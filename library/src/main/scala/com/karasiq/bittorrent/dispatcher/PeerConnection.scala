package com.karasiq.bittorrent.dispatcher

import java.io.IOException
import java.net.InetSocketAddress

import akka.actor.{ActorRef, FSM, Props}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import akka.stream.scaladsl._
import akka.util.ByteString
import com.karasiq.bittorrent.dispatcher.PeerConnectionContext._
import com.karasiq.bittorrent.dispatcher.PeerConnectionState._
import com.karasiq.bittorrent.format.Torrent
import com.karasiq.bittorrent.protocol.PeerMessages._
import com.karasiq.bittorrent.protocol.{PeerConnectionStage, PeerMessageId, TcpMessageWriter}

import scala.annotation.tailrec
import scala.collection.BitSet
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
  case object Downloading extends PeerConnectionState
}

sealed trait PeerConnectionContext
object PeerConnectionContext {
  case class HandshakeContext(address: InetSocketAddress, ownData: SeedData) extends PeerConnectionContext

  case class QueuedDownload(pipelined: Boolean, index: Int, offset: Int, length: Int, handler: ActorRef, time: Long)
  case class QueuedUpload(index: Int, offset: Int, length: Int, data: ByteString = ByteString.empty)
  case class PeerContext(downloadQueue: List[QueuedDownload], uploadQueue: List[QueuedUpload], ownData: SeedData, peerData: PeerData) extends PeerConnectionContext
}

// TODO: PEX, DHT, UDP trackers
class PeerConnection(peerDispatcher: ActorRef, torrent: Torrent, peerAddress: InetSocketAddress, initData: SeedData) extends FSM[PeerConnectionState, PeerConnectionContext] with ActorPublisher[ByteString] with ImplicitMaterializer with PeerMessageMatcher {
  import context.system

  // Settings
  private val config = system.settings.config.getConfig("karasiq.torrentstream.peer-connection")
  private val updateBitField = config.getBoolean("update-bitfield")
  private val downloadQueueLimit = config.getInt("download-queue-size")
  private val uploadQueueLimit = config.getInt("upload-queue-size")

  private var messageBuffer = Vector.empty[ByteString]

  startWith(Idle, HandshakeContext(peerAddress, initData))

