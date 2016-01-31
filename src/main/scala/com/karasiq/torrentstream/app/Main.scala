package com.karasiq.torrentstream.app

import java.io.File

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Range
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl._
import akka.util.ByteString
import com.karasiq.torrentstream._
import com.karasiq.ttorrent.common.Torrent
import org.apache.log4j.BasicConfigurator

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class AppHandler(implicit am: ActorMaterializer, ac: ActorSystem)

object Main extends App {
  BasicConfigurator.configure()

  implicit val actorSystem = ActorSystem("torrent-stream")
  implicit val materializer = ActorMaterializer()

  Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
    override def run(): Unit = {
      actorSystem.log.info("Stopping torrent streamer")
      Await.result(actorSystem.terminate(), 2 minutes)
    }
  }))

  def offsetValue: Directive1[Long] = {
    optionalHeaderValueByType[Range]().map {
      case Some(range) ⇒
        range.ranges.head.getOffset().getOrElse(0).asInstanceOf[Long]
      case None ⇒
        0L
    }
  }

  def createTorrentStream(offset: Long): Directive[(Long, Source[ByteString, Unit])] = {
    val writer = actorSystem.actorOf(Props[TorrentWriter])
    val torrent = Torrent.load(new File(sys.props("test.torrent.path")))
    val file = torrent.getFiles.head
    writer ! DownloadTorrent(ByteString(torrent.getEncoded), file.file.getPath, offset)
    tprovide(file.size → Source.fromPublisher(ActorPublisher[TorrentChunk](writer)).map(_.data))
  }


  val route = get {
    (pathSingleSlash & offsetValue) { offset ⇒
      createTorrentStream(offset) { (size, stream) ⇒
        complete(if (offset == 0) StatusCodes.OK else StatusCodes.PartialContent, HttpEntity(ContentTypes.NoContentType, size - offset, stream))
      }
    }
  }
  Http().bindAndHandle(route, "localhost", 8901)
}
