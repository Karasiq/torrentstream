package com.karasiq.bittorrent.dispatcher

import akka.actor.{ActorContext, ActorRef}
import com.karasiq.bittorrent.dispatcher.MessageConversions._
import com.karasiq.bittorrent.protocol.PeerMessages._

private[dispatcher] final class PeerDownloadQueue(blockSize: Int)(implicit self: ActorRef) {
  require(blockSize > 0, "Invalid block size")

  case class QueuedRequest(handler: Option[ActorRef], request: PieceBlockRequest, retry: Boolean = false)

  case class PeerDownloadRate(time: Long, downloaded: Long) {
    val rate: Long = downloaded * 1000 / (time + 1)

    val queueSize: Int = Seq((rate / blockSize / 2).toInt, 1).max

    private val last = if (downloaded == 0) 0 else System.currentTimeMillis()

    def updatedAgo: Long = System.currentTimeMillis() - last

    def update(bytes: Long): PeerDownloadRate = {
      PeerDownloadRate(1000 + updatedAgo, rate + bytes)
    }
  }

  object PeerDownloadRate {
    val empty = PeerDownloadRate(0, 0)
  }

  case class PeerDemand(queue: Seq[QueuedRequest], rate: PeerDownloadRate)

  object PeerDemand {
    val empty = PeerDemand(Vector.empty, PeerDownloadRate.empty)
  }

  private var demand: Map[ActorRef, PeerDemand] = Map.empty.withDefaultValue(PeerDemand.empty)

  private var queue: Seq[QueuedRequest] = Vector.empty

  private def download(qr: QueuedRequest)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    val available = dsp.peers.filter(pd ⇒ pd._2.completed(qr.request.index) && !pd._2.chokedBy).toSeq
    val fastest = available.sortBy(pd ⇒ demand(pd._1).rate.rate)(Ordering[Long].reverse)

    val currentPeer = fastest.find {
      case (peer, _) ⇒
        val PeerDemand(requests, rate) = demand(peer)
        /* if (requests.length < rate.queueSize) {
          println(s"Peer: $peer (demand: ${requests.length}/${rate.queueSize}, rate: ${rate.rate})")
        } */
        requests.length < rate.queueSize
    }

    val peers = currentPeer
    if (peers.nonEmpty) {
      peers.foreach {
        case (peer, data) ⇒
          val pd = demand(peer)
          demand += peer → pd.copy(pd.queue :+ qr.copy(retry = peers.head._1 == peer))
          peer ! qr.request
      }
    } else {
      queue :+= qr
    }
  }

  def download(request: PieceBlockRequest)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    download(QueuedRequest(Some(ctx.sender()), request))
  }

  def success(block: DownloadedBlock)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    val successPeer = ctx.sender()
    var handlers = Set.empty[ActorRef]
    demand = demand.map {
      case (peer, PeerDemand(requests, rate)) ⇒
        val (drop, keep) = requests.partition(_.request.relatedTo(block))
        handlers ++= drop.flatMap(_.handler)
        if (peer == successPeer) {
          peer → PeerDemand(keep, rate.update(block.length))
        } else {
          peer → PeerDemand(drop.map(_.copy(handler = None)) ++ keep, rate)
        }
    }.withDefaultValue(PeerDemand.empty)

    val (drop, keep) = queue.partition(_.request.relatedTo(block))
    handlers ++= drop.flatMap(_.handler)
    queue = keep

    handlers.foreach(_ ! block)
    retryQueued()
  }

  def failure(fd: BlockDownloadFailed)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    val peer = ctx.sender()
    val pd @ PeerDemand(requests, _) = demand(peer)
    val (drop, keep) = requests.partition(_.request.relatedTo(fd))
    demand += peer → pd.copy(keep)
    if (drop.exists(_.retry)) {
      queue ++= drop
      retryQueued()(ctx, new PeerDispatcherContext(dsp.peers - peer))
    }
  }

  def cancel(cancel: CancelBlockDownload)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    def matches(qr: QueuedRequest): Boolean = qr.handler.contains(ctx.sender()) && qr.request.relatedTo(cancel)
    demand = demand.map {
      case (peer, pd @ PeerDemand(requests, _)) ⇒
        val (drop, keep) = requests.partition(matches)
        if (drop.nonEmpty) {
          peer ! cancel
        }
        peer → pd.copy(keep)
    }.withDefaultValue(PeerDemand.empty)
    queue = queue.filterNot(matches)
    retryQueued()
  }

  def retryQueued()(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    val retry = queue
    if (retry.nonEmpty && dsp.peers.nonEmpty) {
      queue = Vector.empty
      retry.foreach(this.download)
    }
  }

  def removePeer(peer: ActorRef)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    if (demand.contains(peer)) {
      val PeerDemand(requests, _) = demand(peer)
      demand -= peer
      queue ++= requests
      retryQueued()
    }
  }
}
