package com.karasiq.torrentstream

import akka.util.ByteString

import scala.concurrent.duration._
import scala.collection.JavaConversions._

sealed trait TorrentEvent

case class TorrentFileStart(file: String, size: Long) extends TorrentEvent

case class TorrentChunk(file: String, offset: Long, data: ByteString) extends TorrentEvent

case class TorrentFileEnd(file: String) extends TorrentEvent
