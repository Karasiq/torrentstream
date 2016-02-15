package com.karasiq.bittorrent.dispatcher

import java.net.InetSocketAddress

import akka.actor._
import akka.stream.FlowShape
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.{Tcp, _}
import akka.util.{ByteString, Timeout}
import com.karasiq.bittorrent.announce.{HttpTracker, TrackerError, TrackerRequest, TrackerResponse}
import com.karasiq.bittorrent.format.Torrent
import com.karasiq.bittorrent.protocol.PeerMessages.{PeerHandshake, PieceBlockRequest}
import com.karasiq.bittorrent.protocol.{PeerMessages, PeerStreamEncryption}

import scala.collection.{BitSet, mutable}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}

sealed trait PeerDispatcherCommand
case class ConnectPeer(address: InetSocketAddress) extends PeerDispatcherCommand
case object RequestDispatcherData extends PeerDispatcherCommand
case class DispatcherData(data: SeedData)
case class UpdateBitField(completed: BitSet) extends PeerDispatcherCommand

private[dispatcher] final class PeerDispatcherContext(val peers: Map[ActorRef, PeerData]) extends AnyVal



object PeerDispatcher {
  def props(torrent: Torrent): Props = {
    Props(classOf[PeerDispatcher], torrent)
  }
}

class PeerDispatcher(torrent: Torrent) extends Actor with ActorLogging with Stash with ImplicitMaterializer {
  import context.{dispatcher, system}
  private val config = context.system.settings.config.getConfig("karasiq.torrentstream.peer-dispatcher")
  private val queueSize = config.getInt("download-queue-size")
  private val maxPeers = config.getInt("max-peers")
  private val bufferSize = config.getInt("buffer-size")
  private val ownAddress = new InetSocketAddress(config.getString("listen-host"), config.getInt("listen-port"))
  private val peerId: ByteString = {
    val prefix = ByteString(config.getString("peer-id-prefix"))
    require(prefix.length < 20, "Invalid peer id prefix")
    val charset = "abcdefghijklmnopqrstuvwxyz" + "1234567890"
    prefix ++ Array.fill(20 - prefix.length)(charset(Random.nextInt(charset.length)).toByte)
  }

  private val connectionRequests = mutable.Set.empty[InetSocketAddress]
  private val announcer = context.actorOf(Props[HttpTracker])
  private var ownData = SeedData(peerId, torrent.infoHash)
  private var pieces = Vector.empty[DownloadedPiece]
  private var peers = Map.empty[ActorRef, PeerData]

  private val queue = new PeerDownloadQueue(queueSize)
  private implicit def dispatcherCtx: PeerDispatcherContext = {
    new PeerDispatcherContext(peers)
  }

  def scheduleIdleStop(): Cancellable = {
    context.system.scheduler.scheduleOnce(5 minutes, self, PoisonPill)
  }

