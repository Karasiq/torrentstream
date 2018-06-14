package com.karasiq.torrentstream.app

import com.karasiq.bittorrent.format.Torrent
import com.karasiq.torrentstream.shared.TorrentInfo

object TorrentUtils {
  def toTorrentInfo(torrent: Torrent): TorrentInfo = {
    TorrentInfo(
      if (torrent.announceList.nonEmpty) torrent.announceList else Vector(Vector(torrent.announce)),
      torrent.comment.getOrElse(""),
      torrent.createdBy.getOrElse(""),
      torrent.content.files.map(f ⇒ f.name → f.size),
      torrent.infoHashString,
      torrent.content.name,
      torrent.size
    )
  }
}
