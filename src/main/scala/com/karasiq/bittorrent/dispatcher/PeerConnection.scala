package com.karasiq.bittorrent.dispatcher

import java.net.InetSocketAddress

import akka.actor.{ActorRef, FSM}
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.stream.scaladsl._
import akka.util.ByteString
import com.karasiq.bittorrent.dispatcher.PeerConnectionContext._
import com.karasiq.bittorrent.dispatcher.PeerConnectionState._
import com.karasiq.bittorrent.dispatcher.PeerProtocol._
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
  case class QueuedDownload(pipelined: Boolean, blockSize: Int, block: PieceBlock, receiver: ActorRef)
  case class DownloadContext(queue: List[QueuedDownload], connection: ActorRef, ownData: PeerData, peerData: PeerData) extends PeerContext
}

class PeerConnection(torrent: TorrentMetadata) extends FSM[PeerConnectionState, PeerConnectionContext] with ImplicitMaterializer {
  import context.system
  startWith(Idle, NoData)

  when(Idle, 10 seconds) {
    case Event(ConnectPeer(address, ownData), _) ⇒
      IO(Tcp) ! Connect(address)
      goto(Connecting) using ConnectingContext(ownData)

    case Event(StateTimeout, _) ⇒
      stop()
  }

  when(Connecting, 10 seconds) {
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
    case Event(Received(Msg(Msg.Request(piece))), ctx: PeerContext) ⇒
      log.info("Peer requested piece #{}", piece)
      // TODO: Seeding
      stay()

    case Event(HasPiece(piece), ctx: PeerContext) if (0 until torrent.pieces).contains(piece) ⇒
      ctx.connection ! Write(Msg.Have(piece))
      stay() using (ctx match {
        case p: PeerContextHolder ⇒
          p.copy(ownData = ctx.ownData.copy(completed = ctx.ownData.completed + piece))

        case p: DownloadContext ⇒
          p.copy(ownData = ctx.ownData.copy(completed = ctx.ownData.completed + piece))
      })

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

    case Event(Received(Msg(Msg.Have(piece))), ctx: PeerContext) if (0 until torrent.pieces).contains(piece) ⇒
      log.debug("Peer has piece #{}: {}", piece, ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(completed = ctx.peerData.completed + piece))

    case Event(Received(Msg(msg @ Msg.BitField(bitSet))), ctx: PeerContext) if msg.length == torrent.pieces / 8 + 1 ⇒
      val interesting = bitSet.&~(ctx.ownData.completed).nonEmpty
      if (interesting) {
        ctx.connection ! Write(Msg.Interested())
      }
      log.debug("Bit field updated: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(completed = bitSet, interesting = interesting))
  }

  when(ConnectionEstablished) {
    val pf: StateFunction = {
      case Event(Received(Msg.Handshake(PeerHandshake(protocol, infoHash, peerId))), HandshakeContext(connection, peerAddress, ownData)) ⇒
        if (infoHash != torrent.infoHash) {
          log.warning("Invalid info hash from {} ({})", peerAddress, peerId)
          self ! Close
          stay()
        } else {
          log.info("Peer handshake finished: {} (id = {})", peerAddress, peerId.utf8String)
          val newPeerData = PeerData(peerAddress, peerId, infoHash)
          context.parent ! PeerConnected(newPeerData)
          connection ! Write(Msg.BitField(torrent.pieces, ownData.completed))
          stay() using PeerContextHolder(connection, ownData, newPeerData)
        }

      case Event(request @ PieceBlockDownloadRequest(index, block @ TorrentPieceBlock(offset, size)), ctx: PeerContext) ⇒
        val sender = context.sender()
        log.debug("Downloading piece #{} block {}/{} from peer: {}", index, block.offset, block.size, ctx.peerData.address)
        if (ctx.peerData.chokedBy || !ctx.peerData.completed(index)) {
          sender ! PieceBlockDownloadFailed(index, block.offset.toInt, block.size.toInt)
          stay()
        } else {
          self.tell(request, sender)
          goto(Downloading) using DownloadContext(Nil, ctx.connection, ctx.ownData, ctx.peerData)
        }

      case Event(_: ConnectionClosed | _: CloseCommand | _: CommandFailed, ctx: PeerContext) ⇒
        ctx.connection ! Close
        log.warning(s"Peer disconnected: ${ctx.peerData.address}")
        context.parent ! PeerDisconnected(ctx.peerData)
        stop()

      case Event(_: ConnectionClosed | _: CloseCommand | _: CommandFailed, HandshakeContext(connection, address, _)) ⇒
        connection ! Close
        log.warning(s"Handshake failed: $address")
        stop()
    }
    pf.orElse(stateMessage)
  }

  when(Downloading, 10 seconds) {
    val pipelineLimit = 5

    def enqueue(ctx: DownloadContext, queue: List[QueuedDownload]): State = {
      val (pipeline, rest) = queue.splitAt(pipelineLimit)
      pipeline.filterNot(_.pipelined).foreach {
        case QueuedDownload(_, size, PieceBlock(index, offset, _), _) ⇒
          Write(Msg.Request(index, offset.toInt, size.toInt))
      }
      stay() using ctx.copy(pipeline.map(_.copy(pipelined = true)) ++ rest)
    }

    val pf: StateFunction = {
      case Event(PieceBlockDownloadRequest(index, TorrentPieceBlock(offset, size)), ctx @ DownloadContext(queue, connection, ownData, peerData)) ⇒
        if (ctx.peerData.chokedBy) {
          sender() ! PieceBlockDownloadFailed(index, offset.toInt, size.toInt)
          stay()
        } else if (queue.length < pipelineLimit) {
          ctx.connection ! Write(Msg.Request(index, offset.toInt, size.toInt))
          stay() using ctx.copy(queue :+ QueuedDownload(pipelined = true, size.toInt, PieceBlock(index, offset.toInt, ByteString.empty), sender()))
        } else {
          stay() using ctx.copy(queue :+ QueuedDownload(pipelined = false, size.toInt, PieceBlock(index, offset.toInt, ByteString.empty), sender()))
        }

      case Event(Received(Msg(msg @ Msg.Piece(block @ PieceBlock(_, _, data)))), ctx @ DownloadContext(QueuedDownload(pipeline, size, PieceBlock(index, offset, _), handler) :: rest, connection, ownData, peerData)) if block.index == index && block.offset == offset && msg.length - 9 == size ⇒
        log.debug("Block requested: {}/{}/{}", index, offset, size)
        if (data.length >= size) {
          val (keep, resend) = data.splitAt(size)
          log.debug("Block finished: {}/{}/{}", index, offset, size)
          handler ! DownloadedBlock(index, offset, keep)
          if (resend.nonEmpty) {
            self.forward(Received(resend))
          }
          enqueue(ctx, rest)
        } else {
          stay() using ctx.copy(QueuedDownload(pipelined = true, size, block, handler) :: rest)
        }

      case Event(Received(data), ctx @ DownloadContext(QueuedDownload(true, size, block, handler) :: rest, connection, ownData, peerData)) if block.data.nonEmpty ⇒
        if (data.length >= size - block.data.length) {
          val (keep, resend) = data.splitAt(size - block.data.length)
          log.debug("Block finished: {}/{}/{}", block.index, block.offset, size)
          handler ! DownloadedBlock(block.index, block.offset, block.data ++ keep)
          if (resend.nonEmpty) {
            self.forward(Received(resend))
          }
          enqueue(ctx, rest)
        } else {
          stay() using ctx.copy(QueuedDownload(pipelined = true, size, block.copy(data = block.data ++ data), handler) :: rest)
        }

      case Event(CancelPieceBlockDownload(index, offset, length), ctx @ DownloadContext(queue, connection, ownData, peerData)) ⇒
        val (drop, keep) = queue.partition(q ⇒ q.block.index == index && q.block.offset == offset && q.blockSize == length)
        if (drop.exists(_.pipelined)) {
          connection ! Write(Msg.Cancel(index, offset, length))
        }
        stay() using ctx.copy(keep)

      case Event(Received(Msg(Msg.Choke(_))) | StateTimeout, DownloadContext(queue, connection, ownData, peerData)) ⇒
        queue.foreach {
          case QueuedDownload(pipelined, size, PieceBlock(index, offset, _), handler) ⇒
            if (pipelined) {
              connection ! Write(Msg.Cancel(index, offset, size))
            }
            handler ! PieceBlockDownloadFailed(index, offset, size)
        }
        goto(ConnectionEstablished) using PeerContextHolder(connection, ownData, peerData)

      case Event(_: ConnectionClosed | _: CommandFailed | _: CloseCommand, DownloadContext(queue, connection, ownData, peerData)) ⇒
        queue.foreach {
          case QueuedDownload(pipelined, size, PieceBlock(index, offset, _), handler) ⇒
            if (pipelined) {
              connection ! Write(Msg.Cancel(index, offset, size))
            }
            handler ! PieceBlockDownloadFailed(index, offset, size)
        }
        connection ! Close
        log.warning(s"Peer disconnected: ${peerData.address}")
        context.parent ! PeerDisconnected(peerData)
        stop()
    }
    pf.orElse(stateMessage)
  }

  whenUnhandled {
    case Event(Received(data), _: PeerContext | _: HandshakeContext) ⇒
      log.warning("Invalid data received: {}", data)
      self ! Close
      stay()
  }
}
