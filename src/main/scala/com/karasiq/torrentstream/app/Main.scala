package com.karasiq.torrentstream.app

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import org.apache.log4j.BasicConfigurator

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

object Main extends App {
  BasicConfigurator.configure()

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

  val handler = new AppHandler(store)
  Http().bindAndHandle(handler.route, "localhost", 8901)
}
