package com.karasiq.bittorrent.dispatcher

import java.io.IOException
import java.net.InetSocketAddress

import akka.actor.{ActorRef, FSM, Props}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import akka.stream.scaladsl._
import akka.util.ByteString
import com.karasiq.bittorrent.dispatcher.MessageConversions._
import com.karasiq.bittorrent.dispatcher.PeerConnectionContext._
import com.karasiq.bittorrent.dispatcher.PeerConnectionState._
import com.karasiq.bittorrent.format.Torrent
import com.karasiq.bittorrent.protocol.PeerMessages._
import com.karasiq.bittorrent.protocol.extensions.ExtensionProtocol
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

  case class QueuedDownload(pipelined: Boolean, index: Int, offset: Int, length: Int, handler: ActorRef) extends PieceBlockInfo
  case class QueuedUpload(index: Int, offset: Int, override val length: Int, data: ByteString = ByteString.empty) extends PieceBlockData
  case class PeerContext(downloadQueue: List[QueuedDownload], uploadQueue: List[QueuedUpload], ownData: SeedData, peerData: PeerData, epHandshake: Option[EpHandshake] = None) extends PeerConnectionContext
}

// TODO: PEX, DHT, UDP trackers
class PeerConnection(peerDispatcher: ActorRef, torrent: Torrent, peerAddress: InetSocketAddress, initData: SeedData, extMessages: Map[Int, String]) extends FSM[PeerConnectionState, PeerConnectionContext] with ActorPublisher[ByteString] with ImplicitMaterializer with PeerMessageMatcher {
  import context.system

  // Settings
  private val config = system.settings.config.getConfig("karasiq.torrentstream.peer-connection")
  private val updateBitField = config.getBoolean("update-bitfield")
  private var downloadQueueLimit = config.getInt("download-queue-size")
  private val uploadQueueLimit = config.getInt("upload-queue-size")
  private val clientString = config.getString("client-version-string")

  private var messageBuffer = Vector.empty[ByteString]

  startWith(Idle, HandshakeContext(peerAddress, initData))

