package com.karasiq.bittorrent.streams

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Source}
import akka.util.{ByteString, Timeout}

import com.karasiq.bittorrent.dispatcher._
import com.karasiq.bittorrent.format.{Torrent, TorrentPiece}
import com.karasiq.bittorrent.protocol.PeerMessages.PieceBlockRequest

object TorrentSource {
  private[streams] def pieceBlocks(peerDispatcher: ActorRef, index: Int, piece: TorrentPiece, blockSize: Int): Source[ByteString, _] = {
    val blocks = TorrentPiece.blocks(piece, blockSize).toVector
    val size = blocks.map(_.size).sum
    Source
      .actorPublisher[DownloadedBlock](PeerBlockPublisher.props(peerDispatcher, size))
      .mapMaterializedValue(loader ⇒ blocks.foreach(block ⇒ loader ! PieceBlockRequest(index, block.offset, block.size)))
      .take(blocks.length)
      .fold(ByteString.empty)((bs, block) ⇒ bs ++ block.data)
  }

  private def pieceSource(dispatcher: ActorRef, piece: TorrentPiece): Source[DownloadedPiece, ActorRef] = {
    Source
      .actorPublisher[DownloadedPiece](PeerPiecePublisher.props(dispatcher, PieceDownloadRequest(piece)))
  }

  def pieces(dispatcher: ActorRef, pcs: Vector[TorrentPiece]): Source[DownloadedPiece, akka.NotUsed] = {
    Source(pcs)
      .flatMapMerge(3, { piece ⇒ pieceSource(dispatcher, piece) })
  }

  def torrent(dispatcher: ActorRef, torrent: Torrent): Source[DownloadedPiece, akka.NotUsed] = {
    pieces(dispatcher, TorrentPiece.pieces(torrent.content).toVector)
  }

  def dispatcher(torrentManager: ActorRef): Flow[Torrent, PeerDispatcherData, akka.NotUsed] = {
    implicit val timeout = Timeout(10 seconds)
    Flow[Torrent]
      .mapAsync(1)(torrent ⇒ (torrentManager ? CreateDispatcher(torrent)).mapTo[PeerDispatcherData])
      .initialTimeout(10 seconds)
  }
}
