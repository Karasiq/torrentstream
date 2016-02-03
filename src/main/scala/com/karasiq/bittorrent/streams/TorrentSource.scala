package com.karasiq.bittorrent.streams

import akka.actor.{ActorRef, Props}
import akka.stream.scaladsl.Source
import com.karasiq.bittorrent.dispatcher.{DownloadedPiece, PieceDownloadRequest}
import com.karasiq.bittorrent.format.{TorrentFileInfo, TorrentMetadata, TorrentPiece}

object TorrentSource {
  def pieceSource(dispatcher: ActorRef, index: Int, piece: TorrentPiece): Source[DownloadedPiece, ActorRef] = {
    Source.actorPublisher[DownloadedPiece](Props(classOf[PeerPiecePublisher], PieceDownloadRequest(index, piece), dispatcher))
  }

  def pieces(dispatcher: ActorRef, pcs: Vector[(TorrentPiece, Int)]): Source[DownloadedPiece, Unit] = {
    Source(pcs)
      .flatMapConcat { case (piece, index) ⇒ pieceSource(dispatcher, index, piece) }
  }

  def torrent(dispatcher: ActorRef, torrent: TorrentMetadata): Source[DownloadedPiece, Unit] = {
    pieces(dispatcher, TorrentPiece.sequence(torrent.files).toVector.zipWithIndex)
  }

  def file(dispatcher: ActorRef, torrent: TorrentMetadata, file: TorrentFileInfo, offset: Long = 0): Unit = {
    val pcs = TorrentPiece.sequence(torrent.files).toVector.zipWithIndex
      .filter(_._1.file == file)
      .drop((offset / torrent.files.pieceLength).toInt)
    pieces(dispatcher, pcs)
  }
}
