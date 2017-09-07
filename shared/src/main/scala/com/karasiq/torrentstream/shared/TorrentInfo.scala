package com.karasiq.torrentstream.shared

import boopickle.Pickler
import boopickle.Default._

@SerialVersionUID(0L)
final case class TorrentInfo(announceList: Seq[Seq[String]], comment: String,
                             createdBy: String, files: Seq[(String, Long)],
                             infoHash: String, name: String, size: Long)

object TorrentInfo {
  implicit val pickler: Pickler[TorrentInfo] = generatePickler[TorrentInfo]
}