  def stateMessage: StateFunction = {
    case Event(Request(_), _) ⇒
      pushBuffer()
      stay()

    case Event(RequestMsg(request @ PieceBlockRequest(index, offset, length)), ctx: PeerContext) ⇒
      if (ctx.peerData.choking && !ctx.ownData.completed(index)) {
        if (ctx.peerData.extensions.fast) {
          pushMessage(PeerMessage(PeerMessageId.REJECT_REQUEST, request))
        } else {
          log.warning("Invalid piece request: {}", ctx.peerData.address)
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

    // Extension protocol
    case Event(ExtMsg(msg @ ExtendedMessage(id, payload)), ctx: PeerContext) if ctx.peerData.extensions.extensionProtocol ⇒
      if (id == 0) {
        // Handshake
        val handshake = Msg.payload[EpHandshake](payload)
        log.debug("Extension protocol handshake: {}", handshake.getOrElse("<none>"))
        handshake.flatMap(_.requests).foreach(this.downloadQueueLimit = _)
        stay() using ctx.copy(epHandshake = handshake)
      } else {
        extMessages.get(id) match {
          case Some("ut_pex") ⇒
            val peerList = Msg.payload[PeerExchangeList](payload)
            log.debug("Peer exchange: {}", peerList)
            peerList.toSeq.flatMap(_.addresses).foreach(address ⇒ peerDispatcher ! ConnectPeer(address))
            stay()

          case _ ⇒
            log.warning("Unsupported message: {}", msg)
            stay()
        }
      }
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
          if (extensions.extensionProtocol) {
            val epHandshake = EpHandshake(extMessages, Some(clientString), Some(uploadQueueLimit))
            pushMessage(PeerMessage(PeerMessageId.EXTENDED_MESSAGE, ExtendedMessage(0, epHandshake)))
          }
          stay() using PeerContext(Nil, Nil, ownData, newPeerData)
        }

      case Event(request @ PieceBlockRequest(index, offset, length), ctx: PeerContext) ⇒
        val sender = context.sender()
        if (ctx.peerData.chokedBy || !ctx.peerData.completed(index)) {
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
        log.debug(s"Handshake failed: $address")
        onComplete()
        stop()
    }
    pf.orElse(stateMessage)
  }

  when(Downloading, 5 seconds) {
    val pf: StateFunction = {
      case Event(request @ PieceBlockRequest(index, offset, length), ctx @ PeerContext(queue, _, ownData, _, _)) ⇒
        if (ctx.peerData.chokedBy) {
          log.warning("Block download failed: peer choked ({}/{}/{})", index, offset, length)
          sender() ! BlockDownloadFailed(index, offset, length)
          stay()
        } else if (queue.length < downloadQueueLimit) {
          pushMessage(PeerMessage(PeerMessageId.REQUEST, request))
          stay() using ctx.copy(queue :+ QueuedDownload(pipelined = true, index, offset, length, sender()))
        } else {
//          sender() ! BlockDownloadFailed(index, offset, length)
//          stay()
          stay() using ctx.copy(queue :+ QueuedDownload(pipelined = false, index, offset, length, sender()))
        }

      case Event(PieceMsg(block @ PieceBlock(index, offset, data)), ctx @ PeerContext(queue, _, _, peerData, _)) ⇒
        val (drop, keep) = queue.partition(_.relatedTo(block))
        if (drop.nonEmpty) {
          log.debug("Block received: {}/{}/{}", index, offset, data.length)
          drop.foreach {
            case QueuedDownload(_, _, _, _, handler) ⇒
              handler ! block.downloaded
          }
        }
        download(ctx, keep)

      case Event(RejectMsg(request @ PieceBlockRequest(index, offset, length)), ctx @ PeerContext(queue, _, _, peerData, _)) if peerData.extensions.fast ⇒
        val (drop, keep) = queue.partition(_.relatedTo(request))
        if (drop.nonEmpty) {
          log.debug("Rejected: {}/{}/{}", index, offset, length)
          drop.foreach(_.handler ! BlockDownloadFailed(index, offset, length))
        }
        download(ctx, keep)

      case Event(c @ CancelBlockDownload(index, offset, length), ctx @ PeerContext(queue, _, _, _, _)) ⇒
        val (drop, keep) = queue.partition(_.relatedTo(c))
        // drop.foreach(_.handler ! BlockDownloadFailed(index, offset, length))
        if (drop.exists(_.pipelined)) {
          cancelDownload(ctx, drop.head)
        }
        download(ctx, keep)

      case Event(EmptyMsg(PeerMessageId.CHOKE), ctx @ PeerContext(queue, _, _, peerData, _)) ⇒
        queue.foreach {
          case dl @ QueuedDownload(pipelined, index, offset, length, handler) ⇒
            if (pipelined) {
              cancelDownload(ctx, dl)
            }
            log.debug("Block download failed: peer choked ({}/{}/{})", index, offset, length)
            handler ! BlockDownloadFailed(index, offset, length)
        }
        val data = peerData.copy(chokedBy = true)
        peerDispatcher ! PeerStateChanged(data)
        goto(Idle) using ctx.copy(downloadQueue = Nil)

      case Event(StateTimeout, ctx @ PeerContext(queue, _, _, peerData, _)) ⇒
        if (queue.nonEmpty) {
          queue.foreach {
            case dl @ QueuedDownload(pipelined, index, offset, length, handler) ⇒
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

      case Event(Cancel, PeerContext(queue, _, _, peerData, _)) ⇒
        queue.foreach {
          case dl @ QueuedDownload(_, index, offset, length, handler) ⇒
            handler ! dl.failed
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
    case Event(msg: PeerMessage, ctx) ⇒
      log.debug("Unhandled message: {}; Context: {}", msg, ctx)
      stay()

    case msg ⇒
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
    if (messageBuffer.length >= 1000) {
      messageBuffer = messageBuffer.drop(1) :+ ev.toBytes(message)
    } else {
      messageBuffer :+= ev.toBytes(message)
    }
    pushBuffer()
  }

  private def updateState(ctx: PeerContext, newPeerData: PeerData): State = {
    peerDispatcher ! PeerStateChanged(newPeerData)
    stay() using ctx.copy(peerData = newPeerData)
  }

  private def cancelDownload(ctx: PeerContext, download: QueuedDownload): Unit = {
    val (drop, keep) = messageBuffer.partition {
      case Msg(RequestMsg(request @ PieceBlockRequest(index, offset, length))) if download.relatedTo(request) ⇒
        true

      case _ ⇒
        false
    }
    if (drop.nonEmpty) {
      messageBuffer = keep
    } else {
      pushMessage(PeerMessage(PeerMessageId.CANCEL, download.request))
    }
  }

  private def download(ctx: PeerContext, queue: List[QueuedDownload]): State = {
    val newQueue: List[QueuedDownload] = {
      val (pipeline, rest) = queue.splitAt(downloadQueueLimit)
      pipeline.filterNot(_.pipelined).foreach { dl ⇒
        pushMessage(PeerMessage(PeerMessageId.REQUEST, dl.request))
      }
      pipeline.map(_.copy(pipelined = true)) ++ rest
    }
    if (newQueue.nonEmpty) {
      stay() using ctx.copy(newQueue)
    } else {
      goto(Idle) using ctx.copy(Nil)
    }
  }

  @tailrec
  private def upload(ctx: PeerContext, queue: List[QueuedUpload]): State = queue match {
    case (qu: QueuedUpload) :: rest if qu.data.nonEmpty ⇒
      pushMessage(PeerMessage(PeerMessageId.PIECE, qu.message))
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

  def props(peerDispatcher: ActorRef, torrent: Torrent, peerAddress: InetSocketAddress, initData: SeedData, extMessages: Map[Int, String] = ExtensionProtocol.defaultMessages): Props = {
    Props(classOf[PeerConnection], peerDispatcher, torrent, peerAddress, initData, extMessages)
  }
}