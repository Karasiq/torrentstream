package com.karasiq.torrentstream.app

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.karasiq.bittorrent.dispatcher.TorrentManager
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

object Main extends App {
  implicit val actorSystem = ActorSystem("torrent-stream")
  implicit val materializer = ActorMaterializer()

  val store = new TorrentStore(ConfigFactory.load())

  Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
    override def run(): Unit = {
      actorSystem.log.info("Stopping torrent streamer")
      actorSystem.registerOnTermination(store.close())
      Await.result(actorSystem.terminate(), 2 minutes)
    }
  }))

  val torrentManager = actorSystem.actorOf(TorrentManager.props)
  val handler = new AppHandler(torrentManager, store)
  val config = actorSystem.settings.config.getConfig("karasiq.torrentstream.http-server")
  val host = config.getString("host")
  val port = config.getInt("port")
  actorSystem.log.info("Torrent streaming server started on http://{}:{}", host, port)
  Http().bindAndHandle(handler.route, host, port)
}
