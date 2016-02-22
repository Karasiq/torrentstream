package com.karasiq.bittorrent.protocol.extensions

import java.net.{InetAddress, InetSocketAddress}
import java.nio.ByteBuffer

import akka.util.ByteString
import com.karasiq.bittorrent.format.{BEncode, BEncodedDictionary, BEncodedString}
import com.karasiq.bittorrent.protocol.{BitTorrentTcpProtocol, TcpMessageProtocol}

trait PeerExchange extends PeerExchangeMessages with PeerExchangeTcp

trait PeerExchangeMessages {
  case class PeerExchangeList(addresses: Seq[InetSocketAddress])
}

trait PeerExchangeTcp { self: PeerExchangeMessages ⇒
  implicit object PeerExchangeListTcpProtocol extends TcpMessageProtocol[PeerExchangeList] {
    private val ipv4Length: Int = 4
    private val ipv6Length: Int = 16
    private val portLength: Int = 2

    override def toBytes(value: PeerExchangeList): ByteString = {
      val (ipv4, ipv6) = value.addresses.partition(_.getAddress.getAddress.length == ipv4Length)
      def packAddress(address: InetSocketAddress): ByteString = {
        val port = ByteBuffer.allocate(portLength)
        port.putShort(address.getPort.toShort)
        port.flip()
        ByteString(address.getAddress.getAddress) ++ ByteString(port)
      }
      BEncodedDictionary(Vector(
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
          val ipv4 = map.byteString("added").fold(Iterator[ByteString]())(_.grouped(ipv4Length + portLength))
          val ipv6 = map.byteString("added6").fold(Iterator[ByteString]())(_.grouped(ipv6Length + portLength))
          val addresses = (ipv4 ++ ipv6).map { bytes ⇒
            val address = InetAddress.getByAddress(bytes.dropRight(portLength).toArray)
            val port = BitTorrentTcpProtocol.int32FromBytes(bytes.takeRight(portLength))
            new InetSocketAddress(address, port)
          }
          PeerExchangeList(addresses.toVector)
      }
    }
  }
}
