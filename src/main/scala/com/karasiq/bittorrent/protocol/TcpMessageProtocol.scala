package com.karasiq.bittorrent.protocol

import akka.util.ByteString

trait TcpMessageWriter[T] {
  def toBytes(value: T): ByteString
}

trait TcpMessageReader[T] {
  def fromBytes(bs: ByteString): Option[T]
}

trait TcpMessageProtocol[T] extends TcpMessageReader[T] with TcpMessageWriter[T]