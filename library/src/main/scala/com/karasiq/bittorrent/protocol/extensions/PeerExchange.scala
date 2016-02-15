package com.karasiq.bittorrent.protocol.extensions

import java.net.{InetAddress, InetSocketAddress}
import java.nio.ByteBuffer

import akka.util.ByteString
import com.karasiq.bittorrent.format.{BEncode, BEncodedDictionary, BEncodedString}
import com.karasiq.bittorrent.protocol.TcpMessageProtocol

trait PeerExchange extends PeerExchangeMessages with PeerExchangeTcp

trait PeerExchangeMessages {
  case class PeerExchangeList(addresses: Seq[InetSocketAddress])
}

trait PeerExchangeTcp { self: PeerExchangeMessages ⇒
  implicit object PeerExchangeListTcpProtocol extends TcpMessageProtocol[PeerExchangeList] {
    override def toBytes(value: PeerExchangeList): ByteString = {
      val (ipv4, ipv6) = value.addresses.partition(_.getAddress.getAddress.length == 4)
      def packAddress(address: InetSocketAddress): ByteString = {
        val port = ByteBuffer.allocate(2)
        port.putShort(address.getPort.toShort)
        port.flip()
        ByteString(address.getAddress.getAddress) ++ ByteString(port)
      }
      BEncodedDictionary(Seq(
        "dropped" → BEncodedString(ByteString.empty),
        "added" → BEncodedString(ipv4.map(packAddress).fold(ByteString.empty)(_ ++ _)),
        "added.f" → BEncodedString(ByteString(Array.fill(ipv4.length)(1.toByte))),
        "added6" → BEncodedString(ipv6.map(packAddress).fold(ByteString.empty)(_ ++ _)),
        "added6.f" → BEncodedString(ByteString(Array.fill(ipv6.length)(1.toByte)))
      )).toBytes
    }

    override def fromBytes(bs: ByteString): Option[PeerExchangeList] = {
      import com.karasiq.bittorrent.format.BEncodeImplicits._
      BEncode.parse(bs.toArray[Byte]).collectFirst {
        case BEncodedDictionary(values) ⇒
          val map = values.toMap
          val addresses = (map.byteString("added").fold(Iterator[ByteString]())(_.grouped(4 + 2)) ++ map.byteString("added6").fold(Iterator[ByteString]())(_.grouped(16 + 2))).map { bytes ⇒
            val address = bytes.dropRight(2)
            val port = BigInt((ByteString(0, 0) ++ bytes.takeRight(2)).toArray).intValue()
            new InetSocketAddress(InetAddress.getByAddress(address.toArray), port)
          }
          PeerExchangeList(addresses.toVector)
      }
    }
  }
}
