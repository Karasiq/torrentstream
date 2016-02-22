package com.karasiq.torrentstream.frontend

case class TorrentInfo(announceList: Seq[Seq[String]], comment: String, createdBy: String, files: Seq[(String, Long)], infoHash: String, name: String, size: Long)