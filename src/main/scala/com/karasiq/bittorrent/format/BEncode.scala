package com.karasiq.bittorrent.format

import akka.util.ByteString
import org.parboiled2._

sealed trait BEncodedValue {
  def toBytes: ByteString
}
case class BEncodedString(bytes: ByteString) extends BEncodedValue {
  override def toBytes: ByteString = {
    ByteString(bytes.length.toString + ":") ++ bytes
  }

  def utf8String: String = bytes.utf8String
}
case class BEncodedNumber(number: Long) extends BEncodedValue {
  override def toBytes: ByteString = {
    ByteString("i") ++ ByteString(number.toString) ++ ByteString("e")
  }
}
case class BEncodedArray(values: Seq[BEncodedValue]) extends BEncodedValue {
  override def toBytes: ByteString = {
    ByteString("l") ++ values.map(_.toBytes).fold(ByteString.empty)(_ ++ _) ++ ByteString("e")
  }
}
case class BEncodedDictionary(values: Seq[(String, BEncodedValue)]) extends BEncodedValue {
  override def toBytes: ByteString = {
    ByteString("d") ++ values.map(kv ⇒ BEncodedString(ByteString(kv._1.toCharArray.map(_.toByte))).toBytes ++ kv._2.toBytes).fold(ByteString.empty)(_ ++ _) ++ ByteString("e")
  }
}

class BEncode(val input: ParserInput) extends Parser {
  def Number: Rule1[Long] = rule { capture(optional('-') ~ oneOrMore(CharPredicate.Digit)) ~> ((s: String) ⇒ s.toLong) }

  def NumericValue: Rule1[BEncodedNumber] = rule { 'i' ~ Number ~ 'e' ~> BEncodedNumber }

  def StringValue: Rule1[BEncodedString] = rule { Number ~ ':' ~> (length ⇒ test(length >= 0) ~ capture(length.toInt.times(ANY))) ~>
    ((str: String) ⇒ BEncodedString(ByteString(str.toCharArray.map(_.toByte))))
  }

  def ArrayValue: Rule1[BEncodedArray] = rule { 'l' ~ oneOrMore(Value) ~ 'e' ~> BEncodedArray }

  def DictionaryValue: Rule1[BEncodedDictionary] = rule { 'd' ~ oneOrMore(StringValue ~ Value ~> { (s, v) ⇒ s.utf8String → v }) ~ 'e' ~> BEncodedDictionary }

  def Value: Rule1[BEncodedValue] = rule { DictionaryValue | ArrayValue | NumericValue | StringValue }

  def EncodedFile: Rule1[Seq[BEncodedValue]] = rule { oneOrMore(Value) ~ EOI }
}

object BEncode {
  def parse(bytes: ParserInput): Seq[BEncodedValue] = {
    new BEncode(bytes).EncodedFile.run().getOrElse(Nil)
  }
}

object BEncodeImplicits {
  implicit class BEncodedValueOps(val value: BEncodedValue) extends AnyVal {
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
        throw new IllegalArgumentException
    }

    def asNumber: Long = value match {
      case BEncodedNumber(num) ⇒
        num

      case _ ⇒
        throw new IllegalArgumentException
    }

    def asByteString: ByteString = value match {
      case BEncodedString(bs) ⇒
        bs

      case _ ⇒
        throw new IllegalArgumentException
    }
  }

  implicit class BEncodedDictOps(val dict: Map[String, BEncodedValue]) extends AnyVal {
    def string(key: String): Option[String] = dict.get(key).collect {
      case BEncodedString(bs) ⇒
        bs.utf8String
    }

    def byteString(key: String): Option[ByteString] = dict.get(key).collect {
      case BEncodedString(bs) ⇒
        bs
    }

    def number(key: String): Option[Long] = dict.get(key).collect {
      case BEncodedNumber(num) ⇒
        num
    }

    def array(key: String): Seq[BEncodedValue] = dict.get(key).map(_.asArray).getOrElse(Nil)

    def dict(key: String): Map[String, BEncodedValue] = dict.get(key).map(_.asDict).getOrElse(Map.empty)
  }
}






























