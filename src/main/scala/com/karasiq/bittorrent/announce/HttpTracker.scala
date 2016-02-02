package com.karasiq.bittorrent.announce

import akka.actor.{Actor, ActorLogging}
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.stream.scaladsl._
import akka.util.ByteString
import org.apache.commons.codec.binary.Hex

import scala.util.{Failure, Success}

class HttpTracker extends Actor with ActorLogging with ImplicitMaterializer {
  import context.dispatcher

  private val http = Http(context.system)

  override def receive: Receive = {
    case r @ TrackerRequest(url, infoHash, peerId, port, uploaded, downloaded, left, compact, noPeerId, event, ip, numWant, key, trackerId) ⇒
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
        case (key: String, str: ByteString) if str.nonEmpty ⇒
          key → Hex.encodeHexString(str.toArray).grouped(2).mkString("%", "%", "")

        case (key: String, str: String) if str.nonEmpty ⇒
          key → str
      }

      val uri = Uri(url).withRawQueryString(query.map(kv ⇒ kv._1 + "=" + kv._2).mkString("&"))
      val request = HttpRequest(HttpMethods.GET, uri)
      log.info(s"Tracker request: $request")

      val response = http.singleRequest(request).flatMap { response ⇒
        response.entity.dataBytes
          .fold(ByteString.empty)(_ ++ _)
          .map(TrackerResponse.fromBytes)
          .log("http-tracker-response")
          .runWith(Sink.head)
      }

      val sender = context.sender()
      response.onComplete {
        case Success(result) ⇒
          sender ! result.fold(identity, identity)

        case Failure(_) ⇒
          sender ! TrackerError("Tracker communication failure")
      }
  }
}
