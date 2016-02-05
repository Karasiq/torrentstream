package com.karasiq.bittorrent.streams

import akka.actor._
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import com.karasiq.bittorrent.dispatcher.PeerProtocol.PieceBlockRequest
import com.karasiq.bittorrent.dispatcher._

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

class PeerBlockPublisher(peerDispatcher: ActorRef, pieceSize: Int) extends Actor with ActorPublisher[DownloadedBlock] with ActorLogging with Stash {
  private val peersRequired = context.system.settings.config.getInt("karasiq.torrentstream.peer-load-balancer.peers-per-piece")
  private var requested = Map.empty[ActorRef, Set[PieceBlockRequest]].withDefaultValue(Set.empty)
  private var buffer = Vector.empty[DownloadedBlock]
  private val peerSet = mutable.Set.empty[ActorRef]
  private val demand = mutable.Map.empty[ActorRef, Int].withDefaultValue(0)
  private var awaitingPeer: Boolean = false
  private var currentOffset = 0

  override def postStop(): Unit = {
    for ((peer, requests) <- requested; PieceBlockRequest(index, offset, length) <- requests) {
      peer ! CancelBlockDownload(index, offset, length)
    }
    super.postStop()
  }

  @tailrec
  private def deliverBuffer(): Unit = {
    if (totalDemand > 0) {
      val (use, keep) = buffer.partition(_.offset == currentOffset)
      if (use.nonEmpty) {
        buffer = keep
        onNext(use.head)
        currentOffset += use.head.data.length
        if (currentOffset == pieceSize) {
          onCompleteThenStop()
        } else {
          deliverBuffer()
        }
      }
    }
  }

  private def deliver(chunk: DownloadedBlock): Unit = {
    if (chunk.offset >= currentOffset) {
      if (buffer.length < 200) {
        buffer :+= chunk
      } else {
        // Overflow
        buffer = buffer.drop(1) :+ chunk
      }
    }
    deliverBuffer()
  }

  override def receive: Receive = {
    case request @ PieceBlockRequest(index, offset, length) ⇒
      if (peerSet.size < peersRequired && !awaitingPeer) {
        peerDispatcher ! RequestPeers(index, peersRequired)
        awaitingPeer = true
        context.setReceiveTimeout(10 seconds)
      }

      if (peerSet.isEmpty) {
        stash()
      } else if (!requested.values.exists(_.contains(request))) {
        log.debug("Retrying block: {}/{}/{}", index, offset, length)
        val peer = peerSet.minBy(demand)
        peer ! request
        demand += peer → (demand(peer) + 1)
        requested += peer → (requested(peer) + request)
      }

    case PeerList(peers) ⇒
      val newPeers = peers.filterNot(peerSet)
      peerSet ++= newPeers
      newPeers.foreach(context.watch)
      awaitingPeer = false
      unstashAll()

    case Request(_) ⇒
      deliverBuffer()

    case Cancel ⇒
      context.stop(self)

    case BlockDownloadFailed(index, offset, length) ⇒
      val peer = context.sender()
      peerSet -= peer
      val (drop, keep) = requested(peer).partition(r ⇒ r.index == index && r.offset == offset)
      if (drop.nonEmpty) {
        requested += peer → keep
        self ! drop.head
      }

    case block @ DownloadedBlock(index, offset, data) ⇒
      val peer = context.sender()
      demand += peer → (demand(peer) - 1)
      log.debug("Block published: {}/{}/{}", index, offset, data.length)
      requested += peer → requested(peer).filterNot(r ⇒ r.index == index && r.offset == offset && r.length == data.length)
      deliver(block)

    case Terminated(peer) ⇒
      requested(peer).foreach(self ! _)
      requested -= peer
      peerSet -= peer
  }
}
