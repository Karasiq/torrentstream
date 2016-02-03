package com.karasiq.bittorrent.streams

import akka.actor.{Actor, ActorLogging, ActorRef, Status}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import com.karasiq.bittorrent.dispatcher._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

class PeerBlockPublisher(request: PieceBlockDownloadRequest, peerDispatcher: ActorRef) extends Actor with ActorPublisher[DownloadedBlock] with ActorLogging {
  import context.dispatcher
  private val requests = context.system.settings.config.getInt("karasiq.torrentstream.peer-load-balancer.requests-per-block")
  private var block: Option[DownloadedBlock] = None
  private var peersRequested = Set.empty[ActorRef]

  private def publish(): Unit = {
    if (totalDemand > 0) {
      onNext(block.get)
      onCompleteThenStop()
    }
  }

  private def cancel(): Unit = {
    peersRequested.foreach(_ ! CancelPieceBlockDownload(request.index, request.block.offset.toInt, request.block.size.toInt))
  }

  override def receive: Receive = {
    case request: PieceBlockDownloadRequest if block.isEmpty && peersRequested.size < requests ⇒
      peerDispatcher ! RequestPeers(request.index)

    case PeerList(list) if block.isEmpty && peersRequested.size < requests ⇒
      val peers = list.filterNot(peersRequested.contains)
      log.debug("Requesting block: {}", request.block)
      val peerSelection = Random.shuffle(peers).take(requests / 2)
      peersRequested ++= peerSelection
      peerSelection.foreach(_ ! request)

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
      peersRequested -= sender()
      context.system.scheduler.scheduleOnce(5 seconds, self, request)

    case Status.Success(data: DownloadedBlock) if this.block.isEmpty ⇒
      peersRequested -= sender()
      log.info("Block published: {}", this.request.block)
      cancel()
      this.block = Some(data)
      publish()
  }
}
