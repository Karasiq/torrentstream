package com.karasiq.torrentstream

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.karasiq.bittorrent.format.Torrent
import com.karasiq.bittorrent.streams.TorrentSource

case class TorrentStream(size: Long, source: Source[ByteString, Unit])

object TorrentStream {
  def create(torrentManager: ActorRef, torrent: Torrent, fileName: String, ranges: Seq[(Long, Long)] = Nil)(implicit actorSystem: ActorSystem): TorrentStream = {
    val config = actorSystem.settings.config.getConfig("karasiq.torrentstream.streamer")
    val bufferSize = config.getInt("buffer-size")

    val file = torrent.data.files.find(_.name == fileName).getOrElse(torrent.data.files.head)
    val pieces = if (ranges.nonEmpty) {
      TorrentFileOffset.absoluteOffsets(torrent, ranges.map(o ⇒ TorrentFileOffset(file, o._1, o._2)))
    } else {
      TorrentFileOffset.file(torrent, file)
    }
    val source = Source.single(torrent)
      .via(TorrentSource.dispatcher(torrentManager))
      .flatMapConcat(dsp ⇒ TorrentSource.pieces(dsp.actorRef, pieces.pieces.toVector))
      .buffer(bufferSize, OverflowStrategy.backpressure)
      .transform(() ⇒ new TorrentStreamingStage(torrent.data.pieceLength, pieces.offsets))

    TorrentStream(pieces.size, source)
  }
}
