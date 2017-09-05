package com.karasiq.bittorrent.dispatcher

import akka.actor.{ActorContext, ActorRef}

import com.karasiq.bittorrent.dispatcher.MessageConversions._
import com.karasiq.bittorrent.dispatcher.PeerDispatcher.PeerDispatcherContext
import com.karasiq.bittorrent.protocol.PeerMessages._

private[dispatcher] final class PeerDownloadQueue(blockSize: Int, maxQueueSize: Int)(implicit self: ActorRef) {
  require(blockSize > 0 && maxQueueSize > 0)

  private[this] var demand: Map[ActorRef, PeerDemand] = Map.empty.withDefaultValue(PeerDemand.empty)
  private[this] var queue: Seq[QueuedRequest] = List.empty

  def download(request: PieceBlockRequest)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    download(QueuedRequest(Some(ctx.sender()), request))
  }

  def success(block: DownloadedBlock)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    val peer = ctx.sender()
    var handlers = List.empty[ActorRef]
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
      queue = List.empty
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

  private[this] def download(qr: QueuedRequest)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    val peers = for {
      (peer, data) <- dsp.peers.iterator if data.completed(qr.request.index) && !data.chokedBy
      pd @ PeerDemand(pq, rate) <- Some(demand(peer)) if pq.length < rate.queueSize
    } yield peer → pd

    if (peers.nonEmpty) {
      val (peer, pd) = peers.maxBy(_._2.rate.rate)
      // println(s"Peer: $peer ${pd.queue.length}/${pd.rate.queueSize} (rate: ${pd.rate.rate})")
      demand += peer → pd.copy(pd.queue :+ qr)
      peer ! qr.request
    } else {
      queue :+= qr
    }
  }

  private[this] final case class QueuedRequest(handler: Option[ActorRef], request: PieceBlockRequest)

  private[this] case class PeerDownloadRate(time: Long, downloaded: Long) {
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
      copy(1000 + updatedAgo, rate + bytes)
    }
  }

  private[this] object PeerDownloadRate {
    val empty = new PeerDownloadRate(0, 0)
  }

  private[this] final case class PeerDemand(queue: Seq[QueuedRequest], rate: PeerDownloadRate)

  private[this] object PeerDemand {
    val empty = new PeerDemand(List.empty, PeerDownloadRate.empty)
  }
}
