package com.karasiq.bittorrent.dispatcher

import akka.actor.{ActorContext, ActorRef}
import com.karasiq.bittorrent.protocol.PeerMessages._

private[dispatcher] class PeerDownloadQueue(queueSize: Int)(implicit self: ActorRef) {
  assert(queueSize > 0, "Invalid download queue size")

  case class QueuedRequest(handler: Option[ActorRef], request: PieceBlockRequest, time: Long = 0)

  case class PeerDownloadRate(time: Long, downloaded: Long) {
    private val last = if (downloaded == 0) 0 else System.currentTimeMillis()

    def updatedAgo: Long = System.currentTimeMillis() - last

    def update(startTime: Long, bytes: Long): PeerDownloadRate = {
      val currentTime = System.currentTimeMillis()
      PeerDownloadRate(1000 + (currentTime - startTime), (downloaded * 1000 / (time + 1)) + bytes)
    }

    def rate: Long = {
      downloaded * 1000L / (time + 1)
    }
  }

  private var demand: Map[ActorRef, Seq[QueuedRequest]] = Map.empty.withDefaultValue(Vector.empty)

  private var queue: Seq[QueuedRequest] = Vector.empty

  private var downloadRate: Map[ActorRef, PeerDownloadRate] = Map.empty.withDefaultValue(PeerDownloadRate(0, 0))

  private def download(qr: QueuedRequest)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    val available = dsp.peers.filter(pd ⇒ pd._2.completed(qr.request.index) && !pd._2.chokedBy).toSeq
    val fastest = available.sortBy(pd ⇒ downloadRate(pd._1).rate)(Ordering[Long].reverse)

    val currentPeer = fastest.find(pd ⇒ downloadRate(pd._1).downloaded > 0 && demand(pd._1).length < queueSize)
    /* currentPeer.foreach {
      case (p, _) ⇒
        println(s"Peer: $p (demand: ${demand(p).length}, rate: ${downloadRate(p).rate})")
    } */
    val peers = currentPeer.toVector ++ available.filter(pd ⇒ !currentPeer.contains(pd) && downloadRate(pd._1).updatedAgo > 60000 && demand(pd._1).length < 5)
    if (peers.nonEmpty) {
      peers.foreach {
        case (peer, data) ⇒
          demand += peer → (demand(peer) :+ qr.copy(time = System.currentTimeMillis()))
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
      case (peer, requests) ⇒
        val (drop, keep) = requests.partition(qr ⇒ qr.request.index == block.index && qr.request.offset == block.offset && qr.request.length == block.data.length)
        drop.headOption.foreach { qr ⇒
          val rate = downloadRate(peer).update(qr.time, block.data.length)
          // println("Rate: " + rate + " " + rate.rate)
          downloadRate += peer → rate
        }
        drop.flatMap(_.handler).foreach(_ ! block)
        if (peer == successPeer) {
          peer → keep
        } else {
          peer → (drop.map(_.copy(handler = None)) ++ keep)
        }
    }.filter(_._2.nonEmpty).withDefaultValue(Vector.empty)
    retryQueued()
  }

  def failure(fd: BlockDownloadFailed)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    def predicate(qr: QueuedRequest): Boolean = {
      qr.request.index == fd.index && qr.request.offset == fd.offset && qr.request.length == fd.length
    }
    val peer = ctx.sender()
    val (drop, keep) = demand(peer).partition(predicate)
    if (keep.isEmpty) {
      demand -= peer
    } else {
      demand += peer → keep
    }
    if (!demand.valuesIterator.flatten.exists(predicate)) {
      queue ++= drop
    }
    retryQueued()(ctx, new PeerDispatcherContext(dsp.peers - peer))
  }

  def cancel(cancel: CancelBlockDownload)(implicit ctx: ActorContext, dsp: PeerDispatcherContext): Unit = {
    def predicate(qr: QueuedRequest): Boolean = qr.handler.contains(ctx.sender()) && qr.request.index == cancel.index && qr.request.offset == cancel.offset && qr.request.length == cancel.length
    demand = demand.map {
      case (peer, requests) ⇒
        val (drop, keep) = requests.partition(predicate)
        if (drop.nonEmpty) {
          peer ! cancel
        }
        peer → keep
    }.withDefaultValue(Vector.empty)
    queue = queue.filterNot(predicate)
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
      val requests = demand(peer)
      demand -= peer
      queue ++= requests
      retryQueued()
    }
  }
}
