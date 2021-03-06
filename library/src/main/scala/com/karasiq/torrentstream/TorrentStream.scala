package com.karasiq.torrentstream

import akka.actor.ActorRef
import akka.stream.scaladsl.Source
import akka.util.ByteString

import com.karasiq.bittorrent.format.Torrent
import com.karasiq.bittorrent.streams.TorrentSource

case class TorrentStream(size: Long, source: Source[ByteString, akka.NotUsed])

object TorrentStream {
  def create(torrentManager: ActorRef, torrent: Torrent, fileName: String, ranges: Seq[(Long, Long)] = Nil): TorrentStream = {
    val file = torrent.content.files.find(_.name == fileName).getOrElse(torrent.content.files.head)
    val pieces = if (ranges.nonEmpty) {
      TorrentFileOffset.absoluteOffsets(torrent, ranges.map(o ⇒ TorrentFileOffset(file, o._1, o._2)))
    } else {
      TorrentFileOffset.file(torrent, file)
    }
    val source = Source.single(torrent)
      .via(TorrentSource.dispatcher(torrentManager))
      .flatMapConcat(dsp ⇒ TorrentSource.pieces(dsp.actorRef, pieces.pieces.toVector))
      .via(TorrentStreamingStage(torrent.content.pieceSize, pieces.offsets))

    TorrentStream(pieces.size, source)
  }
}
