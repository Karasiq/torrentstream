package com.karasiq.bittorrent.format

import akka.util.ByteString

object BEncodeImplicits {
  implicit class BEncodedValueOps(private val value: BEncodedValue) extends AnyVal {
    def asDict: Map[String, BEncodedValue] = value match {
      case BEncodedDictionary(values) ⇒
        values.toMap

      case _ ⇒
        Map.empty
    }

    def asArray: Seq[BEncodedValue] = value match {
      case BEncodedArray(values) ⇒
        values

      case _ ⇒
        Nil
    }

    def asString: String = value match {
      case BEncodedString(bs) ⇒
        bs.utf8String

      case _ ⇒
        throw new IllegalArgumentException(s"Not a string: $value")
    }

    def asNumber: Long = value match {
      case BEncodedNumber(num) ⇒
        num

      case _ ⇒
        throw new IllegalArgumentException(s"Not a number: $value")
    }

    def asByteString: ByteString = value match {
      case BEncodedString(bs) ⇒
        bs

      case _ ⇒
        throw new IllegalArgumentException(s"Not a bytes: $value")
    }
  }

  implicit class BEncodedDictOps(private val dict: Map[String, BEncodedValue]) extends AnyVal {
    def string(key: String): Option[String] = dict.get(key).collect {
      case BEncodedString(bs) ⇒
        bs.utf8String
    }

    def bytes(key: String): Option[ByteString] = dict.get(key).collect {
      case BEncodedString(bs) ⇒
        bs
    }

    def long(key: String): Option[Long] = dict.get(key).collect {
      case BEncodedNumber(num) ⇒
        num
    }

    def int(key: String): Option[Int] = long(key).map(_.toInt)
    def array(key: String): Seq[BEncodedValue] = dict.get(key).map(_.asArray).getOrElse(Nil)
    def dict(key: String): Map[String, BEncodedValue] = dict.get(key).map(_.asDict).getOrElse(Map.empty)
  }
}