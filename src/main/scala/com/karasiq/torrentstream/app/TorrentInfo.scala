package com.karasiq.torrentstream.app

import com.karasiq.bittorrent.format.TorrentMetadata

case class TorrentInfo(announceList: Seq[Seq[String]], comment: String, createdBy: String, files: Seq[(String, Long)], infoHash: String, name: String, size: Long)

object TorrentInfo {
  def fromTorrent(torrent: TorrentMetadata): TorrentInfo = {
    TorrentInfo(
      torrent.announceList,
      torrent.comment.getOrElse(""),
      torrent.createdBy.getOrElse(""),
      torrent.files.files.map(f ⇒ f.name → f.size),
      torrent.infoHashString,
      torrent.name,
      torrent.size
    )
  }
}