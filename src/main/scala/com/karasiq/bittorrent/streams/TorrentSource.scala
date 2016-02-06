package com.karasiq.bittorrent.streams

import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Source}
import akka.util.{ByteString, Timeout}
import com.karasiq.bittorrent.dispatcher.PeerProtocol.PieceBlockRequest
import com.karasiq.bittorrent.dispatcher._
import com.karasiq.bittorrent.format.{TorrentFileInfo, TorrentMetadata, TorrentPiece}

import scala.concurrent.duration._
import scala.language.postfixOps

object TorrentSource {
  def pieceBlocks(peerDispatcher: ActorRef, index: Int, piece: TorrentPiece, blockSize: Int): Source[ByteString, _] = {
    val blocks = TorrentPiece.blocks(piece, blockSize).toVector
    val size = blocks.map(_.size).sum
    Source
      .actorPublisher[DownloadedBlock](Props(classOf[PeerBlockPublisher], peerDispatcher, size))
      .mapMaterializedValue(loader ⇒ blocks.foreach(block ⇒ loader ! PieceBlockRequest(index, block.offset, block.size)))
      .take(blocks.length)
      .fold(ByteString.empty)((bs, block) ⇒ bs ++ block.data)
  }

  def pieceSource(dispatcher: ActorRef, index: Int, piece: TorrentPiece): Source[DownloadedPiece, ActorRef] = {
    Source.actorPublisher[DownloadedPiece](Props(classOf[PeerPiecePublisher], PieceDownloadRequest(index, piece), dispatcher))
  }

  def pieces(dispatcher: ActorRef, pcs: Vector[(TorrentPiece, Int)]): Source[DownloadedPiece, Unit] = {
    Source(pcs)
      .flatMapConcat({ case (piece, index) ⇒ pieceSource(dispatcher, index, piece) })
  }

  def torrent(dispatcher: ActorRef, torrent: TorrentMetadata): Source[DownloadedPiece, Unit] = {
    pieces(dispatcher, TorrentPiece.sequence(torrent.files).toVector.zipWithIndex)
  }

  def file(dispatcher: ActorRef, torrent: TorrentMetadata, file: TorrentFileInfo, start: Long, size: Long): Source[DownloadedPiece, Unit] = {
    val drop: Int = (start / torrent.files.pieceLength).toInt
    val take: Int = math.ceil(size / torrent.files.pieceLength).toInt
    val pcs = TorrentPiece.sequence(torrent.files).toVector.zipWithIndex
      .filter(_._1.file == file)
      .slice(drop, drop + take)
    pieces(dispatcher, pcs)
  }

  def dispatcher(torrentManager: ActorRef): Flow[TorrentMetadata, PeerDispatcherData, Unit] = {
    implicit val timeout = Timeout(10 seconds)
    Flow[TorrentMetadata]
      .mapAsync(1)(torrent ⇒ (torrentManager ? CreateDispatcher(torrent)).mapTo[PeerDispatcherData])
      .initialTimeout(10 seconds)
  }
}
