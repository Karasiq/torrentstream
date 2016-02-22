package com.karasiq.torrentstream.app

import com.karasiq.bittorrent.format.Torrent

private[app] case class TorrentInfo(announceList: Seq[Seq[String]], comment: String, createdBy: String, files: Seq[(String, Long)], infoHash: String, name: String, size: Long)

private[app] object TorrentInfo {
  def fromTorrent(torrent: Torrent): TorrentInfo = {
    TorrentInfo(
      if (torrent.announceList.nonEmpty) torrent.announceList else Vector(Vector(torrent.announce)),
      torrent.comment.getOrElse(""),
      torrent.createdBy.getOrElse(""),
      torrent.data.files.map(f ⇒ f.name → f.size),
      torrent.infoHashString,
      torrent.data.name,
      torrent.size
    )
  }
}