package com.karasiq.bittorrent.dispatcher

import java.net.InetSocketAddress

import scala.collection.{mutable, BitSet}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Random, Success}

import akka.actor._
import akka.pattern.ask
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, FlowShape}
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.{Tcp, _}
import akka.util.{ByteString, Timeout}

import com.karasiq.bittorrent.announce.{HttpTracker, TrackerError, TrackerRequest, TrackerResponse}
import com.karasiq.bittorrent.dispatcher.PeerConnection.{PeerConnected, PeerDisconnected, PeerStateChanged}
import com.karasiq.bittorrent.dispatcher.PeerDispatcher._
import com.karasiq.bittorrent.format.Torrent
import com.karasiq.bittorrent.protocol.PeerStreamEncryption
import com.karasiq.bittorrent.protocol.PeerMessages.{PeerHandshake, PieceBlockRequest}

object PeerDispatcher {
  private[this] val PeerIdCharset = ByteString("abcdefghijklmnopqrstuvwxyz" + "1234567890")

  def props(torrent: Torrent): Props = {
    Props(new PeerDispatcher(torrent))
  }

  def generatePeerId(prefix: String): ByteString = {
    val bs = ByteString(prefix).take(20)
    bs ++ Array.fill(20 - bs.length)(PeerIdCharset(Random.nextInt(PeerIdCharset.length)))
  }

  sealed trait PeerDispatcherCommand
  case class ConnectPeer(address: InetSocketAddress) extends PeerDispatcherCommand
  case object RequestDispatcherData extends PeerDispatcherCommand
  case class DispatcherData(data: SeedData)
  case class UpdateBitField(completed: BitSet) extends PeerDispatcherCommand

  private[dispatcher] final class PeerDispatcherContext(val peers: Map[ActorRef, PeerData]) extends AnyVal
}

