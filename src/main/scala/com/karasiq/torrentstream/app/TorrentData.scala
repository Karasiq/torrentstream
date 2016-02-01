package com.karasiq.torrentstream.app

import com.karasiq.ttorrent.common.Torrent

import scala.collection.JavaConversions._

case class TorrentData(announceList: Seq[Seq[String]], comment: String, createdBy: String, files: Seq[(String, Long)], infoHash: String, name: String, size: Long)

case class TorrentStoreEntry(infoHash: String, data: TorrentData)

object TorrentData {
  def fromTorrent(torrent: Torrent): TorrentData = {
    TorrentData(
      torrent.getAnnounceList.map(_.map(_.toString).toSeq).toSeq,
      torrent.getComment,
      torrent.getCreatedBy,
      torrent.getFiles.map(f ⇒ f.file.getPath → f.size).toSeq,
      torrent.getHexInfoHash,
      torrent.getName,
      torrent.getSize
    )
  }
}