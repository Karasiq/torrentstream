package com.karasiq.bittorrent.announce

import java.net.InetSocketAddress

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Materializer}
import akka.stream.scaladsl._
import akka.util.ByteString

import com.karasiq.bittorrent.utils.Utils
import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.networkutils.uri._
import com.karasiq.proxy.ProxyChain

object HttpAnnouncer {
  def props: Props = {
    Props(new HttpAnnouncer)
  }
}

private final class HttpAnnouncer extends Actor with ActorLogging {
  import context.dispatcher
  private[this] implicit val materializer: Materializer = ActorMaterializer(ActorMaterializerSettings(context.system))
  private[this] val http = Http(context.system)

  private[this] object trackerSettings {
    private[this] val config = context.system.settings.config.getConfig("karasiq.bittorrent.tracker")
    val proxies: Seq[Proxy] = config.getStringList("proxies").asScala.map(url ⇒ Proxy(url))
  }

  override def receive: Receive = {
    case TrackerRequest(url, infoHash, peerId, port, uploaded, downloaded, left, compact, noPeerId, event, ip, numWant, key, trackerId) ⇒
      val params = Map(
        "info_hash" → infoHash,
        "peer_id" → infoHash,
        "port" → port.toString,
        "uploaded" → uploaded.toString,
        "downloaded" → downloaded.toString,
        "left" → left.toString,
        "compact" → (if (compact) "1" else "0"),
        "no_peer_id" → (if (noPeerId) "1" else "0"),
        "event" → event.getOrElse(""),
        "ip" → ip.fold("")(_.getHostAddress),
        "numwant" → numWant.toString,
        "key" → key.getOrElse(""),
        "trackerid" → trackerId.getOrElse("")
      )

      val query = params.collect {
        case (key: String, bytes: ByteString) if bytes.nonEmpty ⇒
          key → Utils.toHexString(bytes).grouped(2).mkString("%", "%", "")

        case (key: String, str: String) if str.nonEmpty ⇒
          key → str
      }

      val uri = Uri(url).withRawQueryString(query.map(kv ⇒ kv._1 + "=" + kv._2).mkString("&"))
      val request = HttpRequest(HttpMethods.GET, uri)
      log.info(s"Tracker request: $request")

      val proxyChainTransport = new ClientTransport {
        def connectTo(host: String, port: Int, settings: ClientConnectionSettings)
                     (implicit system: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] = {
          val address = InetSocketAddress.createUnresolved(host, port)
          val proxyChain = ProxyChain.connect(address, trackerSettings.proxies)
          proxyChain.mapMaterializedValue { case (connFuture, proxyFuture) ⇒
            connFuture
              .flatMap(tcpConn ⇒ proxyFuture.map(_ ⇒ tcpConn))
              .map(tcpConn ⇒ Http.OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress))
          }
        }
      }

      val httpSettings = ConnectionPoolSettings(context.system).withTransport(proxyChainTransport)
      val responseStream = Source.fromFuture(http.singleRequest(request, settings = httpSettings))
        .flatMapConcat { response ⇒
          response.entity.dataBytes
            .fold(ByteString.empty)(_ ++ _)
            .map(TrackerResponse.fromBytes)
        }
        .log("http-tracker-response")
        .idleTimeout(10 seconds)
        .recover { case error ⇒ TrackerError(s"Tracker communication failure: $error") }
        .orElse(Source.single(TrackerError("Tracker communication failure")))

      val sender = context.sender()
      responseStream.runForeach {
        case Right(result) ⇒
          sender ! result

        case Left(error) ⇒
          sender ! error
      }
  }
}
