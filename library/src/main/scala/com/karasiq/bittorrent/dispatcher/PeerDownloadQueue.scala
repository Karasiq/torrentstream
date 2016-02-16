package com.karasiq.bittorrent.dispatcher

import akka.actor.{ActorContext, ActorRef}
import com.karasiq.bittorrent.dispatcher.MessageConversions._
import com.karasiq.bittorrent.protocol.PeerMessages._

private[dispatcher] final class PeerDownloadQueue(blockSize: Int, minQueueLength: Int, maxQueueSize: Int)(implicit self: ActorRef) {
  require(blockSize > 0, "Invalid block size")
  assert(minQueueLength > 0 && maxQueueSize > 0, "Invalid download queue size")

  case class QueuedRequest(handler: Option[ActorRef], request: PieceBlockRequest, time: Long = 0, retry: Boolean = false)

  case class PeerDownloadRate(time: Long, downloaded: Long) {
    val rate: Long = downloaded * 1000 / (time + 1)

    private val last = if (downloaded == 0) 0 else System.currentTimeMillis()

    def updatedAgo: Long = System.currentTimeMillis() - last

    def update(startTime: Long, bytes: Long): PeerDownloadRate = {
      PeerDownloadRate(1000 + (System.currentTimeMillis() - startTime), rate + bytes)
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
        val queueSize = Seq((rate.rate / blockSize + 1).toInt * minQueueLength, maxQueueSize).min
        // println(s"Peer: $peer (demand: ${requests.length}/$queueSize, rate: ${rate.rate})")
        rate.downloaded > 0 && requests.length < queueSize
    }

    val slowest = available.filter {
      case (peer, _) ⇒
        val PeerDemand(requests, rate) = demand(peer)
        !currentPeer.exists(_._1 == peer) && (rate.updatedAgo > 15000 || rate.rate < blockSize) && requests.length < 3
    }

    val peers = currentPeer ++ slowest
    if (peers.nonEmpty) {
      peers.foreach {
        case (peer, data) ⇒
          val pd = demand(peer)
          demand += peer → pd.copy(pd.queue :+ qr.copy(time = System.currentTimeMillis(), retry = peers.head._1 == peer))
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
    demand = demand.map {
      case (peer, PeerDemand(requests, rate)) ⇒
        val (drop, keep) = requests.partition(_.request.relatedTo(block))
        val newRate = drop.headOption.fold(rate)(qr ⇒ rate.update(qr.time, block.data.length))
        if (peer == successPeer) {
          drop.flatMap(_.handler).foreach(_ ! block)
          peer → PeerDemand(keep, newRate)
        } else {
          peer → PeerDemand(drop.map(_.copy(handler = None)) ++ keep, newRate)
        }
    }.filter(_._2.queue.nonEmpty).withDefaultValue(PeerDemand.empty)

    val (drop, keep) = queue.partition(_.request.relatedTo(block))
    drop.flatMap(_.handler).foreach(_ ! block)
    queue = keep
    retryQueued()
  }

  def failure(fd: BlockDownloadFailed)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    val peer = ctx.sender()
    val pd @ PeerDemand(requests, _) = demand(peer)
    val (drop, keep) = requests.partition(_.request.relatedTo(fd))
    if (keep.isEmpty) {
      demand -= peer
    } else {
      demand += peer → pd.copy(keep)
    }
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
    }.filter(_._2.queue.nonEmpty).withDefaultValue(PeerDemand.empty)
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
