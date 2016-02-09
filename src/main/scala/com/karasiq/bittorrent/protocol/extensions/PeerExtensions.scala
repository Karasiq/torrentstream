package com.karasiq.bittorrent.protocol.extensions

// TODO: Extension message protocol
case class PeerExtensions(fast: Boolean = true) {
  def toBytes: Array[Byte] = {
    val array = new Array[Byte](8)
    if (fast) array(7) = (array(7) | 4).toByte
    array
  }
}

object PeerExtensions {
  val default: PeerExtensions = PeerExtensions()

  def fromBytes(bytes: Array[Byte]): PeerExtensions = {
    assert(bytes.length == 8, "Invalid extensions bit set")
    PeerExtensions(fast = (bytes(7) & 4) != 0)
  }
}