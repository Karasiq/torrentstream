package com.karasiq.bittorrent.announce

import java.net.InetSocketAddress

import akka.util.ByteString

import com.karasiq.bittorrent.dht.DHTMessages
import com.karasiq.bittorrent.format._
import com.karasiq.bittorrent.format.BEncodeImplicits._

case class TrackerPeer(peerId: Option[String], address: InetSocketAddress)

case class TrackerResponse(warning: Option[String], interval: Int, minInterval: Option[Int], trackerId: Option[String], complete: Option[Int], incomplete: Option[Int], peers: Seq[TrackerPeer])

case class TrackerError(reason: String)

object TrackerResponse {
  private def parsePeers: PartialFunction[BEncodedValue, Seq[TrackerPeer]] = {
    case BEncodedArray(peers) ⇒
      peers.collect {
        case BEncodedDictionary(values) ⇒
          val peer = values.toMap
          for {
            id <- Some(peer.string("peer id"))
            ip <- peer.string("ip")
            port <- peer.long("port")
          } yield TrackerPeer(id, InetSocketAddress.createUnresolved(ip, port.toInt))
      }.flatten

    case s: BEncodedString ⇒ // Compact
      DHTMessages.PeerAddress.parseList(s.asByteString)
        .map(pa ⇒ TrackerPeer(None, pa.address))
  }

  def fromBytes(str: ByteString): Either[TrackerError, TrackerResponse] = {
    val data = BEncode.parse(str.toArray[Byte])
    data match {
      case Seq(BEncodedDictionary(values)) ⇒
        val map = values.toMap
        map.string("failure reason") match {
          case Some(error) ⇒
            Left(TrackerError(s"Tracker error: $error"))

          case None ⇒
            val response = for {
              interval <- map.int("interval")
              peers <- map.get("peers").collect(parsePeers)
            } yield TrackerResponse(map.string("warning message"), interval, map.int("min interval"), map.string("tracker id"), map.int("complete"), map.int("incomplete"), peers)

            response.fold[Either[TrackerError, TrackerResponse]](Left(TrackerError("Invalid response")))(Right.apply)
        }

      case _ ⇒
        Left(TrackerError(s"Not a BEncoded value: ${str.utf8String}"))
    }
  }
}