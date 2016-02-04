package com.karasiq.bittorrent.dispatcher

import java.net.{InetAddress, InetSocketAddress}

import akka.actor._
import akka.io.Tcp.Close
import akka.stream.scaladsl._
import akka.util.{ByteString, Timeout}
import com.karasiq.bittorrent.announce.{HttpTracker, TrackerError, TrackerRequest, TrackerResponse}
import com.karasiq.bittorrent.format.TorrentMetadata
import com.karasiq.bittorrent.streams.PeerPiecePublisher
import org.apache.commons.codec.binary.Hex

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

case class RequestPeers(piece: Int, count: Int)
case class PeerList(peers: Seq[ActorRef])
case class BanPeer(peer: ActorRef)
case class HasPiece(piece: Int)

class PeerDispatcher(torrent: TorrentMetadata) extends Actor with ActorLogging with Stash with ImplicitMaterializer {
  import context.dispatcher
  private val peers = mutable.Map.empty[ActorRef, PeerData]
  private val demand = mutable.Map.empty[ActorRef, Int].withDefaultValue(0)
  private val banned = mutable.Set.empty[InetSocketAddress]

  private val peerId = ByteString("-TR2840-") ++ {
    val charset = "abcdefghijklmnopqrstuvwxyz" + "1234567890"
    Array.fill(12)(charset(Random.nextInt(charset.length)).toByte)
  }
  private val announcer = context.actorOf(Props[HttpTracker])

  private var ownData = PeerData(new InetSocketAddress(InetAddress.getLocalHost, 8902), peerId, torrent.infoHash)

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    (torrent.announceList.flatten :+ torrent.announce).distinct
      .foreach(url ⇒ self ! TrackerRequest(url, torrent.infoHash, peerId, 8902, 0, 0, 0, numWant = 200))
  }

  override def receive: Receive = {
    case r: TrackerRequest ⇒
      import akka.pattern.ask
      import context.system
      implicit val timeout = Timeout(30 seconds)
      log.info("Announce request: {}", r.announce)
      (announcer ? r).foreach {
        case TrackerError(error) ⇒
          log.error("Tracker error: {}", error)
          system.scheduler.scheduleOnce(1 minute, self, r)

        case TrackerResponse(_, interval, minInterval, trackerId, _, _, peerList) ⇒
          val next = 2.minutes // minInterval.getOrElse(interval).seconds
          log.info("{} peers received from tracker: {}, next request in {}", peerList.length, r.announce, next)
          system.scheduler.scheduleOnce(next, self, r.copy(trackerId = trackerId))
          peerList.foreach(peer ⇒ self ! ConnectPeer(peer.address, null))
      }

    case r @ RequestPeers(index, count) if (0 until torrent.pieces).contains(index) ⇒
      val result = peers.collect {
        case (peer, data) if data.completed(index) && !data.chokedBy ⇒
          peer
      }.toSeq.sortBy(demand).take(count)

      if (result.nonEmpty) {
        result.foreach(peer ⇒ demand += peer → (demand(peer) + 1))
        sender() ! PeerList(result)
      } else {
        val self = context.self
        val sender = context.sender()
        context.system.scheduler.scheduleOnce(5 seconds) {
          // Retry
          self.tell(r, sender)
        }
      }

    case request @ PieceDownloadRequest(index, piece) if (0 until torrent.pieces).contains(index) ⇒
      val sender = context.sender()
      if (log.isInfoEnabled) {
        log.info("Piece download request: {} with hash {}", index, Hex.encodeHexString(piece.sha1.toArray))
      }
      Source.actorPublisher[DownloadedPiece](Props(classOf[PeerPiecePublisher], request, self))
        .completionTimeout(5 minutes)
        .runWith(Sink.foreach(sender ! _))

    case BanPeer(peer) if peers.contains(peer) ⇒
      val address = peers(peer).address
      banned += address
      log.warning("Peer banned: {}", address)
      peer ! Close

    case h @ HasPiece(piece) ⇒
      ownData = ownData.copy(completed = ownData.completed + piece)
      log.info("Has piece: {}", h.piece)
      peers.keys.foreach(_ ! h)

    case c @ ConnectPeer(address, data) if !banned.contains(address) && !peers.exists(_._2.address == address) ⇒
      log.info("Connecting to: {}", address)
      context.actorOf(Props(classOf[PeerConnection], torrent)) ! c.copy(ownData = Option(data).getOrElse(ownData))

    case PeerConnected(peerData) ⇒
      peers += sender() → peerData

    case PeerStateChanged(peerData) ⇒
      peers += sender() → peerData

    case PeerDisconnected(peerData) ⇒
//      context.system.scheduler.scheduleOnce(5 seconds, self, ConnectPeer(peerData.address, ownData))
      demand -= sender()
      peers -= sender()
  }
}
