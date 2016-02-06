package com.karasiq.bittorrent.dispatcher

import java.net.InetSocketAddress

import akka.actor.{ActorRef, FSM}
import akka.io.Tcp._
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import akka.stream.scaladsl._
import akka.util.ByteString
import com.karasiq.bittorrent.dispatcher.PeerConnectionContext._
import com.karasiq.bittorrent.dispatcher.PeerConnectionState._
import com.karasiq.bittorrent.dispatcher.PeerProtocol._
import com.karasiq.bittorrent.format.TorrentMetadata

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.language.postfixOps

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
  case class HandshakeContext(address: InetSocketAddress, ownData: PeerData) extends PeerConnectionContext

  case class QueuedDownload(pipelined: Boolean, index: Int, offset: Int, length: Int, handler: ActorRef)
  case class QueuedUpload(index: Int, offset: Int, length: Int, data: ByteString = ByteString.empty)
  case class PeerContext(downloadQueue: List[QueuedDownload], uploadQueue: List[QueuedUpload], ownData: PeerData, peerData: PeerData) extends PeerConnectionContext
}

class PeerConnection(peerDispatcher: ActorRef, torrent: TorrentMetadata, peerAddress: InetSocketAddress, initData: PeerData) extends FSM[PeerConnectionState, PeerConnectionContext] with ActorPublisher[ByteString] with ImplicitMaterializer {
  import context.system

  // Settings
  private val config = system.settings.config.getConfig("karasiq.torrentstream.peer-connection")
  private val downloadQueueLimit = config.getInt("download-queue-size")
  private val uploadQueueLimit = config.getInt("upload-queue-size")

  private var messageBuffer = Vector.empty[PeerTcpMessage]

  startWith(ConnectionEstablished, HandshakeContext(peerAddress, initData))