class PeerDispatcher(torrent: Torrent) extends Actor with ActorLogging with Stash {
  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))
  import context.{dispatcher, system}

  private[this] val announcer = context.actorOf(Props[HttpTracker])

  private[this] object settings {
    private[this] val rootConfig = context.system.settings.config.getConfig("karasiq.bittorrent")

    private[this] val dispatcherConfig = rootConfig.getConfig("peer-dispatcher")
    val blockSize = dispatcherConfig.getInt("block-size")
    val maxPeers = dispatcherConfig.getInt("max-peers")
    val bufferSize = dispatcherConfig.getInt("buffer-size") / torrent.content.pieceSize
    val ownAddress = new InetSocketAddress(dispatcherConfig.getString("listen-host"), dispatcherConfig.getInt("listen-port"))
    val peerId = PeerDispatcher.generatePeerId(dispatcherConfig.getString("peer-id-prefix"))

    private[this] val connectionConfig = rootConfig.getConfig("peer-connection")
    val downloadQueueSize = connectionConfig.getInt("download-queue-size")
  }

  private[this] object state {
    var ownData = SeedData(settings.peerId, torrent.infoHash)
    val connectionRequests = mutable.Set.empty[InetSocketAddress]
    var peers = Map.empty[ActorRef, PeerData]
    var pieces = List.empty[DownloadedPiece]

    val queue = new PeerDownloadQueue(settings.blockSize, settings.downloadQueueSize)
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    schedules.setIdleTimeout()
    (torrent.announceList.flatten :+ torrent.announce).distinct
      .filter(url ⇒ url.startsWith("http://") || url.startsWith("https://"))
      .foreach(url ⇒ self ! TrackerRequest(url, torrent.infoHash, settings.peerId, settings.ownAddress.getPort, 0, 0, 0))
  }

  override def receive: Receive = {
    case trackerRequest: TrackerRequest ⇒
      implicit val timeout = Timeout(30 seconds)
      val self = context.self
      val scheduler = context.system.scheduler

      log.info("Announce request: {}", trackerRequest.announce)

      (announcer ? trackerRequest).onComplete {
        case Success(TrackerError(error)) ⇒
          log.error("Tracker error: {}", error)
          scheduler.scheduleOnce(30 seconds, self, trackerRequest)

        case Success(TrackerResponse(_, interval, minInterval, trackerId, _, _, peerList)) ⇒
          val next = minInterval.getOrElse(interval).seconds
          log.info("{} peers received from tracker: {}, next request in {}", peerList.length, trackerRequest.announce, next)
          scheduler.scheduleOnce(next, self, trackerRequest.copy(trackerId = trackerId))
          peerList.foreach(peer ⇒ self ! ConnectPeer(peer.address))

        case _ ⇒
          scheduler.scheduleOnce(30 seconds, self, trackerRequest)
      }

    case piece @ DownloadedPiece(pieceIndex, _) ⇒
      val newCompleted = if (state.pieces.length > settings.bufferSize) {
        val (drop, keep) = state.pieces.splitAt(1)
        log.debug("Piece unbuffered: #{}", drop.head.pieceIndex)
        state.pieces = keep :+ piece
        state.ownData.completed + pieceIndex -- drop.map(_.pieceIndex)
      } else {
        state.pieces :+= piece
        state.ownData.completed + pieceIndex
      }
      state.ownData = state.ownData.copy(completed = newCompleted)
      log.debug("Piece buffered: #{}", pieceIndex)
      state.peers.keys.foreach(_ ! UpdateBitField(newCompleted))

    case request @ PieceBlockRequest(index, offset, length) ⇒
      schedules.resetIdleTimeout()

      def isMatchingPiece(piece: DownloadedPiece): Boolean = {
        piece.pieceIndex == index && piece.data.length >= (offset + length)
      }

      state.pieces.find(isMatchingPiece) match {
        case Some(DownloadedPiece(`index`, data)) ⇒
          val block = DownloadedBlock(index, offset, data.slice(offset, offset + length))
          require(block.data.length == length)
          sender() ! block

        case _ ⇒
          log.debug("Downloading block: {}/{}/{}", index, offset, length)
          state.queue.download(request)
      }

    case cancelledBlock: CancelBlockDownload ⇒
      state.queue.cancel(cancelledBlock)

    case block: DownloadedBlock ⇒
      state.queue.success(block)

    case failedBlock: BlockDownloadFailed ⇒
      state.queue.failure(failedBlock)

    case RequestDispatcherData ⇒
      sender() ! DispatcherData(state.ownData)

    case ConnectPeer(address) if !state.connectionRequests.contains(address) && state.peers.size < settings.maxPeers ⇒
      state.connectionRequests += address
      log.info("Connecting to: {}", address)
      connections.connectTo(address)

    case PeerConnected(peerData) ⇒
      state.peers += sender() → peerData
      state.queue.retryQueued()

    case PeerStateChanged(peerData) ⇒
      state.peers += sender() → peerData
      state.queue.retryQueued()

    case PeerDisconnected(_) ⇒
      // context.system.scheduler.scheduleOnce(5 seconds, self, ConnectPeer(peerData.address, ownData))
      val peer = sender()
      state.peers -= peer
      context.unwatch(peer)
      state.queue.removePeer(peer)

    case Terminated(peer) ⇒
      state.peers -= peer
      state.queue.removePeer(peer)
  }

  private[this] object schedules {
    private[this] var idleTimeout = 5 minutes
    private[this] var idleSchedule: Option[Cancellable] = None

    def setIdleTimeout(newTimeout: FiniteDuration = idleTimeout): Unit = {
      idleTimeout = newTimeout
      resetIdleTimeout()
    }

    def resetIdleTimeout(): Unit = {
      idleSchedule.foreach(_.cancel())
      idleSchedule = Some(scheduleIdleStop())
    }

    private[this] def scheduleIdleStop(): Cancellable = {
      context.system.scheduler.scheduleOnce(idleTimeout, self, PoisonPill)
    }
  }

  private[this] object connections {
    def connectTo(address: InetSocketAddress): Future[Tcp.OutgoingConnection] = {
      val initialTimeout = 10 seconds
      val idleTimeout = 90 seconds

      Tcp().outgoingConnection(address)
        .alsoTo(Sink.onComplete(_.failed.foreach { _ ⇒
          // Retry without encryption
          Tcp().outgoingConnection(address)
            .initialTimeout(initialTimeout)
            .idleTimeout(idleTimeout)
            .join(plainConnection(address))
            .run()
        }))
        .initialTimeout(initialTimeout)
        .idleTimeout(idleTimeout)
        .join(encryptedConnection(address))
        .run()
    }

    private[this] def encryptedConnection(address: InetSocketAddress): Flow[ByteString, ByteString, akka.NotUsed] = {
      val messageProcessor = context.actorOf(PeerConnection.props(self, torrent, address, state.ownData))
      val graph = GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._
        val messages = b.add(
          Flow[ByteString]
            .via(PeerConnection.framing)
            .to(Sink.foreach(messageProcessor ! _))
        )
        val output = b.add(
          Source.single[ByteString](PeerHandshake("BitTorrent protocol", torrent.infoHash, settings.peerId))
            .concat(Source.fromPublisher(ActorPublisher[ByteString](messageProcessor)))
          // .keepAlive[ByteString](2 minutes, () ⇒ PeerMessages.KeepAlive)
        )
        val encryption = b.add(PeerStreamEncryption(state.ownData.infoHash)(log))
        encryption.out1 ~> messages.in
        output.out ~> encryption.in2
        FlowShape(encryption.in1, encryption.out2)
      }
      Flow.fromGraph(graph).named("encryptedConnection")
    }

    private[this] def plainConnection(address: InetSocketAddress): Flow[ByteString, ByteString, akka.NotUsed] = {
      val messageProcessor = context.actorOf(PeerConnection.props(self, torrent, address, state.ownData))
      val graph = GraphDSL.create() { implicit b ⇒
        val messages = b.add(
          Flow[ByteString]
            .via(PeerConnection.framing)
            .to(Sink.foreach(messageProcessor ! _))
        )
        val output = b.add(
          Source.single[ByteString](PeerHandshake("BitTorrent protocol", torrent.infoHash, settings.peerId))
            .concat(Source.fromPublisher(ActorPublisher[ByteString](messageProcessor)))
          // .keepAlive[ByteString](2 minutes, () ⇒ PeerMessages.KeepAlive)
        )
        FlowShape(messages.in, output.out)
      }
      Flow.fromGraph(graph).named("plainConnection")
    }
  }

  private[this] implicit def implicitDispatcherContext: PeerDispatcherContext = {
    new PeerDispatcherContext(state.peers)
  }
}
