package com.karasiq.bittorrent.dispatcher

import java.security.MessageDigest

import akka.actor._
import akka.pattern.ask
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import akka.stream.scaladsl._
import akka.util.{ByteString, Timeout}
import com.karasiq.bittorrent.format.{TorrentMetadata, TorrentPiece}
import org.apache.commons.codec.binary.Hex

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

case class RequestPeers(piece: Int)
case class PeerList(peers: Seq[ActorRef])

class PeerBlockPublisher(request: PieceBlockDownloadRequest, peerDispatcher: ActorRef) extends Actor with ActorPublisher[DownloadedBlock] with ActorLogging {
  import context.dispatcher
  private var block: Option[DownloadedBlock] = None

  val schedule = context.system.scheduler.schedule(0 seconds, 15 seconds, self, request)

  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    schedule.cancel()
    super.postStop()
  }

  private def requestPeers(f: Seq[ActorRef] ⇒ Unit): Unit = {
    import context.dispatcher
    implicit val timeout = Timeout(1 minute)
    (peerDispatcher ? RequestPeers(request.index)).mapTo[PeerList].foreach {
      case PeerList(peers) ⇒
        f(peers)
    }
  }

  private def publish(): Unit = {
    if (totalDemand > 0) {
      onNext(block.get)
      onCompleteThenStop()
    }
  }

  private def cancel(): Unit = {
    requestPeers { peers ⇒
      peers.foreach(_ ! CancelPieceBlockDownload(request.index, request.block.offset.toInt, request.block.size.toInt))
    }
  }

  override def receive: Receive = {
    case request: PieceBlockDownloadRequest if block.isEmpty ⇒
      requestPeers { peers ⇒
        log.debug("Requesting block: {}", request.block)
        Random.shuffle(peers).head ! request
      }

    case Request(_) ⇒
      if (block.isDefined) {
        publish()
      } else {
        self ! request
      }

    case Cancel ⇒
      cancel()
      context.stop(self)

    case Status.Failure(_) if this.block.isEmpty ⇒
      self ! request

    case Status.Success(data: DownloadedBlock) if this.block.isEmpty ⇒
      log.info("Block published: {}", this.request.block)
      cancel()
      this.block = Some(data)
      publish()
  }
}

class PeerPiecePublisher(request: PieceDownloadRequest, index: Int, info: TorrentPiece, peerDispatcher: ActorRef) extends Actor with ActorLogging with ActorPublisher[DownloadedPiece] with ImplicitMaterializer {
  import context.dispatcher
  private var piece: Option[DownloadedPiece] = None

  private def checkHash(data: ByteString, hash: ByteString): Boolean = {
    val md = MessageDigest.getInstance("SHA-1")
    ByteString(md.digest(data.toArray)) == hash
  }

  private def publish(): Unit = {
    if (totalDemand > 0) {
      onNext(piece.get)
      onCompleteThenStop()
    }
  }

  val schedule = context.system.scheduler.schedule(0 seconds, 30 seconds, self, request)

  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    schedule.cancel()
    super.postStop()
  }

  //noinspection VariablePatternShadow
  override def receive: Receive = {
    case PieceDownloadRequest(index, piece) if this.piece.isEmpty ⇒
      val self = context.self
      val blockSize = math.pow(2, 14).toInt
      val blocks = TorrentPiece.blocks(piece, blockSize).toVector
      Source(blocks)
        .completionTimeout(1 minute)
        .flatMapConcat(block ⇒ Source.actorPublisher[DownloadedBlock](Props(classOf[PeerBlockPublisher], PieceBlockDownloadRequest(index, block), peerDispatcher)))
        .fold(ByteString.empty)((bs, block) ⇒ bs ++ block.data)
        .runWith(Sink.foreach(data ⇒ self ! DownloadedPiece(index, data)))

    case piece @ DownloadedPiece(index, data) ⇒
      if (checkHash(data, info.sha1)) {
        this.piece = Some(piece)
        publish()
      } else {
        // Retry
        log.warning(s"Invalid piece: #$index")
        self ! request
      }

    case Request(_) ⇒
      if (piece.isDefined) {
        publish()
      } else {
        self ! request
      }

    case Cancel ⇒
      context.stop(self)
  }
}

class PeerDispatcher(torrent: TorrentMetadata) extends Actor with ActorLogging with Stash with ImplicitMaterializer {
  import context.dispatcher
  private val peers = mutable.Map.empty[ActorRef, PeerData]

  override def receive: Receive = {
    case r @ RequestPeers(index) ⇒
      val result = peers.collect {
        case (peer, data) if data.completed(index) && !data.chokedBy ⇒
          peer
      }.toSeq
      if (result.nonEmpty) {
        sender() ! PeerList(result)
      } else {
        val self = context.self
        val sender = context.sender()
        context.system.scheduler.scheduleOnce(10 seconds) {
          self.tell(r, sender)
        }
      }

    case request @ PieceDownloadRequest(index, piece) ⇒
      val sender = context.sender()
      if (log.isInfoEnabled) {
        log.info("Piece download request: {} with hash {}", index, Hex.encodeHexString(piece.sha1.toArray))
      }

      Source.actorPublisher[DownloadedPiece](Props(classOf[PeerPiecePublisher], request, index, piece, self))
        .runWith(Sink.foreach(sender ! _))

    case c @ ConnectPeer(address, data) ⇒
      if (!peers.exists(_._2.address == address)) {
        log.info("Connecting to: {}", address)
        context.actorOf(Props(classOf[PeerConnection], torrent)) ! c
      }

    case PeerConnected(peerData) ⇒
      peers += sender() → peerData

    case PeerStateChanged(peerData) ⇒
      peers += sender() → peerData

    case PeerDisconnected(peerData) ⇒
      peers -= sender()
  }
}