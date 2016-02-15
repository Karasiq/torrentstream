package com.karasiq.bittorrent.protocol.extensions

case class ExtensionBit(index: Int, mask: Byte) {
  def set(bytes: Array[Byte]): Unit = {
    bytes(index) = (bytes(index) | mask).toByte
  }

  def test(bytes: Array[Byte]): Boolean = {
    (bytes(index) & mask) != 0
  }
}

case class PeerExtensions(fast: Boolean = true, extensionProtocol: Boolean = true) {
  import PeerExtensions.Bits._

  def toBytes: Array[Byte] = {
    val array = new Array[Byte](8)
    if (fast) fastBit.set(array)
    if (extensionProtocol) extensionProtocolBit.set(array)
    array
  }
}

object PeerExtensions {
  private[extensions] object Bits {
    val fastBit = ExtensionBit(7, 0x04)
    val extensionProtocolBit = ExtensionBit(5, 0x10)
  }

  val default: PeerExtensions = PeerExtensions()

  def fromBytes(bytes: Array[Byte]): PeerExtensions = {
    import Bits._
    assert(bytes.length == 8, "Invalid extensions bit set")
    PeerExtensions(fastBit.test(bytes), extensionProtocolBit.test(bytes))
  }
}