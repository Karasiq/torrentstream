package com.karasiq.bittorrent.protocol

import scala.util.Try

import akka.util.ByteString

trait TcpMessageWriter[T] {
  def toBytes(value: T): ByteString

  implicit class ImplicitTcpMessageToBytes(message: T) {
    def toBytes: ByteString = TcpMessageWriter.this.toBytes(message)
  }
}

trait TcpMessageReader[T] {
  def fromBytes(bs: ByteString): Option[T]

  def unapply(bs: ByteString): Option[T] = {
    Try(fromBytes(bs)).toOption.flatten
  }
}

trait TcpMessageProtocol[T] extends TcpMessageReader[T] with TcpMessageWriter[T]