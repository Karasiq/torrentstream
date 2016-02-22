package com.karasiq.bittorrent.announce

import java.net.{InetAddress, InetSocketAddress}

import akka.util.ByteString
import com.karasiq.bittorrent.format.BEncodeImplicits._
import com.karasiq.bittorrent.format._
import com.karasiq.bittorrent.protocol.BitTorrentTcpProtocol

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

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
      def readPort(bytes: ByteString): Int = {
        require(bytes.length == 2)
        BitTorrentTcpProtocol.int32FromBytes(bytes)
      }

      @tailrec
      def parsePeerString(peers: ArrayBuffer[TrackerPeer], ps: ByteString): Seq[TrackerPeer] = {
        if (ps.isEmpty) peers.result() else {
          val address = new InetSocketAddress(InetAddress.getByAddress(ps.take(4).toArray), readPort(ps.drop(4).take(2)))
          parsePeerString(peers :+ TrackerPeer(None, address), ps.drop(6))
        }
      }
      val bytes = s.asByteString
      parsePeerString(new ArrayBuffer[TrackerPeer](bytes.length / 6), bytes)
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