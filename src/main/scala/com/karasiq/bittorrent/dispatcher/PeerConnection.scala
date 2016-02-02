package com.karasiq.bittorrent.dispatcher

import java.io.IOException
import java.net.InetSocketAddress

import akka.actor.{ActorRef, FSM, Stash}
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl._
import com.karasiq.bittorrent.dispatcher.PeerConnectionContext._
import com.karasiq.bittorrent.dispatcher.PeerConnectionState._
import com.karasiq.bittorrent.dispatcher.PeerProtocol.{Msg, PeerHandshake, PieceRequest}
import com.karasiq.bittorrent.format.{TorrentMetadata, TorrentPiece, TorrentPieceBlock}

import scala.collection.BitSet
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

case class ConnectPeer(address: InetSocketAddress, ownData: PeerData)
case class DownloadFinished(index: Int)

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
  case class DownloadContext(index: Int, receiver: ActorRef, connection: ActorRef, ownData: PeerData, peerData: PeerData) extends PeerContext
}

class PeerConnection(torrent: TorrentMetadata) extends FSM[PeerConnectionState, PeerConnectionContext] with Stash with ImplicitMaterializer {
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
    case Event(Received(Msg(Msg.Choke())), ctx: PeerContext) ⇒
      log.debug("Choked: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(chokedBy = true))

    case Event(Received(Msg(Msg.Unchoke())), ctx: PeerContext) ⇒
      log.debug("Unchoked: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(chokedBy = false))

    case Event(Received(Msg(Msg.Interested())), ctx: PeerContext) ⇒
      log.debug("Interested: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(interestedBy = true))

    case Event(Received(Msg(Msg.NotInterested())), ctx: PeerContext) ⇒
      log.debug("Not interested: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(interestedBy = false))

    case Event(Received(Msg(Msg.Have(pieceIndex))), ctx: PeerContext) ⇒
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
        val newPeerData = PeerData(peerAddress, peerId, infoHash, choking = false, interesting = false, chokedBy = false, interestedBy = false, BitSet.empty)
        context.parent ! PeerConnected(newPeerData)
        stay() using PeerContextHolder(connection, ownData, newPeerData)

      case Event(PieceDownloadRequest(index, piece), ctx: PeerContext) ⇒
        log.info(s"Downloading piece $index from peer: ${ctx.peerData.address}")
        if (ctx.peerData.chokedBy || !ctx.peerData.completed(index)) {
          sender() ! PieceDownloadFailed
          stay()
        } else {
          val self = context.self
          val sender = context.sender()
          val blocks = TorrentPiece.blocks(piece, math.pow(2, 14).toLong)
          val stage = Source.actorRef(100, OverflowStrategy.fail)
            .transform(() ⇒ new PieceBlockStage(index, torrent.files.pieceLength.toInt))
            .take(1)
            .log("downloaded-piece")
            .to(Sink.onComplete {
              case Success(result) ⇒
                sender ! result
                self ! DownloadFinished(index)

              case Failure(exc) ⇒
                log.error(exc, "Piece download error: {}", index)
                sender ! PieceDownloadFailed
                self ! DownloadFinished(index)
            })
            .run()
          blocks.foreach {
            case TorrentPieceBlock(offset, size) ⇒
              ctx.connection ! Write(PieceRequest(index, offset.toInt, size.toInt).toBytes)
          }
          goto(Downloading) using DownloadContext(index, stage, ctx.connection, ctx.ownData, ctx.peerData)
        }

      case Event(_: ConnectionClosed | _: CloseCommand | _: CommandFailed | StateTimeout, ctx: PeerContext) ⇒
        log.warning(s"Peer disconnected: ${ctx.peerData}")
        context.parent ! PeerDisconnected(ctx.peerData)
        stop()
    }
    pf.orElse(stateMessage)
  }

  when(Downloading, 5 minutes) {
    val pf: StateFunction = {
      case Event(Received(Msg(Msg.Piece(block))), DownloadContext(index, handler, connection, ownData, peerData)) if block.index == index ⇒
        handler ! block
        stay()

      case Event(r @ Received(Msg(Msg.Choke())), DownloadContext(index, handler, connection, ownData, peerData)) ⇒
        handler ! Failure(new IOException("Peer choked download")) // Download failed
        self.forward(r)
        goto(ConnectionEstablished) using PeerContextHolder(connection, ownData, peerData)

      case Event(DownloadFinished(finishedIndex), DownloadContext(index, handler, connection, ownData, peerData)) if index == finishedIndex ⇒
        goto(ConnectionEstablished) using PeerContextHolder(connection, ownData, peerData)

      case Event(_: PieceDownloadRequest, _) ⇒
        stash()
        stay()

      case Event(_: ConnectionClosed | _: CommandFailed | _: CloseCommand | StateTimeout, DownloadContext(index, handler, connection, ownData, peerData)) ⇒
        handler ! Failure(new IOException("Connection closed or timed out"))
        log.warning(s"Peer disconnected: $peerData")
        context.parent ! PeerDisconnected(peerData)
        stop()
    }
    pf.orElse(stateMessage)
  }

  onTransition {
    case Downloading -> ConnectionEstablished ⇒
      unstashAll()
  }
}