  def stateMessage: StateFunction = {
    case Event(Request(_), _) ⇒
      pushBuffer()
      stay()

    case Event(Msg.Request(request @ PieceBlockRequest(index, offset, length)), ctx: PeerContext) if !ctx.peerData.choking ⇒
      log.info("Peer requested piece block: {}", request)
      peerDispatcher ! request
      val queue = ctx.uploadQueue :+ QueuedUpload(index, offset, length)
      if (queue.length == uploadQueueLimit) {
        log.info("Upload limit reached, choking peer: {}", ctx.peerData.address)
        pushMessage(Msg.Choke())
        val data = ctx.peerData.copy(choking = true)
        peerDispatcher ! PeerStateChanged(data)
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
          .foreach(piece ⇒ pushMessage(Msg.Have(piece)))
      } else {
        pushMessage(Msg.BitField(torrent.pieces, completed))
      }
      stay() using ctx.copy(ownData = ctx.ownData.copy(completed = completed))

    case Event(Msg.Choke(_), ctx: PeerContext) ⇒
      log.debug("Choked: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(chokedBy = true))

    case Event(Msg.Unchoke(_), ctx: PeerContext) ⇒
      log.debug("Unchoked: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(chokedBy = false))

    case Event(Msg.Interested(_), ctx: PeerContext) ⇒
      log.debug("Interested: {}", ctx.peerData.address)
      if (ctx.peerData.choking && ctx.uploadQueue.length < uploadQueueLimit) {
        pushMessage(Msg.Unchoke())
        updateState(ctx, ctx.peerData.copy(interestedBy = true, choking = false))
      } else {
        updateState(ctx, ctx.peerData.copy(interestedBy = true))
      }

    case Event(Msg.NotInterested(_), ctx: PeerContext) ⇒
      log.debug("Not interested: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(interestedBy = false))

    case Event(Msg.Have(piece), ctx: PeerContext) if (0 until torrent.pieces).contains(piece) ⇒
      log.debug("Peer has piece #{}: {}", piece, ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(completed = ctx.peerData.completed + piece))

    case Event(msg @ Msg.BitField(bitSet), ctx: PeerContext) ⇒
      if (msg.length == torrent.pieces / 8 + 1) {
        val interesting = bitSet.&~(ctx.ownData.completed).nonEmpty
        if (interesting) {
          pushMessage(Msg.Interested())
        }
        log.debug("Bit field updated: {}", ctx.peerData.address)
        updateState(ctx, ctx.peerData.copy(completed = bitSet, interesting = interesting))
      } else {
        onError(new IllegalArgumentException("Invalid bit field"))
        stop()
      }
  }

  when(ConnectionEstablished, 10 minutes) {
    val pf: StateFunction = {
      case Event(PeerHandshake(protocol, infoHash, peerId), HandshakeContext(address, ownData)) ⇒
        if (infoHash != ownData.infoHash) {
          log.warning("Invalid info hash from {} ({})", address, peerId)
          self ! Close
          stay()
        } else {
          log.info("Peer handshake finished: {} (id = {})", address, peerId.utf8String)
          val newPeerData = PeerData(address, peerId, infoHash)
          peerDispatcher ! PeerConnected(newPeerData)
          pushMessage(Msg.BitField(torrent.pieces, ownData.completed))
          stay() using PeerContext(Nil, Nil, ownData, newPeerData)
        }

      case Event(request @ PieceBlockRequest(index, offset, length), ctx: PeerContext) ⇒
        val sender = context.sender()
        if (ctx.ownData.completed(index)) {
          // From cache
          peerDispatcher.tell(request, sender)
          stay()
        } else if (ctx.peerData.chokedBy || !ctx.peerData.completed(index)) {
          log.warning("Block download failed: peer choked ({}/{}/{})", index, offset, length)
          sender ! BlockDownloadFailed(index, offset, length)
          stay()
        } else {
          self.tell(request, sender)
          goto(Downloading)
        }

      case Event(StateTimeout | Cancel, ctx: PeerContext) ⇒
        log.warning(s"Peer disconnected: ${ctx.peerData.address}")
        peerDispatcher ! PeerDisconnected(ctx.peerData)
        onComplete()
        stop()

      case Event(StateTimeout | Cancel, HandshakeContext(address, _)) ⇒
        log.warning(s"Handshake failed: $address")
        onComplete()
        stop()
    }
    pf.orElse(stateMessage)
  }

  when(Downloading, 15 seconds) {
    val pf: StateFunction = {
      case Event(request @ PieceBlockRequest(index, offset, length), ctx @ PeerContext(queue, _, ownData, peerData)) ⇒
        if (ownData.completed(index)) {
          // From cache
          peerDispatcher.tell(request, sender)
          stay()
        } else if (ctx.peerData.chokedBy) {
          log.warning("Block download failed: peer choked ({}/{}/{})", index, offset, length)
          sender() ! BlockDownloadFailed(index, offset, length)
          stay()
        } else if (queue.length < downloadQueueLimit) {
          pushMessage(Msg.Request(index, offset, length))
          stay() using ctx.copy(queue :+ QueuedDownload(pipelined = true, index, offset, length, sender()))
        } else {
          stay() using ctx.copy(queue :+ QueuedDownload(pipelined = false, index, offset, length, sender()))
        }

      case Event(Msg.Piece(block @ PieceBlock(index, offset, data)), ctx @ PeerContext(queue, _, ownData, peerData)) if block.index == index && block.offset == offset ⇒
        val (drop, keep) = queue.partition(q ⇒ q.index == index && q.offset == offset && q.length == data.length)
        if (drop.nonEmpty) {
          log.debug("Block received: {}/{}/{}", index, offset, data.length)
          drop.foreach {
            case QueuedDownload(_, _, _, _, handler) ⇒
              handler ! DownloadedBlock(index, offset, data)
          }
        }
        download(ctx, keep)

      case Event(CancelBlockDownload(index, offset, length), ctx @ PeerContext(queue, _, ownData, peerData)) ⇒
        val (drop, keep) = queue.partition(q ⇒ q.index == index && q.offset == offset && q.length == length)
        drop.foreach(_.handler ! BlockDownloadFailed(index, offset, length))
        if (drop.exists(_.pipelined)) {
          pushMessage(Msg.Cancel(index, offset, length))
        }
        download(ctx, keep)

      case Event(Msg.Choke(_), ctx @ PeerContext(queue, _, ownData, peerData)) ⇒
        val (keep, drop) = queue.partition(_.pipelined)
        drop.foreach {
          case QueuedDownload(_, index, offset, length, handler) ⇒
            log.debug("Block download failed: peer choked ({}/{}/{})", index, offset, length)
            handler ! BlockDownloadFailed(index, offset, length)
        }
        val data = peerData.copy(chokedBy = true)
        peerDispatcher ! PeerStateChanged(data)
        download(ctx.copy(peerData = data), keep)

      case Event(StateTimeout, ctx @ PeerContext(queue, _, ownData, peerData)) ⇒
        if (queue.nonEmpty) {
          queue.foreach {
            case QueuedDownload(pipelined, index, offset, length, handler) ⇒
              if (pipelined) {
                pushMessage(Msg.Cancel(index, offset, length))
              }
              log.debug("Block download failed: read timeout ({}/{}/{})", index, offset, length)
              handler ! BlockDownloadFailed(index, offset, length)
          }
          peerDispatcher ! PeerDisconnected(peerData)
          onComplete()
          stop()
        } else {
          log.warning("Connection in downloading state with empty queue: {}", self)
          goto(ConnectionEstablished)
        }

      case Event(Cancel, PeerContext(queue, _, ownData, peerData)) ⇒
        queue.foreach {
          case QueuedDownload(pipelined, index, offset, length, handler) ⇒
            handler ! BlockDownloadFailed(index, offset, length)
            log.debug("Block download failed: peer connection closed ({}/{}/{})", index, offset, length)
        }
        log.warning(s"Peer disconnected: ${peerData.address}")
        peerDispatcher ! PeerDisconnected(peerData)
        onComplete()
        stop()
    }
    pf.orElse(stateMessage)
  }

  whenUnhandled {
    case Event(Msg.Piece(block), ctx) ⇒
      log.debug("Unhandled block: {}; Context: {}", block, ctx)
      stay()

    case Event(msg, ctx) ⇒
      log.warning("Unhandled message: {}; Context: {}", msg, ctx)
      stay()
  }

  private def pushBuffer(): Unit = {
    if (totalDemand > 0) {
      val (send, keep) = messageBuffer.splitAt(totalDemand.toInt)
      send.foreach(msg ⇒ onNext(msg))
      messageBuffer = keep
    }
  }

  private def pushMessage(message: PeerTcpMessage): Unit = {
    require(messageBuffer.length < 200)
    messageBuffer :+= message
    pushBuffer()
  }

  private def updateState(ctx: PeerContext, newPeerData: PeerData): State = {
    peerDispatcher ! PeerStateChanged(newPeerData)
    stay() using ctx.copy(peerData = newPeerData)
  }

  private def download(ctx: PeerContext, queue: List[QueuedDownload]): State = {
    val newQueue: List[QueuedDownload] = {
      val (pipeline, rest) = queue.splitAt(downloadQueueLimit)
      pipeline.filterNot(_.pipelined).foreach {
        case QueuedDownload(_, index, offset, length, _) ⇒
          pushMessage(Msg.Request(index, offset, length))
      }
      pipeline.map(_.copy(pipelined = true)) ++ rest
    }
    if (newQueue.nonEmpty) {
      stay() using ctx.copy(newQueue)
    } else {
      goto(ConnectionEstablished) using ctx.copy(Nil)
    }
  }

  @tailrec
  private def upload(ctx: PeerContext, queue: List[QueuedUpload]): State = queue match {
    case QueuedUpload(index, offset, length, data) :: rest if data.nonEmpty ⇒
      pushMessage(Msg.Piece(index, offset, data))
      upload(ctx, rest)

    case _ ⇒
      if (ctx.peerData.choking && queue.length < uploadQueueLimit) {
        log.info("Unchoking peer: {}", ctx.peerData.address)
        pushMessage(Msg.Unchoke())
        val data = ctx.peerData.copy(choking = false)
        peerDispatcher ! PeerStateChanged(data)
        stay() using ctx.copy(uploadQueue = queue, peerData = data)
      } else {
        stay() using ctx.copy(uploadQueue = queue)
      }
  }
}

object PeerConnection {
  def messageFlow = Flow[ByteString].transform(() ⇒ new PeerConnectionStage(131072))
}