package com.karasiq.torrentstream.app

import java.io.File

import akka.actor.{ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl._
import akka.util.{ByteString, Timeout}
import com.karasiq.torrentstream.{DownloadTorrent, InterruptTorrentDownload, TorrentChunk, TorrentWriter}
import com.karasiq.ttorrent.common.Torrent
import org.apache.log4j.BasicConfigurator

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

object Main extends App {
  BasicConfigurator.configure()

  implicit val actorSystem = ActorSystem("torrent-stream")
  implicit val materializer = ActorMaterializer()

  val writer = actorSystem.actorOf(Props[TorrentWriter])
  Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
    override def run(): Unit = {
      import actorSystem.dispatcher
      import akka.pattern.ask
      implicit val timeout = Timeout(5 minutes)
      actorSystem.log.info("Stopping torrent streamer")
      (writer ? InterruptTorrentDownload).onComplete { case _ ⇒
        Await.result(actorSystem.terminate(), 2 minutes)
      }
    }
  }))
  val torrentWriterSource = Source.fromPublisher(ActorPublisher[TorrentChunk](writer))
  val materializedTorrent = torrentWriterSource
    .to(Sink.foreach(ch ⇒ println(ch.data)))
    .run()

  val torrent = Torrent.load(new File(sys.props("test.torrent.path")))
  writer ! DownloadTorrent(ByteString(torrent.getEncoded), Random.shuffle(torrent.getFilenames.toList).head)
  writer ! InterruptTorrentDownload // Test interruption
  writer ! DownloadTorrent(ByteString(torrent.getEncoded), Random.shuffle(torrent.getFilenames.toList).head)
}
