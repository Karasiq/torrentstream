package com.karasiq.bittorrent.protocol.extensions

import java.net.{InetAddress, InetSocketAddress}
import java.nio.ByteBuffer

import akka.util.ByteString

import com.karasiq.bittorrent.format.{BEncode, BEncodedDictionary, BEncodedNumber, BEncodedString}
import com.karasiq.bittorrent.protocol.{BitTorrentTcpProtocol, TcpMessageProtocol}

// TODO: Map(1 -> ut_pex, 6 -> ut_comment, 2 -> ut_metadata, 7 -> lt_donthave, 3 -> upload_only, 4 -> ut_holepunch)
object ExtensionProtocol {
  val defaultMessages: Map[Int, String] = Map(
    1 → "ut_pex"
  )
}

trait ExtensionProtocol extends ExtensionProtocolMessages with ExtensionProtocolTcp

trait ExtensionProtocolMessageIds {
  val EXTENDED_MESSAGE = 20
}

trait ExtensionProtocolMessages {
  case class EPHandshake(messages: Map[Int, String], version: Option[String] = None, requests: Option[Int] = None, address: Option[InetSocketAddress] = None)

  case class ExtendedMessage(id: Int, payload: ByteString)
}

trait ExtensionProtocolTcp { self: ExtensionProtocolMessages ⇒
  implicit object EpHandshakeTcpProtocol extends TcpMessageProtocol[EPHandshake] {
    override def toBytes(value: EPHandshake): ByteString = {
      val messageIds = BEncodedDictionary(value.messages.toSeq.map {
        case (id, key) ⇒
          key → BEncodedNumber(id)
      })
      val values = Vector(
        "m" → Some(messageIds),
        "v" → value.version.map(v ⇒ BEncodedString(ByteString(v))),
        "ipv6" → value.address.collect {
          case address if !address.isUnresolved && address.getAddress.getAddress.length == 16 ⇒
            BEncodedString(ByteString(address.getAddress.getAddress))
        },
        "ipv4" → value.address.collect {
          case address if !address.isUnresolved && address.getAddress.getAddress.length == 4 ⇒
            BEncodedString(ByteString(address.getAddress.getAddress))
        },
        "p" → value.address.map(a ⇒ BEncodedNumber(a.getPort)),
        "reqq" → value.requests.map(r ⇒ BEncodedNumber(r))
      )
      BEncodedDictionary(values.collect { case (k, Some(v)) ⇒ k → v })
        .toBytes
    }

    override def fromBytes(bs: ByteString): Option[EPHandshake] = {
      import com.karasiq.bittorrent.format.BEncodeImplicits._
      BEncode.parse(bs.toArray[Byte]).collectFirst {
        case BEncodedDictionary(values) ⇒
          val map = values.toMap
          val messageIds = map.get("m") match {
            case Some(BEncodedDictionary(messages)) ⇒
              messages.collect {
                case (key, BEncodedNumber(id)) ⇒
                  id.toInt → key
              }

            case _ ⇒
              Nil
          }
          val address = for {
            a <- map.bytes("ipv6").filter(_.length == 16)
              .orElse(map.bytes("ipv4").filter(_.length == 4))
              .map(a ⇒ InetAddress.getByAddress(a.toArray))
            p <- map.int("p")
          } yield new InetSocketAddress(a, p)
          EPHandshake(messageIds.toMap, map.string("v"), map.int("reqq"), address)
      }
    }
  }

  implicit object ExtendedMessageTcpProtocol extends TcpMessageProtocol[ExtendedMessage] {
    override def toBytes(value: ExtendedMessage): ByteString = {
      val buffer = ByteBuffer.allocate(1 + value.payload.length)
      buffer.put(value.id.toByte)
      buffer.put(value.payload.toArray)
      buffer.flip()
      ByteString(buffer)
    }

    override def fromBytes(bs: ByteString): Option[ExtendedMessage] = {
      if (bs.length > 1) {
        Some(ExtendedMessage(BitTorrentTcpProtocol.int32FromBytes(bs.take(1)), bs.drop(1)))
      } else {
        None
      }
    }
  }
}