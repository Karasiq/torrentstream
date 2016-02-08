package com.karasiq.bittorrent.streams

import akka.actor._
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import com.karasiq.bittorrent.dispatcher._
import com.karasiq.bittorrent.protocol.PeerMessages
import com.karasiq.bittorrent.protocol.PeerMessages.PieceBlockRequest

import scala.annotation.tailrec
import scala.collection.mutable
import scala.language.postfixOps

class PeerBlockPublisher(peerDispatcher: ActorRef, pieceSize: Int) extends Actor with ActorPublisher[DownloadedBlock] with ActorLogging with Stash {
  // Configuration
  private val config = context.system.settings.config.getConfig("karasiq.torrentstream.peer-load-balancer")
  private val peersPerPiece = config.getInt("peers-per-piece")
  private val peersPerBlock = config.getInt("peers-per-block")

  // Buffers
  private var requested = Map.empty[PieceBlockRequest, Set[ActorRef]].withDefaultValue(Set.empty)
  private var completed = Set.empty[PieceBlockRequest]
  private var buffer = Vector.empty[DownloadedBlock]
  private val peerSet = mutable.Set.empty[ActorRef]
  private val demand = mutable.Map.empty[ActorRef, Int].withDefaultValue(0)
  private var awaitingPeer: Boolean = false
  private var currentOffset = 0

  override def postStop(): Unit = {
    for ((PieceBlockRequest(index, offset, length), peers) <- requested; peer <- peers) {
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
      if (peerSet.size < peersPerPiece && !awaitingPeer) {
        peerDispatcher ! RequestPeers(index, peersPerPiece)
        awaitingPeer = true
      }

      if (peerSet.isEmpty) {
        stash()
      } else if (requested(request).isEmpty) {
        log.debug("Requesting block: {}/{}/{}", index, offset, length)
        val peers = peerSet.toSeq.sortBy(demand).take(peersPerBlock)
        peers.foreach { peer ⇒
          peer ! request
          demand += peer → (demand(peer) + 1)
        }
        requested += request → (requested(request) ++ peers)
      }

    case PeerList(peers) ⇒
      val newPeers = peers.filterNot(peerSet).take(peersPerPiece - peerSet.size)
      peerSet ++= newPeers
      newPeers.foreach(context.watch)
      awaitingPeer = false
      unstashAll()

    case Request(_) ⇒
      deliverBuffer()

    case Cancel ⇒
      context.stop(self)

    case BlockDownloadFailed(index, offset, length) ⇒
      val request = PieceBlockRequest(index, offset, length)
      if (!completed(request)) {
        val peer = context.sender()
        peerSet -= peer
        val awaiting = requested(request) - peer
        requested += request → awaiting
        if (awaiting.isEmpty) {
          // Retry
          log.debug("Retrying block: {}/{}/{}", request.index, request.offset, request.length)
          self ! request
        }
      }

    case block @ DownloadedBlock(index, offset, data) ⇒
      val request = PieceBlockRequest(index, offset, data.length)
      val peer = context.sender()
      demand += peer → (demand(peer) - 1)
      for (peers <- requested.get(request); p <- peers if p != peer) {
        p ! CancelBlockDownload(index, offset, data.length)
      }
      requested -= request
      if (!completed(request)) {
        completed += request
        log.debug("Block published: {}/{}/{}", index, offset, data.length)
        deliver(block)
      }

    case Terminated(peer) ⇒
      requested = requested.map(kv ⇒ kv._1 → (kv._2 - peer))
      requested.filter(_._2.isEmpty).foreach {
        case (request, _) ⇒
          self ! request
      }
      peerSet -= peer
  }
}
