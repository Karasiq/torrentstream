package com.karasiq.bittorrent.dispatcher

import akka.actor.{ActorContext, ActorRef}

import com.karasiq.bittorrent.dispatcher.MessageConversions._
import com.karasiq.bittorrent.dispatcher.PeerDispatcher.PeerDispatcherContext
import com.karasiq.bittorrent.protocol.PeerMessages._

private[dispatcher] object PeerDownloadQueue {
  def apply(blockSize: Int, minQueueSize: Int, maxQueueSize: Int, queueSizeFactor: Double)
           (implicit context: ActorContext): PeerDownloadQueue = {
    new PeerDownloadQueue(blockSize, minQueueSize, maxQueueSize, queueSizeFactor)
  }
}

private[dispatcher] final class PeerDownloadQueue(blockSize: Int,
                                                  minQueueSize: Int,
                                                  maxQueueSize: Int,
                                                  queueSizeFactor: Double)(implicit actorContext: ActorContext) {

  require(blockSize > 0 && minQueueSize > 0 && maxQueueSize >= minQueueSize)

  private[this] implicit val implicitSender = actorContext.self
  // private[this] val log = Logging(actorContext.system, implicitSender)
  private[this] var demand: Map[ActorRef, PeerDemand] = Map.empty.withDefaultValue(PeerDemand.empty)
  private[this] var queue: Seq[QueuedRequest] = List.empty

  def download(request: PieceBlockRequest)(implicit dsp: PeerDispatcherContext): Unit = {
    download(QueuedRequest(Some(actorContext.sender()), request))
  }

  def success(block: DownloadedBlock)(implicit dsp: PeerDispatcherContext): Unit = {
    val peer = actorContext.sender()
    var handlers = List.empty[ActorRef]
    demand(peer) match {
      case PeerDemand(requests, rate) ⇒
        val (drop, keep) = requests.partition(_.request.isRelatedTo(block))
        handlers ++= drop.flatMap(_.handler)
        demand += peer → PeerDemand(keep, rate.update(block.length))
    }

    queue.partition(_.request.isRelatedTo(block)) match {
      case (drop, keep) ⇒
        handlers ++= drop.flatMap(_.handler)
        queue = keep
    }

    handlers.foreach(_ ! block)
    retryQueued()
  }

  def failure(fd: BlockDownloadFailed)(implicit dsp: PeerDispatcherContext): Unit = {
    val peer = actorContext.sender()
    val pd @ PeerDemand(requests, _) = demand(peer)
    val (drop, keep) = requests.partition(_.request.isRelatedTo(fd))
    demand += peer → pd.copy(keep)
    queue ++= drop.headOption
    retryQueued()(PeerDispatcherContext(dsp.peers - peer))
  }

  def cancel(cancel: CancelBlockDownload)(implicit dsp: PeerDispatcherContext): Unit = {
    def matches(qr: QueuedRequest): Boolean = qr.handler.contains(actorContext.sender()) && qr.request.isRelatedTo(cancel)
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

  def retryQueued()(implicit dsp: PeerDispatcherContext): Unit = {
    val retry = queue
    if (retry.nonEmpty && dsp.peers.nonEmpty) {
      queue = List.empty
      retry.foreach(this.download)
    }
  }

  def removePeer(peer: ActorRef)(implicit dsp: PeerDispatcherContext): Unit = {
    if (demand.contains(peer)) {
      val PeerDemand(requests, _) = demand(peer)
      demand -= peer
      queue ++= requests
      retryQueued()
    }
  }

  private[this] def download(queuedRequest: QueuedRequest)(implicit dsp: PeerDispatcherContext): Unit = {
    val availablePeers = for {
      (peer, data) ← dsp.peers.iterator if data.completed(queuedRequest.request.index) && !data.chokedBy
      demand @ PeerDemand(queue, rate) ← Some(demand(peer)) if queue.length < rate.queueSize
    } yield (peer, demand)

    if (availablePeers.nonEmpty) {
      val (peer, peerDemand) = availablePeers.maxBy(_._2.rate.rate)

      // println(s"${peerDemand.queue.length}/${peerDemand.rate.queueSize} (rate: ${peerDemand.rate.rate})")
      // log.debug("Peer download queue: {} {}/{} (rate: {} MB/sec)", peer, peerDemand.queue.length, peerDemand.rate.queueSize, peerDemand.rate.rate.toDouble / 1048576)

      demand += peer → peerDemand.copy(peerDemand.queue :+ queuedRequest)
      peer ! queuedRequest.request
    } else {
      queue :+= queuedRequest
    }
  }

  private[this] final case class QueuedRequest(handler: Option[ActorRef], request: PieceBlockRequest)

  private[this] case class PeerDownloadRate(time: Long, downloaded: Long) {
    private[this] val SampleTime = 1000000000L // 1 second

    private[this] val timestamp = {
      if (downloaded == 0) 0 else getCurrentTime()
    }

    val rate: Long = downloaded * SampleTime / (time + 1)

    val queueSize: Int = {
      val queueSize = (rate / blockSize * queueSizeFactor).toInt
      math.min(maxQueueSize, math.max(minQueueSize, queueSize))
    }

    def updatedAgo: Long = {
      getCurrentTime() - timestamp
    }

    def update(bytes: Long): PeerDownloadRate = {
      copy(SampleTime + updatedAgo, rate + bytes)
    }

    private[this] def getCurrentTime(): Long = {
      System.nanoTime()
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
