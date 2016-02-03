package com.karasiq.bittorrent.dispatcher

import java.io.IOException
import java.net.InetSocketAddress

import akka.actor.{ActorRef, FSM, Status}
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.stream.scaladsl._
import akka.util.ByteString
import com.karasiq.bittorrent.dispatcher.PeerConnectionContext._
import com.karasiq.bittorrent.dispatcher.PeerConnectionState._
import com.karasiq.bittorrent.dispatcher.PeerProtocol.{Msg, PeerHandshake}
import com.karasiq.bittorrent.format.{TorrentMetadata, TorrentPieceBlock}

import scala.concurrent.duration._
import scala.language.postfixOps

case class ConnectPeer(address: InetSocketAddress, ownData: PeerData)

sealed trait PeerEvent {
  def data: PeerData
}
case class PeerConnected(data: PeerData) extends PeerEvent
case class PeerStateChanged(data: PeerData) extends PeerEvent
case class PeerDisconnected(data: PeerData) extends PeerEvent

sealed trait PeerConnectionState
object PeerConnectionState {
  case object Idle extends PeerConnectionState
  case object Connecting extends PeerConnectionState
  case object ConnectionEstablished extends PeerConnectionState
  case object Downloading extends PeerConnectionState
}

sealed trait PeerConnectionContext
object PeerConnectionContext {
  case object NoData extends PeerConnectionContext
  case class ConnectingContext(ownData: PeerData) extends PeerConnectionContext
  case class HandshakeContext(connection: ActorRef, address: InetSocketAddress, ownData: PeerData) extends PeerConnectionContext

  sealed trait PeerContext extends PeerConnectionContext {
    def connection: ActorRef
    def ownData: PeerData
    def peerData: PeerData
  }
  case class PeerContextHolder(connection: ActorRef, ownData: PeerData, peerData: PeerData) extends PeerContext
  case class DownloadContext(block: PieceBlockDownloadRequest, buffer: ByteString, index: Int, receiver: ActorRef, connection: ActorRef, ownData: PeerData, peerData: PeerData) extends PeerContext
}

class PeerConnection(torrent: TorrentMetadata) extends FSM[PeerConnectionState, PeerConnectionContext] with ImplicitMaterializer {
  import context.system
  startWith(Idle, NoData)

  when(Idle) {
    case Event(ConnectPeer(address, ownData), _) ⇒
      IO(Tcp) ! Connect(address)
      goto(Connecting) using ConnectingContext(ownData)

    case Event(StateTimeout, _) ⇒
      stop()
  }

  when(Connecting, 3 minutes) {
    case Event(Connected(remote, local), ConnectingContext(ownData)) ⇒
      val connection = sender()
      connection ! Register(self)
      connection ! Write(PeerHandshake("BitTorrent protocol", ownData.infoHash, ownData.id).toBytes)
      goto(ConnectionEstablished) using HandshakeContext(connection, remote, ownData)

    case Event(_: ConnectionClosed |  _: CloseCommand | _: CommandFailed | StateTimeout, _) ⇒
      stop()
  }

  def updateState(ctx: PeerContext, newPeerData: PeerData) = {
    context.parent ! PeerStateChanged(newPeerData)
    stay() using (ctx match {
      case p: PeerContextHolder ⇒
        p.copy(peerData = newPeerData)

      case p: DownloadContext ⇒
        p.copy(peerData = newPeerData)
    })
  }

