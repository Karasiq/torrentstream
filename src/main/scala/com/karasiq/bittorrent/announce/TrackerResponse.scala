package com.karasiq.bittorrent.announce

import java.net.{InetAddress, InetSocketAddress}

import akka.util.ByteString
import com.karasiq.bittorrent.format.BEncodeImplicits._
import com.karasiq.bittorrent.format._

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

case class TrackerPeer(peerId: Option[String], address: InetSocketAddress)

case class TrackerResponse(warning: Option[String], interval: Int, minInterval: Option[Int], trackerId: Option[String], complete: Int, incomplete: Int, peers: Seq[TrackerPeer])

case class TrackerError(reason: String)

object TrackerResponse {
  private def parsePeers: PartialFunction[BEncodedValue, Seq[TrackerPeer]] = {
    case BEncodedArray(peers) ⇒
      peers.collect {
        case BEncodedDictionary(peer) ⇒
          for {
            id <- Some(peer.string("peer id"))
            ip <- peer.string("ip")
            port <- peer.number("port")
          } yield TrackerPeer(id, InetSocketAddress.createUnresolved(ip, port.toInt))
      }.flatten

    case s: BEncodedString ⇒ // Compact
      def readPort(bytes: ByteString): Int = {
        require(bytes.length == 2)
        BigInt((ByteString(0, 0) ++ bytes).toArray[Byte]).intValue()
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
      case Seq(BEncodedDictionary(map)) ⇒
        val error = map.string("failure reason")
        if (error.isDefined) {
          Left(TrackerError(s"Tracker error: ${error.get}"))
        } else {
          val response = for {
            warning <- Some(map.string("warning message"))
            interval <- map.number("interval")
            minInterval <- Some(map.number("min interval"))
            trackerId <- Some(map.string("tracker id"))
            complete <- map.number("complete")
            incomplete <- map.number("incomplete")
            peers <- map.get("peers").collect(parsePeers)
          } yield TrackerResponse(warning, interval.toInt, minInterval.map(_.toInt), trackerId, complete.toInt, incomplete.toInt, peers)

          response.fold[Either[TrackerError, TrackerResponse]](Left(TrackerError("Invalid response")))(Right.apply)
        }

      case _ ⇒
        Left(TrackerError(s"Not a BEncoded value: ${str.utf8String}"))
    }
  }
}