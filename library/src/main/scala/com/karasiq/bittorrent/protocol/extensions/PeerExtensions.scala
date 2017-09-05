package com.karasiq.bittorrent.protocol.extensions

import akka.util.ByteString

case class ExtensionBit(index: Int, mask: Byte) {
  def set(bytes: Array[Byte]): Unit = {
    bytes(index) = (bytes(index) | mask).toByte
  }

  def test(bytes: Array[Byte]): Boolean = {
    (bytes(index) & mask) != 0
  }
}

case class PeerExtensions(fast: Boolean = true, extensionProtocol: Boolean = true) {
  import PeerExtensions.{Bits, BitSetBytes}

  def toByteArray: Array[Byte] = {
    val byteArray = new Array[Byte](BitSetBytes)
    if (fast) Bits.fast.set(byteArray)
    if (extensionProtocol) Bits.extensionProtocol.set(byteArray)
    byteArray
  }

  def toBytes: ByteString = {
    ByteString(toByteArray)
  }

  override def toString: String = {
    val features = Seq(
      (fast, "Fast"),
      (extensionProtocol, "Extension protocol")
    )
    val featuresString = (for ((enabled, name) ← features if enabled) yield name).mkString(", ")
    if (featuresString.isEmpty) "PeerExtensions.empty" else "PeerExtensions(" + featuresString + ")"
  }
}

object PeerExtensions {
  private[extensions] val BitSetBytes = 8

  private[extensions] object Bits {
    val fast = ExtensionBit(7, 0x04)
    val extensionProtocol = ExtensionBit(5, 0x10)
  }

  val empty = new PeerExtensions()

  def fromByteArray(bytes: Array[Byte]): PeerExtensions = {
    assert(bytes.length == BitSetBytes, "Invalid extensions bit set")
    PeerExtensions(
      Bits.fast.test(bytes),
      Bits.extensionProtocol.test(bytes)
    )
  }

  def fromBytes(bytes: ByteString): PeerExtensions = {
    fromByteArray(bytes.toArray)
  }
}