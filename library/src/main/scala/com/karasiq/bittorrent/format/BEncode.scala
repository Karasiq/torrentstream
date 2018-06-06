package com.karasiq.bittorrent.format

import scala.language.implicitConversions

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

object BEncodedString {
  implicit def fromBytes(bytes: ByteString): BEncodedString = apply(bytes)

  implicit def apply(str: String): BEncodedString = {
    new BEncodedString(ByteString(str.toCharArray.map(_.toByte)))
  }
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

object BEncodedArray {
  def apply(value1: BEncodedValue, values: BEncodedValue*): BEncodedArray = {
    apply(value1 +: values)
  }

  implicit def fromSeq(values: BEncodedValue*): BEncodedArray = {
    apply(values)
  }
}

case class BEncodedDictionary(values: Seq[(String, BEncodedValue)]) extends BEncodedValue {
  private[this] lazy val _map = values.toMap

  def toMap = _map

  override def toBytes: ByteString = {
    ByteString("d") ++ values.map { case (key, value) ⇒ BEncodedString(key).toBytes ++ value.toBytes }.fold(ByteString.empty)(_ ++ _) ++ ByteString("e")
  }
}

object BEncodedDictionary {
  def apply(value1: (String, BEncodedValue), values: (String, BEncodedValue)*): BEncodedDictionary = {
    new BEncodedDictionary(value1 +: values)
  }

  implicit def apply(map: Map[String, BEncodedValue]): BEncodedDictionary = {
    new BEncodedDictionary(map.toSeq)
  }

  implicit def toMap(dict: BEncodedDictionary): Map[String, BEncodedValue] = {
    dict.toMap
  }
}

class BEncode(val input: ParserInput) extends Parser {
  def Number: Rule1[Long] = rule { capture(optional('-') ~ oneOrMore(CharPredicate.Digit)) ~> ((s: String) ⇒ s.toLong) }

  def NumericValue: Rule1[BEncodedNumber] = rule { 'i' ~ Number ~ 'e' ~> BEncodedNumber }

  def StringValue: Rule1[BEncodedString] = rule { Number ~ ':' ~> (length ⇒ test(length >= 0) ~ capture(length.toInt.times(ANY))) ~> (BEncodedString(_: String)) }

  def ArrayValue: Rule1[BEncodedArray] = rule { 'l' ~ oneOrMore(Value) ~ 'e' ~> ((vs: Seq[BEncodedValue]) ⇒ BEncodedArray(vs)) }

  def DictionaryValue: Rule1[BEncodedDictionary] = rule { 'd' ~ oneOrMore(StringValue ~ Value ~> { (s, v) ⇒ s.utf8String → v }) ~ 'e' ~> ((vs: Seq[(String, BEncodedValue)]) ⇒ BEncodedDictionary(vs)) }

  def Value: Rule1[BEncodedValue] = rule { DictionaryValue | ArrayValue | NumericValue | StringValue }

  def EncodedFile: Rule1[Seq[BEncodedValue]] = rule { oneOrMore(Value) ~ EOI }
}

object BEncode {
  def parse(bytes: ParserInput): Seq[BEncodedValue] = {
    new BEncode(bytes).EncodedFile.run().getOrElse(Nil)
  }
}