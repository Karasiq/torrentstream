package com.karasiq.bittorrent.streams

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Source}
import akka.util.{ByteString, Timeout}

import com.karasiq.bittorrent.dispatcher._
import com.karasiq.bittorrent.dispatcher.TorrentManager.{CreateDispatcher, PeerDispatcherData}
import com.karasiq.bittorrent.format.{Torrent, TorrentPiece}
import com.karasiq.bittorrent.protocol.PeerMessages.PieceBlockRequest

object TorrentSource {
  private[bittorrent] val PieceParallelism = 3
  private[this] implicit val AskTimeout: Timeout = 10 seconds

  def pieces(dispatcher: ActorRef, pcs: Seq[TorrentPiece]): Source[DownloadedPiece, NotUsed] = {
    Source(pcs.toList)
      .flatMapMerge(PieceParallelism, pieceSource(dispatcher, _))
      .named("torrentPieces")
  }

  def torrent(dispatcher: ActorRef, torrent: Torrent): Source[DownloadedPiece, NotUsed] = {
    pieces(dispatcher, TorrentPiece.pieces(torrent.content))
  }

  def dispatcher(torrentManager: ActorRef): Flow[Torrent, PeerDispatcherData, NotUsed] = {
    Flow[Torrent]
      .mapAsync(1)(torrent ⇒ (torrentManager ? CreateDispatcher(torrent)).mapTo[PeerDispatcherData])
      .named("createPeerDispatcher")
  }

  private[streams] def pieceBlocks(peerDispatcher: ActorRef, index: Int, piece: TorrentPiece, blockSize: Int): Source[ByteString, NotUsed] = {
    val blocks = TorrentPiece.blocks(piece, blockSize).toList
    val size = blocks.map(_.size).sum
    Source
      .actorPublisher[DownloadedBlock](PeerBlockPublisher.props(peerDispatcher, size))
      .take(blocks.length)
      .fold(ByteString.empty)((bs, block) ⇒ bs ++ block.data)
      .mapMaterializedValue(loader ⇒ blocks.foreach(block ⇒ loader ! PieceBlockRequest(index, block.offset, block.size)))
      .mapMaterializedValue(_ ⇒ NotUsed)
      .named("torrentPieceBlocks")
  }

  private[streams] def pieceSource(dispatcher: ActorRef, piece: TorrentPiece): Source[DownloadedPiece, ActorRef] = {
    Source
      .actorPublisher[DownloadedPiece](PeerPiecePublisher.props(dispatcher, PieceDownloadRequest(piece)))
      .named("torrentPiece")
  }
}
