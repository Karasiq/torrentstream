package com.karasiq.bittorrent.dispatcher

import akka.actor.{ActorContext, ActorRef}
import com.karasiq.bittorrent.dispatcher.MessageConversions._
import com.karasiq.bittorrent.protocol.PeerMessages._

private[dispatcher] final class PeerDownloadQueue(blockSize: Int, maxQueueSize: Int)(implicit self: ActorRef) {
  require(blockSize > 0 && maxQueueSize > 0)

  private final case class QueuedRequest(handler: Option[ActorRef], request: PieceBlockRequest)

  private final case class PeerDownloadRate(time: Long, downloaded: Long) {
    val rate: Long = downloaded * 1000 / (time + 1)

    val queueSize: Int = {
      val qs: Int = (rate / blockSize).toInt
      val minQueueSize: Int = 1
      if (qs < minQueueSize) {
        minQueueSize
      } else if (qs < maxQueueSize) {
        qs
      } else {
        maxQueueSize
      }
    }

    private val timestamp = {
      if (downloaded == 0) 0 else System.currentTimeMillis()
    }

    def updatedAgo: Long = {
      System.currentTimeMillis() - timestamp
    }

    def update(bytes: Long): PeerDownloadRate = {
      PeerDownloadRate(1000 + updatedAgo, rate + bytes)
    }
  }

  private object PeerDownloadRate {
    val empty = PeerDownloadRate(0, 0)
  }

  private final case class PeerDemand(queue: Seq[QueuedRequest], rate: PeerDownloadRate)

  private object PeerDemand {
    val empty = PeerDemand(Vector.empty, PeerDownloadRate.empty)
  }

  private var demand: Map[ActorRef, PeerDemand] = Map.empty.withDefaultValue(PeerDemand.empty)

  private var queue: Seq[QueuedRequest] = Vector.empty

  private def download(qr: QueuedRequest)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    val available = dsp.peers.filter(pd ⇒ pd._2.completed(qr.request.index) && !pd._2.chokedBy).toStream.map {
      case (peer, _) ⇒
        peer → demand(peer)
    }

    val currentPeer = available
      .filter(pd ⇒ pd._2.queue.length < pd._2.rate.queueSize)
      .sortBy(_._2.rate.rate)(Ordering[Long].reverse)
      .headOption

    currentPeer match {
      case Some((peer, pd)) ⇒
        // println(s"Peer: $peer ${pd.queue.length}/${pd.rate.queueSize} (rate: ${pd.rate.rate})")
        demand += peer → pd.copy(pd.queue :+ qr)
        peer ! qr.request

      case None ⇒
        queue :+= qr
    }
  }

  def download(request: PieceBlockRequest)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    download(QueuedRequest(Some(ctx.sender()), request))
  }

  def success(block: DownloadedBlock)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    val peer = ctx.sender()
    var handlers = Vector.empty[ActorRef]
    demand(peer) match {
      case PeerDemand(requests, rate) ⇒
        val (drop, keep) = requests.partition(_.request.relatedTo(block))
        handlers ++= drop.flatMap(_.handler)
        demand += peer → PeerDemand(keep, rate.update(block.length))
    }

    queue.partition(_.request.relatedTo(block)) match {
      case (drop, keep) ⇒
        handlers ++= drop.flatMap(_.handler)
        queue = keep
    }

    handlers.foreach(_ ! block)
    retryQueued()
  }

  def failure(fd: BlockDownloadFailed)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    val peer = ctx.sender()
    val pd @ PeerDemand(requests, _) = demand(peer)
    val (drop, keep) = requests.partition(_.request.relatedTo(fd))
    demand += peer → pd.copy(keep)
    queue ++= drop.headOption
    retryQueued()(ctx, new PeerDispatcherContext(dsp.peers - peer))
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
