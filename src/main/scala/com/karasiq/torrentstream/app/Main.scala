package com.karasiq.torrentstream.app

import java.io.File

import akka.actor.{ActorSystem, Props}
import akka.util.ByteString
import com.karasiq.torrentstream.{DownloadTorrent, TorrentWriter}
import com.karasiq.ttorrent.common.Torrent
import org.apache.log4j.BasicConfigurator

import scala.collection.JavaConversions._
import scala.util.Random

object Main extends App {
  BasicConfigurator.configure()

  val actorSystem = ActorSystem("torrent-stream")

  val writer = actorSystem.actorOf(Props[TorrentWriter])
  val torrent = Torrent.load(new File(sys.props("test.torrent.path")))
  writer ! DownloadTorrent(ByteString(torrent.getEncoded), Random.shuffle(torrent.getFilenames).head)
}
