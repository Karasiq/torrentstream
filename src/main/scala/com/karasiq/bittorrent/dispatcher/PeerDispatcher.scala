package com.karasiq.bittorrent.dispatcher

import java.net.{InetAddress, InetSocketAddress}

import akka.actor._
import akka.io.Tcp.Close
import akka.stream.scaladsl._
import akka.util.{ByteString, Timeout}
import com.karasiq.bittorrent.announce.{HttpTracker, TrackerError, TrackerRequest, TrackerResponse}
import com.karasiq.bittorrent.dispatcher.PeerProtocol.PieceBlockRequest
import com.karasiq.bittorrent.format.TorrentMetadata

import scala.collection.{BitSet, mutable}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

case class RequestPeers(piece: Int, count: Int)
case class PeerList(peers: Seq[ActorRef])
case class BanPeer(peer: ActorRef)
case class UpdateBitField(completed: BitSet)

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

  private var pieces = Vector.empty[DownloadedPiece]

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    (torrent.announceList.flatten :+ torrent.announce).distinct
      .foreach(url ⇒ self ! TrackerRequest(url, torrent.infoHash, peerId, 8902, 0, 0, 0, numWant = 200))
  }

  override def receive: Receive = {
    case request: TrackerRequest ⇒
      import akka.pattern.ask
      import context.system
      implicit val timeout = Timeout(30 seconds)
      log.info("Announce request: {}", request.announce)
      (announcer ? request).foreach {
        case TrackerError(error) ⇒
          log.error("Tracker error: {}", error)
          system.scheduler.scheduleOnce(1 minute, self, request)

        case TrackerResponse(_, interval, minInterval, trackerId, _, _, peerList) ⇒
          val next = 2.minutes // minInterval.getOrElse(interval).seconds
          log.info("{} peers received from tracker: {}, next request in {}", peerList.length, request.announce, next)
          system.scheduler.scheduleOnce(next, self, request.copy(trackerId = trackerId))
          peerList.foreach(peer ⇒ self ! ConnectPeer(peer.address, null))
      }

    case request @ RequestPeers(index, count) if (0 until torrent.pieces).contains(index) ⇒
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
          self.tell(request, sender)
        }
      }

    case BanPeer(peer) if peers.contains(peer) ⇒
      val address = peers(peer).address
      banned += address
      peers -= peer
      log.warning("Peer banned: {}", address)
      peer ! Close

    case piece @ DownloadedPiece(index, data) ⇒
      val completed = if (pieces.length > 100) {
        val (drop, keep) = pieces.splitAt(1)
        pieces = keep :+ piece
        ownData.completed + index -- drop.map(_.pieceIndex)
      } else {
        pieces :+= piece
        ownData.completed + index
      }
      this.ownData = ownData.copy(completed = completed)
      log.info("Piece buffered: #{}", index)
      peers.keys.foreach(_ ! UpdateBitField(completed))

    case PieceBlockRequest(index, offset, length) ⇒
      pieces.find(p ⇒ p.pieceIndex == index && p.data.length >= (offset + length)) match {
        case Some(DownloadedPiece(`index`, data)) ⇒
          log.info("Block uploaded: {}/{}/{}", index, offset, length)
          val block = DownloadedBlock(index, offset, data.slice(offset, offset + length))
          require(block.data.length == length)
          sender() ! block

        case _ ⇒
          log.warning("Missing block request: {}/{}/{}", index, offset, length)
          sender() ! BlockDownloadFailed(index, offset, length)
      }

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
