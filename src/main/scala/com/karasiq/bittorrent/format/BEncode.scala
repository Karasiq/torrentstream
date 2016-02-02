package com.karasiq.bittorrent.format

import org.parboiled2._

sealed trait BEncodedValue
case class BEncodedString(string: String) extends BEncodedValue
case class BEncodedNumber(number: Long) extends BEncodedValue
case class BEncodedArray(values: Seq[BEncodedValue]) extends BEncodedValue
case class BEncodedDictionary(values: Map[String, BEncodedValue]) extends BEncodedValue

class BEncode(val input: ParserInput) extends Parser {
  def Number: Rule1[Long] = rule { capture(optional('-') ~ oneOrMore(CharPredicate.Digit)) ~> ((s: String) ⇒ s.toLong) }

  def NumericValue: Rule1[BEncodedNumber] = rule { 'i' ~ Number ~ 'e' ~> BEncodedNumber }

  def StringValue: Rule1[BEncodedString] = rule { Number ~ ':' ~> (length ⇒ test(length >= 0) ~ capture(length.toInt.times(CharPredicate.All))) ~> BEncodedString }

  def ArrayValue: Rule1[BEncodedArray] = rule { 'l' ~ oneOrMore(Value) ~ 'e' ~> BEncodedArray }

  def DictionaryValue: Rule1[BEncodedDictionary] = rule { 'd' ~ oneOrMore(StringValue ~ Value ~> { (s, v) ⇒ s.string → v }) ~ 'e' ~> ((values: Seq[(String, BEncodedValue)]) ⇒ BEncodedDictionary(values.toMap)) }

  def Value: Rule1[BEncodedValue] = rule { NumericValue | StringValue | ArrayValue | DictionaryValue }

  def EncodedFile: Rule1[Seq[BEncodedValue]] = rule { oneOrMore(Value) }
}

object BEncode {
  def parse(bytes: ParserInput): Seq[BEncodedValue] = {
    new BEncode(bytes).EncodedFile.run().getOrElse(Nil)
  }
}