  private var idleSchedule = scheduleIdleStop()

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    (torrent.announceList.flatten :+ torrent.announce).distinct
      .foreach(url ⇒ self ! TrackerRequest(url, torrent.infoHash, peerId, ownAddress.getPort, 0, 0, 0))
  }

  override def receive: Receive = {
    case request: TrackerRequest ⇒
      val self = context.self
      import akka.pattern.ask
      import context.system
      implicit val timeout = Timeout(30 seconds)
      log.info("Announce request: {}", request.announce)
      (announcer ? request).onComplete {
        case Success(TrackerError(error)) ⇒
          log.error("Tracker error: {}", error)
          system.scheduler.scheduleOnce(1 minute, self, request)

        case Success(TrackerResponse(_, interval, minInterval, trackerId, _, _, peerList)) ⇒
          val next = minInterval.getOrElse(interval).seconds
          log.info("{} peers received from tracker: {}, next request in {}", peerList.length, request.announce, next)
          system.scheduler.scheduleOnce(next, self, request.copy(trackerId = trackerId))
          peerList.foreach(peer ⇒ self ! ConnectPeer(peer.address))

        case _ ⇒
          system.scheduler.scheduleOnce(5 minutes, self, request)
      }

    case piece @ DownloadedPiece(index, data) ⇒
      val completed = if (pieces.length > bufferSize) {
        val (drop, keep) = pieces.splitAt(1)
        log.debug("Piece unbuffered: #{}", drop.head.pieceIndex)
        pieces = keep :+ piece
        ownData.completed + index -- drop.map(_.pieceIndex)
      } else {
        pieces :+= piece
        ownData.completed + index
      }
      this.ownData = ownData.copy(completed = completed)
      log.debug("Piece buffered: #{}", index)
      peers.keys.foreach(_ ! UpdateBitField(completed))

    case request @ PieceBlockRequest(index, offset, length) ⇒
      idleSchedule.cancel()
      idleSchedule = scheduleIdleStop()
      pieces.find(p ⇒ p.pieceIndex == index && p.data.length >= (offset + length)) match {
        case Some(DownloadedPiece(`index`, data)) ⇒
          val block = DownloadedBlock(index, offset, data.slice(offset, offset + length))
          require(block.data.length == length)
          sender() ! block

        case _ ⇒
          log.debug("Downloading block: {}/{}/{}", index, offset, length)
          queue.download(request)
      }

    case c: CancelBlockDownload ⇒
      queue.cancel(c)

    case block: DownloadedBlock ⇒
      queue.success(block)

    case fd: BlockDownloadFailed ⇒
      queue.failure(fd)

    case RequestDispatcherData ⇒
      sender() ! DispatcherData(ownData)

    case connect @ ConnectPeer(address) if !connectionRequests.contains(address) && peers.size < maxPeers ⇒
      connectionRequests += address
      log.info("Connecting to: {}", address)
      Tcp().outgoingConnection(address)
        .alsoTo(Sink.onComplete {
          case Success(_) ⇒
            // Pass

          case Failure(_) ⇒
            // Retry without encryption
            Tcp().outgoingConnection(address)
              .initialTimeout(10 seconds)
              .join(plainConnection(address))
              .run()
        })
        .initialTimeout(10 seconds)
        .join(encryptedConnection(address)).run()

    case PeerConnected(peerData) ⇒
      peers += sender() → peerData
      queue.retryQueued()

    case PeerStateChanged(peerData) ⇒
      peers += sender() → peerData
      queue.retryQueued()

    case PeerDisconnected(peerData) ⇒
      // context.system.scheduler.scheduleOnce(5 seconds, self, ConnectPeer(peerData.address, ownData))
      val peer = sender()
      peers -= peer
      context.unwatch(peer)
      queue.removePeer(peer)

    case Terminated(peer) ⇒
      peers -= peer
      queue.removePeer(peer)
  }

  private def encryptedConnection(address: InetSocketAddress): Flow[ByteString, ByteString, Unit] = {
    val messageProcessor = context.actorOf(PeerConnection.props(self, torrent, address, ownData))
    Flow.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._
      val messages = b.add(
        Flow[ByteString]
          .via(PeerConnection.framing)
          .to(Sink.foreach(messageProcessor ! _))
      )
      val output = b.add(
        Source.single[ByteString](PeerHandshake("BitTorrent protocol", torrent.infoHash, peerId))
          .concat(Source.fromPublisher(ActorPublisher[ByteString](messageProcessor)))
          .keepAlive[ByteString](30 seconds, () ⇒ PeerMessages.KeepAlive)
      )
      val encryption = b.add(new PeerStreamEncryption(ownData.infoHash)(log))
      encryption.out1 ~> messages.in
      output.out ~> encryption.in2
      FlowShape(encryption.in1, encryption.out2)
    })
  }

  private def plainConnection(address: InetSocketAddress): Flow[ByteString, ByteString, Unit] = {
    val messageProcessor = context.actorOf(PeerConnection.props(self, torrent, address, ownData))
    Flow.fromGraph(GraphDSL.create() { implicit b ⇒
      val messages = b.add(
        Flow[ByteString]
          .via(PeerConnection.framing)
          .to(Sink.foreach(messageProcessor ! _))
      )
      val output = b.add(
        Source.single[ByteString](PeerHandshake("BitTorrent protocol", torrent.infoHash, peerId))
          .concat(Source.fromPublisher(ActorPublisher[ByteString](messageProcessor)))
          .keepAlive[ByteString](30 seconds, () ⇒ PeerMessages.KeepAlive)
      )
      FlowShape(messages.in, output.out)
    })
  }
}
