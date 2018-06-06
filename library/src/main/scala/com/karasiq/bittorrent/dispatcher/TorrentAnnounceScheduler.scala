package com.karasiq.bittorrent.dispatcher

import java.net.InetSocketAddress

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Success

import akka.actor.{Actor, ActorLogging, ActorRef, NotInfluenceReceiveTimeout, PossiblyHarmful, Props}
import akka.pattern.ask
import akka.util.{ByteString, Timeout}

import com.karasiq.bittorrent.announce.{TrackerError, TrackerRequest, TrackerResponse}
import com.karasiq.bittorrent.dht.DHTRoutingTable.FindPeers
import com.karasiq.bittorrent.format.Torrent

object TorrentAnnounceScheduler {
  // Messages
  sealed trait Message

  // Internal messages
  private sealed trait InternalMessage extends Message with PossiblyHarmful
  private final object DHTAnnounce extends InternalMessage with NotInfluenceReceiveTimeout

  // Events
  sealed trait Event
  final case class PeersReceived(peers: Seq[InetSocketAddress]) extends Event with NotInfluenceReceiveTimeout

  // Props
  def props(dhtRoutingTable: ActorRef, httpTracker: ActorRef, torrent: Torrent, peerId: ByteString, ownAddress: InetSocketAddress): Props = {
    Props(new TorrentAnnounceScheduler(dhtRoutingTable, httpTracker, torrent, peerId, ownAddress))
  }
}

class TorrentAnnounceScheduler(dhtRoutingTable: ActorRef, httpTracker: ActorRef, torrent: Torrent, peerId: ByteString, ownAddress: InetSocketAddress) extends Actor with ActorLogging {
  import context.dispatcher

  import TorrentAnnounceScheduler._
  private[this] implicit val timeout = Timeout(10 seconds)

  override def receive: Receive = {
    case pr: PeersReceived ⇒
      log.debug("Peers received: {}", pr.peers)
      context.parent ! pr

    case DHTAnnounce ⇒
      dhtRoutingTable ! FindPeers(torrent.infoHash, ownAddress.getPort)

    case FindPeers.Success(peers) ⇒
      self ! PeersReceived(peers)

    case trackerRequest: TrackerRequest ⇒
      implicit val timeout = Timeout(30 seconds)
      val self = context.self
      val scheduler = context.system.scheduler

      log.info("Announce request: {}", trackerRequest.announce)

      (httpTracker ? trackerRequest).onComplete {
        case Success(TrackerError(error)) ⇒
          log.error("Tracker error: {}", error)
          scheduler.scheduleOnce(30 seconds, self, trackerRequest)

        case Success(TrackerResponse(_, interval, minInterval, trackerId, _, _, peerList)) ⇒
          val next = minInterval.getOrElse(interval).seconds
          log.info("{} peers received from tracker: {}, next request in {}", peerList.length, trackerRequest.announce, next)
          scheduler.scheduleOnce(next, self, trackerRequest.copy(trackerId = trackerId))
          self ! PeersReceived(peerList.map(_.address))

        case _ ⇒
          scheduler.scheduleOnce(30 seconds, self, trackerRequest)
      }
  }

  override def preStart(): Unit = {
    super.preStart()
    context.system.scheduler.schedule(0 seconds, 30 seconds, self, DHTAnnounce)
    (torrent.announceList.flatten :+ torrent.announce).distinct
      .filter(url ⇒ url.startsWith("http://") || url.startsWith("https://"))
      .foreach(url ⇒ self ! TrackerRequest(url, torrent.infoHash, peerId, ownAddress.getPort, 0, 0, torrent.size))
  }
}
