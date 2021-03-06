package com.karasiq.bittorrent.dht

import java.security.MessageDigest

import scala.language.implicitConversions
import scala.util.Random

import akka.util.ByteString

import com.karasiq.bittorrent.format.BEncodedString

final case class NodeId(bytes: ByteString) extends AnyVal {
  def encoded = BEncodedString(bytes)

  def toBigInt: BigInt = BigInt(1, bytes.toArray)

  def distanceTo(id2: NodeId): BigInt = {
    (toBigInt ^ id2.toBigInt).abs
  }

  override def toString: String = {
    val hexString = toBigInt.toString(16)
    "NodeId(" + hexString + ")"
  }
}

object NodeId {
  val NodeIdSize = 20

  def generate(): NodeId = {
    val md = MessageDigest.getInstance("SHA-1")
    val randomBytes = new Array[Byte](256)
    Random.nextBytes(randomBytes)
    NodeId(ByteString(md.digest(randomBytes)))
  }

  implicit def fromBytes(bytes: ByteString): NodeId = {
    NodeId(bytes)
  }

  implicit def fromBigInt(bigInt: BigInt): NodeId = {
    val byteArray = ByteString(bigInt.toByteArray)
    NodeId(byteArray)
  }
}