  def stateMessage: StateFunction = {
    case Event(Request(_), _) ⇒
      pushBuffer()
      stay()

    case Event(RequestMsg(request @ PieceBlockRequest(index, offset, length)), ctx: PeerContext) ⇒
      if (ctx.peerData.choking) {
        if (ctx.peerData.extensions.fast) {
          pushMessage(PeerMessage(PeerMessageId.REJECT_REQUEST, request))
        } else {
          log.warning("Piece request while choked: {}", ctx.peerData.address)
        }
        stay()
      } else {
        log.debug("Peer requested piece block: {}", request)
        peerDispatcher ! request
        val queue = ctx.uploadQueue :+ QueuedUpload(index, offset, length)
        if (queue.length == uploadQueueLimit) {
          log.info("Upload limit reached, choking peer: {}", ctx.peerData.address)
          pushMessage(PeerMessage(PeerMessageId.CHOKE))
          val data = ctx.peerData.copy(choking = true)
          peerDispatcher ! PeerStateChanged(data)
          stay() using ctx.copy(uploadQueue = queue, peerData = data)
        } else {
          stay() using ctx.copy(uploadQueue = queue)
        }
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
      if (updateBitField) {
        if (ctx.ownData.completed.subsetOf(completed)) {
          completed.&~(ctx.ownData.completed)
            .foreach(piece ⇒ pushMessage(PeerMessage(PeerMessageId.HAVE, PieceIndex(piece))))
        } else {
          pushMessage(PeerMessage(PeerMessageId.BITFIELD, BitField(torrent.pieces, completed)))
        }
      }
      stay() using ctx.copy(ownData = ctx.ownData.copy(completed = completed))

    case Event(EmptyMsg(PeerMessageId.CHOKE), ctx: PeerContext) ⇒
      log.debug("Choked: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(chokedBy = true))

    case Event(EmptyMsg(PeerMessageId.UNCHOKE), ctx: PeerContext) ⇒
      log.debug("Unchoked: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(chokedBy = false))

    case Event(EmptyMsg(PeerMessageId.INTERESTED), ctx: PeerContext) ⇒
      log.debug("Interested: {}", ctx.peerData.address)
      if (ctx.peerData.choking && ctx.uploadQueue.length < uploadQueueLimit) {
        pushMessage(PeerMessage(PeerMessageId.UNCHOKE))
        updateState(ctx, ctx.peerData.copy(interestedBy = true, choking = false))
      } else {
        updateState(ctx, ctx.peerData.copy(interestedBy = true))
      }

    case Event(EmptyMsg(PeerMessageId.NOT_INTERESTED), ctx: PeerContext) ⇒
      log.debug("Not interested: {}", ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(interestedBy = false))

    case Event(HaveMsg(PieceIndex(piece)), ctx: PeerContext) if (0 until torrent.pieces).contains(piece) ⇒
      log.debug("Peer has piece #{}: {}", piece, ctx.peerData.address)
      updateState(ctx, ctx.peerData.copy(completed = ctx.peerData.completed + piece))

    case Event(BitFieldMsg(BitField(length, bitSet)), ctx: PeerContext) ⇒
      if (length == torrent.pieces) {
        val interesting = bitSet.&~(ctx.ownData.completed).nonEmpty
        if (interesting && !ctx.peerData.interesting) {
          pushMessage(PeerMessage(PeerMessageId.INTERESTED))
        } else if (!interesting && ctx.peerData.interesting) {
          pushMessage(PeerMessage(PeerMessageId.NOT_INTERESTED))
        }
        log.debug("Bit field updated: {}", ctx.peerData.address)
        updateState(ctx, ctx.peerData.copy(completed = bitSet, interesting = interesting))
      } else {
        log.warning("Invalid bit field")
        onError(new IllegalArgumentException("Invalid bit field"))
        stop()
      }

    // Fast extension
    case Event(EmptyMsg(PeerMessageId.HAVE_ALL), ctx: PeerContext) if ctx.peerData.extensions.fast ⇒
      if (!ctx.peerData.interesting) {
        pushMessage(PeerMessage(PeerMessageId.INTERESTED))
      }
      log.debug("Have all: {}", ctx.peerData.address)
      val set = BitSet.newBuilder
      set.sizeHint(torrent.pieces)
      (0 until torrent.pieces).foreach(set += _)
      updateState(ctx, ctx.peerData.copy(completed = set.result(), interesting = true))

    case Event(EmptyMsg(PeerMessageId.HAVE_NONE), ctx: PeerContext) if ctx.peerData.extensions.fast ⇒
      log.debug("Have none: {}", ctx.peerData.address)
      if (ctx.peerData.interesting) {
        pushMessage(PeerMessage(PeerMessageId.NOT_INTERESTED))
      }
      updateState(ctx, ctx.peerData.copy(interesting = false))

    case Event(SuggestMsg(PieceIndex(piece)), ctx: PeerContext) if ctx.peerData.extensions.fast ⇒
      log.debug("Suggested piece: {}", piece)
      stay()

    case Event(AllowedMsg(PieceIndex(piece)), ctx: PeerContext) if ctx.peerData.extensions.fast ⇒
      log.debug("Allowed piece: {}", piece)
      stay()
  }

  when(Idle, 10 minutes) {
    val pf: StateFunction = {
      case Event(PeerHandshake(protocol, infoHash, peerId, extensions), HandshakeContext(address, ownData)) ⇒
        if (infoHash != ownData.infoHash) {
          onError(new IOException("Invalid info hash"))
          stop()
        } else {
          log.info("Peer handshake finished: {} (id = {}, extensions = {})", address, peerId.utf8String, extensions)
          val newPeerData = PeerData(address, peerId, infoHash, extensions)
          peerDispatcher ! PeerConnected(newPeerData)
          if (extensions.fast && ownData.completed.isEmpty) {
            pushMessage(PeerMessage(PeerMessageId.HAVE_NONE))
          } else {
            pushMessage(PeerMessage(PeerMessageId.BITFIELD, BitField(torrent.pieces, ownData.completed)))
          }
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

  when(Downloading, 5 seconds) {
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
          pushMessage(PeerMessage(PeerMessageId.REQUEST, request))
          stay() using ctx.copy(queue :+ QueuedDownload(pipelined = true, index, offset, length, sender(), System.currentTimeMillis()))
        } else {
          stay() using ctx.copy(queue :+ QueuedDownload(pipelined = false, index, offset, length, sender(), 0))
        }

      case Event(PieceMsg(block @ PieceBlock(index, offset, data)), ctx @ PeerContext(queue, _, ownData, peerData)) if block.index == index && block.offset == offset ⇒
        val (drop, keep) = queue.partition(q ⇒ q.index == index && q.offset == offset && q.length == data.length)
        if (drop.nonEmpty) {
          log.debug("Block received: {}/{}/{}", index, offset, data.length)
          drop.foreach {
            case QueuedDownload(_, _, _, _, handler, _) ⇒
              handler ! DownloadedBlock(index, offset, data)
          }
          val newData = peerData.copy(latency = System.currentTimeMillis() - drop.head.time)
          peerDispatcher ! PeerStateChanged(newData)
          download(ctx.copy(peerData = newData), keep)
        } else {
          stay()
        }

      case Event(RejectMsg(PieceBlockRequest(index, offset, length)), ctx @ PeerContext(queue, _, ownData, peerData)) if peerData.extensions.fast ⇒
        log.debug("Rejected: {}/{}/{}", index, offset, length)
        val (drop, keep) = queue.partition(q ⇒ q.index == index && q.offset == offset && q.length == length)
        if (drop.nonEmpty) {
          drop.foreach(_.handler ! BlockDownloadFailed(index, offset, length))
          val newData = peerData.copy(latency = System.currentTimeMillis() - drop.head.time)
          peerDispatcher ! PeerStateChanged(newData)
          download(ctx.copy(peerData = newData), keep)
        } else {
          stay()
        }

      case Event(CancelBlockDownload(index, offset, length), ctx @ PeerContext(queue, _, ownData, peerData)) ⇒
        val (drop, keep) = queue.partition(q ⇒ q.index == index && q.offset == offset && q.length == length)
        drop.foreach(_.handler ! BlockDownloadFailed(index, offset, length))
        if (drop.exists(_.pipelined)) {
          cancelDownload(ctx, drop.head)
        }
        download(ctx, keep)

      case Event(EmptyMsg(PeerMessageId.CHOKE), ctx @ PeerContext(queue, _, ownData, peerData)) ⇒
        queue.foreach {
          case dl @ QueuedDownload(pipelined, index, offset, length, handler, _) ⇒
            if (pipelined) {
              cancelDownload(ctx, dl)
            }
            log.debug("Block download failed: peer choked ({}/{}/{})", index, offset, length)
            handler ! BlockDownloadFailed(index, offset, length)
        }
        val data = peerData.copy(chokedBy = true)
        peerDispatcher ! PeerStateChanged(data)
        goto(Idle) using ctx.copy(downloadQueue = Nil)

      case Event(StateTimeout, ctx @ PeerContext(queue, _, ownData, peerData)) ⇒
        if (queue.nonEmpty) {
          queue.foreach {
            case dl @ QueuedDownload(pipelined, index, offset, length, handler, _) ⇒
              if (pipelined) {
                cancelDownload(ctx, dl)
              }
              log.debug("Block download failed: read timeout ({}/{}/{})", index, offset, length)
              handler ! BlockDownloadFailed(index, offset, length)
          }
          peerDispatcher ! PeerDisconnected(peerData)
          onComplete()
          stop()
        } else {
          log.warning("Connection in downloading state with empty queue: {}", self)
          goto(Idle)
        }

      case Event(Cancel, PeerContext(queue, _, ownData, peerData)) ⇒
        queue.foreach {
          case QueuedDownload(pipelined, index, offset, length, handler, _) ⇒
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
    case Event(PieceMsg(block), ctx) ⇒
      log.debug("Unhandled block: {}; Context: {}", block, ctx)
      stay()

    case Event(msg, ctx) ⇒
      log.debug("Unhandled message: {}; Context: {}", msg, ctx)
      stay()

    case _ ⇒
      stay()
  }

  private def pushBuffer(): Unit = {
    if (totalDemand > 0) {
      val (send, keep) = messageBuffer.splitAt(totalDemand.toInt)
      send.foreach(msg ⇒ onNext(msg))
      messageBuffer = keep
    }
  }

  private def pushMessage[T <: TopLevelMessage](message: T)(implicit ev: TcpMessageWriter[T]): Unit = {
    require(messageBuffer.length < 200, "TCP buffer overflow")
    messageBuffer :+= ev.toBytes(message)
    pushBuffer()
  }

  private def updateState(ctx: PeerContext, newPeerData: PeerData): State = {
    peerDispatcher ! PeerStateChanged(newPeerData)
    stay() using ctx.copy(peerData = newPeerData)
  }

  private def cancelDownload(ctx: PeerContext, download: QueuedDownload): Unit = {
    val (drop, keep) = messageBuffer.partition {
      case Msg(RequestMsg(PieceBlockRequest(index, offset, length))) if index == download.index && offset == download.offset && length == download.length ⇒
        true

      case _ ⇒
        false
    }
    if (drop.nonEmpty) {
      messageBuffer = keep
    } else {
      pushMessage(PeerMessage(PeerMessageId.CANCEL, PieceBlockRequest(download.index, download.offset, download.length)))
    }
  }

  private def download(ctx: PeerContext, queue: List[QueuedDownload]): State = {
    val newQueue: List[QueuedDownload] = {
      val (pipeline, rest) = queue.splitAt(downloadQueueLimit)
      pipeline.filterNot(_.pipelined).foreach {
        case QueuedDownload(_, index, offset, length, _, _) ⇒
          pushMessage(PeerMessage(PeerMessageId.REQUEST, PieceBlockRequest(index, offset, length)))
      }
      pipeline.map(_.copy(pipelined = true, time = System.currentTimeMillis())) ++ rest
    }
    if (newQueue.nonEmpty) {
      stay() using ctx.copy(newQueue)
    } else {
      goto(Idle) using ctx.copy(Nil)
    }
  }

  @tailrec
  private def upload(ctx: PeerContext, queue: List[QueuedUpload]): State = queue match {
    case QueuedUpload(index, offset, length, data) :: rest if data.nonEmpty ⇒
      pushMessage(PeerMessage(PeerMessageId.PIECE, PieceBlock(index, offset, data)))
      upload(ctx, rest)

    case _ ⇒
      if (ctx.peerData.choking && queue.length < uploadQueueLimit) {
        log.info("Unchoking peer: {}", ctx.peerData.address)
        pushMessage(PeerMessage(PeerMessageId.UNCHOKE))
        val data = ctx.peerData.copy(choking = false)
        peerDispatcher ! PeerStateChanged(data)
        stay() using ctx.copy(uploadQueue = queue, peerData = data)
      } else {
        stay() using ctx.copy(uploadQueue = queue)
      }
  }
}

object PeerConnection {
  def framing: Flow[ByteString, TopLevelMessage, Unit] = {
    val messageBufferSize: Int = 131072
    Flow[ByteString]
      .transform(() ⇒ new PeerConnectionStage(messageBufferSize))
  }

  def props(peerDispatcher: ActorRef, torrent: Torrent, peerAddress: InetSocketAddress, initData: SeedData): Props = {
    Props(classOf[PeerConnection], peerDispatcher, torrent, peerAddress, initData)
  }
}