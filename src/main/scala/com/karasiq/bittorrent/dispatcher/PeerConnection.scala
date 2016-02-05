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
import com.karasiq.bittorrent.format.TorrentMetadata

import scala.annotation.tailrec
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

  case class QueuedDownload(pipelined: Boolean, blockSize: Int, block: PieceBlock, handler: ActorRef)
  case class QueuedUpload(index: Int, offset: Int, length: Int, data: ByteString = ByteString.empty)
  case class PeerContext(downloadQueue: List[QueuedDownload], uploadQueue: List[QueuedUpload], connection: ActorRef, ownData: PeerData, peerData: PeerData) extends PeerConnectionContext
}

class PeerConnection(torrent: TorrentMetadata) extends FSM[PeerConnectionState, PeerConnectionContext] with ImplicitMaterializer {
  import context.system

  // Settings
  private val config = system.settings.config.getConfig("karasiq.torrentstream.peer-connection")
  private val downloadQueueLimit = config.getInt("download-queue-size")
  private val uploadQueueLimit = config.getInt("upload-queue-size")

  startWith(Idle, NoData)

  when(Idle, 2 seconds) {
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

  private def updateState(ctx: PeerContext, newPeerData: PeerData): State = {
    context.parent ! PeerStateChanged(newPeerData)
    stay() using ctx.copy(peerData = newPeerData)
  }

  private def download(ctx: PeerContext, queue: List[QueuedDownload]): State = {
    val newQueue: List[QueuedDownload] = {
      val (pipeline, rest) = queue.splitAt(downloadQueueLimit)
      pipeline.filterNot(_.pipelined).foreach {
        case QueuedDownload(_, size, PieceBlock(index, offset, _), _) ⇒
          Write(Msg.Request(index, offset, size))
      }
      pipeline.map(_.copy(pipelined = true)) ++ rest
    }
    if (newQueue.nonEmpty) {
      stay() using ctx.copy(newQueue)
    } else {
      goto(ConnectionEstablished) using ctx
    }
  }

  @tailrec
  private def upload(ctx: PeerContext, queue: List[QueuedUpload]): State = queue match {
    case QueuedUpload(index, offset, length, data) :: rest if data.nonEmpty ⇒
      ctx.connection ! Write(Msg.Piece(index, offset, data))
      upload(ctx, rest)

    case _ ⇒
      if (ctx.peerData.choking && queue.length < uploadQueueLimit) {
        log.info("Unchoking peer: {}", ctx.peerData.address)
        ctx.connection ! Write(Msg.Unchoke())
        val data = ctx.peerData.copy(choking = false)
        context.parent ! PeerStateChanged(data)
        stay() using ctx.copy(uploadQueue = queue, peerData = data)
      } else {
        stay() using ctx.copy(uploadQueue = queue)
      }
  }

  def stateMessage: StateFunction = {
    case Event(Received(Msg(Msg.Request(request @ PieceBlockRequest(index, offset, length)))), ctx: PeerContext) if !ctx.peerData.choking && ctx.uploadQueue.length < uploadQueueLimit ⇒
      log.info("Peer requested piece block: {}", request)
      context.parent ! request
      val queue = ctx.uploadQueue :+ QueuedUpload(index, offset, length)
      if (queue.length == uploadQueueLimit) {
        log.info("Upload limit reached, choking peer: {}", ctx.peerData.address)
        ctx.connection ! Write(Msg.Choke())
        val data = ctx.peerData.copy(choking = true)
        context.parent ! PeerStateChanged(data)
        stay() using ctx.copy(uploadQueue = queue, peerData = data)
      } else {
        stay() using ctx.copy(uploadQueue = queue)
      }

    case Event(DownloadedBlock(index, offset, data), ctx: PeerContext) ⇒
      val updated = ctx.uploadQueue.collect {
        case request @ QueuedUpload(`index`, `offset`, length, _) if length == data.length ⇒
          request.copy(data = data)

        case request ⇒
          request
      }
      upload(ctx, updated)

    case Event(UpdateBitField(completed), ctx: PeerContext) ⇒
      if (ctx.ownData.completed.subsetOf(completed)) {
        completed.&~(ctx.ownData.completed)
          .foreach(piece ⇒ ctx.connection ! Write(Msg.Have(piece)))
      } else {
        ctx.connection ! Write(Msg.BitField(torrent.pieces, completed))
      }
      stay() using ctx.copy(ownData = ctx.ownData.copy(completed = completed))

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
      if (ctx.peerData.choking && ctx.uploadQueue.length < uploadQueueLimit) {
        ctx.connection ! Write(Msg.Unchoke())
        updateState(ctx, ctx.peerData.copy(interestedBy = true, choking = false))
      } else {
        updateState(ctx, ctx.peerData.copy(interestedBy = true))
      }

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
          stay() using PeerContext(Nil, Nil, connection, ownData, newPeerData)
        }

      case Event(request @ PieceBlockRequest(index, offset, size), ctx: PeerContext) ⇒
        val sender = context.sender()
        if (ctx.peerData.chokedBy || !ctx.peerData.completed(index)) {
          sender ! BlockDownloadFailed(index, offset, size)
          stay()
        } else {
          self.tell(request, sender)
          goto(Downloading)
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

  when(Downloading, 3 seconds) {
    val pf: StateFunction = {
      case Event(PieceBlockRequest(index, offset, size), ctx @ PeerContext(queue, _, connection, ownData, peerData)) ⇒
        if (ctx.peerData.chokedBy) {
          sender() ! BlockDownloadFailed(index, offset, size)
          stay()
        } else if (queue.length < downloadQueueLimit) {
          ctx.connection ! Write(Msg.Request(index, offset, size))
          stay() using ctx.copy(queue :+ QueuedDownload(pipelined = true, size, PieceBlock(index, offset, ByteString.empty), sender()))
        } else {
          stay() using ctx.copy(queue :+ QueuedDownload(pipelined = false, size, PieceBlock(index, offset, ByteString.empty), sender()))
        }

      case Event(Received(Msg(msg @ Msg.Piece(block @ PieceBlock(_, _, data)))), ctx @ PeerContext(QueuedDownload(true, size, PieceBlock(index, offset, _), handler) :: rest, _, connection, ownData, peerData)) if block.index == index && block.offset == offset && msg.length - 9 == size ⇒
        log.debug("Block requested: {}/{}/{}", index, offset, size)
        if (data.length >= size) {
          val (keep, resend) = data.splitAt(size)
          log.debug("Block finished: {}/{}/{}", index, offset, size)
          handler ! DownloadedBlock(index, offset, keep)
          if (resend.nonEmpty) {
            self.forward(Received(resend))
          }
          download(ctx, rest)
        } else {
          stay() using ctx.copy(QueuedDownload(pipelined = true, size, block, handler) :: rest)
        }

      case Event(Received(data), ctx @ PeerContext(QueuedDownload(true, size, block, handler) :: rest, _, connection, ownData, peerData)) if block.data.nonEmpty ⇒
        if (data.length >= size - block.data.length) {
          val (keep, resend) = data.splitAt(size - block.data.length)
          log.debug("Block finished: {}/{}/{}", block.index, block.offset, size)
          handler ! DownloadedBlock(block.index, block.offset, block.data ++ keep)
          if (resend.nonEmpty) {
            self.forward(Received(resend))
          }
          download(ctx, rest)
        } else {
          stay() using ctx.copy(QueuedDownload(pipelined = true, size, block.copy(data = block.data ++ data), handler) :: rest)
        }

      case Event(CancelBlockDownload(index, offset, length), ctx @ PeerContext(queue, _, connection, ownData, peerData)) ⇒
        val (drop, keep) = queue.partition(q ⇒ q.block.index == index && q.block.offset == offset && q.blockSize == length)
        drop.foreach(_.handler ! BlockDownloadFailed(index, offset, length))
        if (drop.exists(_.pipelined)) {
          connection ! Write(Msg.Cancel(index, offset, length))
        }
        download(ctx, keep)

      case Event(Received(Msg(Msg.Choke(_))), ctx @ PeerContext(queue, _, connection, ownData, peerData)) ⇒
        val (keep, drop) = queue.partition(_.pipelined)
        drop.foreach {
          case QueuedDownload(_, size, PieceBlock(index, offset, _), handler) ⇒
            handler ! BlockDownloadFailed(index, offset, size)
        }
        val data = peerData.copy(chokedBy = true)
        context.parent ! PeerStateChanged(data)
        download(ctx.copy(peerData = data), keep)

      case Event(StateTimeout, ctx @ PeerContext(queue, _, connection, ownData, peerData)) ⇒
        if (queue.nonEmpty) {
          log.warning("Peer read timeout: {}", peerData.address)
          queue.foreach {
            case QueuedDownload(_, size, PieceBlock(index, offset, _), handler) ⇒
              handler ! BlockDownloadFailed(index, offset, size)
          }
          context.parent ! PeerDisconnected(peerData)
          stop()
        } else {
          log.warning("Connection in downloading state with empty queue: {}", self)
          goto(ConnectionEstablished)
        }

      case Event(_: ConnectionClosed | _: CommandFailed | _: CloseCommand, PeerContext(queue, _, connection, ownData, peerData)) ⇒
        queue.foreach {
          case QueuedDownload(pipelined, size, PieceBlock(index, offset, _), handler) ⇒
            if (pipelined) {
              connection ! Write(Msg.Cancel(index, offset, size))
            }
            handler ! BlockDownloadFailed(index, offset, size)
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