  def stateMessage: StateFunction = {
    case Event(Received(Msg.KeepAlive(_)), ctx: PeerContext) ⇒
      stay()

    case Event(Received(Msg(Msg.Choke(_))), ctx: PeerContext) ⇒
      log.debug("Choked: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(chokedBy = true))

    case Event(Received(Msg(Msg.Unchoke(_))), ctx: PeerContext) ⇒
      log.debug("Unchoked: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(chokedBy = false))

    case Event(Received(Msg(Msg.Interested(_))), ctx: PeerContext) ⇒
      log.debug("Interested: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(interestedBy = true))

    case Event(Received(Msg(Msg.NotInterested(_))), ctx: PeerContext) ⇒
      log.debug("Not interested: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(interestedBy = false))

    case Event(Received(Msg(Msg.Have(pieceIndex))), ctx: PeerContext) if pieceIndex >= 0 && pieceIndex < torrent.pieces  ⇒
      log.debug("Has piece #{}: {}", pieceIndex, ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(completed = ctx.peerData.completed + pieceIndex))

    case Event(Received(Msg(Msg.BitField(bitSet))), ctx: PeerContext) ⇒
      val interesting = bitSet.&~(ctx.ownData.completed).nonEmpty
      if (interesting) {
        ctx.connection ! Write(Msg.Interested().toBytes)
      }
      log.debug("Bit field updated: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(completed = bitSet, interesting = interesting))
  }

  when(ConnectionEstablished, 10 minutes) {
    val pf: StateFunction = {
      case Event(Received(Msg.Handshake(PeerHandshake(protocol, infoHash, peerId))), HandshakeContext(connection, peerAddress, ownData)) ⇒
        log.info("Peer handshake finished: {} (id = {})", peerAddress, peerId.utf8String)
        val newPeerData = PeerData(peerAddress, peerId, infoHash)
        context.parent ! PeerConnected(newPeerData)
        stay() using PeerContextHolder(connection, ownData, newPeerData)

      case Event(r @ PieceBlockDownloadRequest(index, block @ TorrentPieceBlock(offset, size)), ctx: PeerContext) ⇒
        val sender = context.sender()
        log.debug("Downloading piece #{} block {}/{} from peer: {}", index, block.offset, block.size, ctx.peerData.address)
        if (ctx.peerData.chokedBy || !ctx.peerData.completed(index)) {
          sender ! Status.Failure(new IOException(s"Piece download rejected: $index"))
          stay()
        } else {
          ctx.connection ! Write(Msg.Request(index, offset.toInt, size.toInt).toBytes)
          context.parent ! PeerStateChanged(ctx.peerData.copy(busy = true))
          goto(Downloading) using DownloadContext(r, ByteString.empty, index, sender, ctx.connection, ctx.ownData, ctx.peerData)
        }

      case Event(_: ConnectionClosed | _: CloseCommand | _: CommandFailed | StateTimeout, ctx: PeerContext) ⇒
        ctx.connection ! Close
        log.warning(s"Peer disconnected: ${ctx.peerData.address}")
        context.parent ! PeerDisconnected(ctx.peerData)
        stop()
    }
    pf.orElse(stateMessage)
  }

  when(Downloading, 15 seconds) {
    val pf1: StateFunction = {
      case Event(msg @ Received(Msg(Msg.Choke(_))), ctx @ DownloadContext(request, buffer, index, handler, connection, ownData, peerData)) ⇒
        self ! CancelPieceBlockDownload(index, request.block.offset.toInt, request.block.size.toInt)
        self ! msg
        stay()
    }
    val pf2: StateFunction = {
      case Event(_: PieceBlockDownloadRequest, _) ⇒
        sender() ! Status.Failure(new IOException("Peer is busy"))
        stay()

      case Event(Received(Msg(Msg.Piece(block))), ctx @ DownloadContext(request, buffer, index, handler, connection, ownData, peerData)) if block.index == request.index ⇒
        if (block.data.length > (request.block.size - buffer.length)) {
          log.debug(s"Block discarded: ${block.offset}/${block.data.length} bytes")
          stay()
        } else if (block.data.length == request.block.size - buffer.length) {
          log.debug("Block finished: {}/{}/{}", index, request.block.offset, request.block.size)
          handler ! Status.Success(DownloadedBlock(request.index, request.block.offset.toInt, buffer ++ block.data))
          context.parent ! PeerStateChanged(ctx.peerData.copy(busy = false))
          goto(ConnectionEstablished) using PeerContextHolder(connection, ownData, peerData)
        } else {
          stay() using ctx.copy(buffer = buffer ++ block.data)
        }

      case Event(Received(data), ctx @ DownloadContext(request, buffer, index, handler, connection, ownData, peerData)) if buffer.nonEmpty ⇒
        if (data.length > (request.block.size - buffer.length)) {
          log.debug(s"Block discarded: ${request.block.offset}/${data.length} bytes")
          stay()
        } else if (data.length == request.block.size - buffer.length) {
          log.debug("Block finished: {}/{}/{}", index, request.block.offset, request.block.size)
          handler ! Status.Success(DownloadedBlock(request.index, request.block.offset.toInt, buffer ++ data))
          context.parent ! PeerStateChanged(ctx.peerData.copy(busy = false))
          goto(ConnectionEstablished) using PeerContextHolder(connection, ownData, peerData)
        } else {
          stay() using ctx.copy(buffer = buffer ++ data)
        }

      case Event(CancelPieceBlockDownload(index, offset, length), ctx: DownloadContext) if ctx.index == index && ctx.block.block.offset == offset && ctx.block.block.size == length ⇒
        ctx.receiver ! Status.Failure(new IOException("Cancelled"))
        // log.warning("Block cancelled: {}/{}/{}", index, offset, length)
        ctx.connection ! Write(Msg.Cancel(index, offset, length).toBytes)
        context.parent ! PeerStateChanged(ctx.peerData.copy(busy = false))
        goto(ConnectionEstablished) using PeerContextHolder(ctx.connection, ctx.ownData, ctx.peerData)

      case Event(_: ConnectionClosed | _: CommandFailed | _: CloseCommand | StateTimeout, DownloadContext(request, buffer, index, handler, connection, ownData, peerData)) ⇒
        connection ! Write(Msg.Cancel(request.index, request.block.offset.toInt, request.block.size.toInt).toBytes)
        connection ! Close
        handler ! Status.Failure(new IOException("Connection closed or timed out"))
        log.warning(s"Peer disconnected: ${peerData.address}")
        context.parent ! PeerDisconnected(peerData)
        stop()
    }
    pf1.orElse(stateMessage).orElse(pf2)
  }

  whenUnhandled {
    case _ ⇒
      stay()
  }
}
