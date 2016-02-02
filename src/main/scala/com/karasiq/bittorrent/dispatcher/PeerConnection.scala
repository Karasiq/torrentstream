package com.karasiq.bittorrent.dispatcher

import java.net.InetSocketAddress

import akka.actor.{ActorRef, FSM, Props}
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import com.karasiq.bittorrent.dispatcher.PeerConnectionContext._
import com.karasiq.bittorrent.dispatcher.PeerConnectionState._
import com.karasiq.bittorrent.dispatcher.PeerProtocol.{Msg, PeerHandshake}
import com.karasiq.bittorrent.format.{TorrentMetadata, TorrentPiece}

import scala.collection.BitSet
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
}

sealed trait PeerConnectionContext
object PeerConnectionContext {
  case object NoData extends PeerConnectionContext
  case class ConnectingContext(ownData: PeerData) extends PeerConnectionContext
  case class HandshakeContext(connection: ActorRef, address: InetSocketAddress, ownData: PeerData) extends PeerConnectionContext
  case class PeerContext(connection: ActorRef, ownData: PeerData, peerData: PeerData) extends PeerConnectionContext
}

class PeerConnection(torrent: TorrentMetadata)(implicit am: ActorMaterializer) extends FSM[PeerConnectionState, PeerConnectionContext] {
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

    case Event(_: ConnectionClosed | _: CommandFailed | StateTimeout, _) ⇒
      stop()
  }

  def updateState(ctx: PeerContext, newPeerData: PeerData) = {
    context.parent ! PeerStateChanged(newPeerData)
    stay() using ctx.copy(peerData = newPeerData)
  }

  when(ConnectionEstablished, 10 minutes) {
    case Event(Received(Msg.Handshake(PeerHandshake(protocol, infoHash, peerId))), HandshakeContext(connection, peerAddress, ownData)) ⇒
      log.info("Peer handshake finished: {} (id = {})", peerAddress, peerId.utf8String)
      val newPeerData = PeerData(peerAddress, peerId, infoHash, choking = false, interesting = false, chokingBy = false, interestingBy = false, BitSet.empty)
      context.parent ! PeerConnected(newPeerData)
      stay() using PeerContext(connection, ownData, newPeerData)

    case Event(Received(Msg(Msg.Choke())), ctx: PeerContext) ⇒
      updateState(ctx, ctx.peerData.copy(chokingBy = true))

    case Event(Received(Msg(Msg.Unchoke())), ctx: PeerContext) ⇒
      updateState(ctx, ctx.peerData.copy(chokingBy = false))

    case Event(Received(Msg(Msg.Interested())), ctx: PeerContext) ⇒
      updateState(ctx, ctx.peerData.copy(interestingBy = true))

    case Event(Received(Msg(Msg.NotInterested())), ctx: PeerContext) ⇒
      updateState(ctx, ctx.peerData.copy(interestingBy = false))

    case Event(Received(Msg(Msg.Have(pieceIndex))), ctx: PeerContext) ⇒
      updateState(ctx, ctx.peerData.copy(completed = ctx.peerData.completed + pieceIndex))

    case Event(Received(Msg(Msg.BitField(bitSet))), ctx: PeerContext) ⇒
      updateState(ctx, ctx.peerData.copy(completed = bitSet))

    case Event(PieceDownloadRequest(index, piece), ctx: PeerContext) ⇒
      log.info(s"Downloading piece $index from peer: ${ctx.peerData.address}")
      if (ctx.peerData.chokingBy || !ctx.peerData.completed(index)) {
        sender() ! PieceDownloadFailed
      } else {
        val sender = context.sender()
        val blocks = TorrentPiece.blocks(piece, math.pow(2, 14).toLong)
        val stage = Source.actorPublisher(Props(new PieceDownloader(index, ctx.connection)))
          .transform(() ⇒ new PieceBlockStage(index, torrent.files.pieceLength.toInt))
          .log("downloaded-piece")
          .to(Sink.actorRef(sender, null))
          .run()
        blocks.foreach(stage ! _)
      }
      stay()

    case Event(_: ConnectionClosed | _: CommandFailed | StateTimeout, ctx: PeerContext) ⇒
      log.warning(s"Peer disconnected: ${ctx.peerData}")
      context.parent ! PeerDisconnected(ctx.peerData)
      stop()
  }
}
