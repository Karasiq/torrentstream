package com.karasiq.bittorrent.format

import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant

import akka.util.ByteString
import com.karasiq.bittorrent.format.BEncodeImplicits._
import org.apache.commons.codec.binary.Hex
import org.apache.commons.io.IOUtils

case class Torrent(infoHash: ByteString, announce: String, announceList: Seq[Seq[String]], createdBy: Option[String], comment: Option[String], encoding: Option[String], date: Option[Instant], data: TorrentFiles) {
  val size: Long = data.files.map(_.size).sum
  val pieces: Int = data.pieces.length / 20

  def infoHashString: String = {
    Hex.encodeHexString(infoHash.toArray).toUpperCase
  }
}

case class TorrentFiles(name: String, pieceLength: Int, pieces: ByteString, files: Seq[TorrentFile])
case class TorrentFile(name: String, size: Long)

object Torrent {
  private def asAnnounceList: PartialFunction[BEncodedValue, Seq[Seq[String]]] = {
    case BEncodedArray(values) ⇒
      values.collect {
        case BEncodedArray(urls) ⇒
          urls.map(_.asString)
      }
  }

  private def asPathSeq: PartialFunction[BEncodedValue, String] = {
    case BEncodedArray(values) ⇒
      values.map(_.asString).mkString("/")
  }

  private def filesInfo(v: BEncodedValue): Option[TorrentFiles] = v match {
    case BEncodedDictionary(seq) ⇒
      val data = seq.toMap
      val files = data.get("files").collect {
        case BEncodedArray(fileList) ⇒
          fileList.flatMap {
            case BEncodedDictionary(fileValues) ⇒
              val fileData = fileValues.toMap
              for (path <- fileData.get("path").collect(asPathSeq); length <- fileData.long("length")) yield {
                TorrentFile(path, length)
              }

            case _ ⇒
              Nil
          }
      }
      val name = data.string("name")
      val pieceLength = data.int("piece length")
      val pieces = data.byteString("pieces")
      if (files.isDefined) {
        for {
          name <- name
          pieceLength <- pieceLength
          pieces <- pieces
          files <- files
        } yield TorrentFiles(name, pieceLength, pieces, files)
      } else {
        for {
          name <- name
          length <- data.long("length")
          pieceLength <- pieceLength
          pieces <- pieces
        } yield TorrentFiles(name, pieceLength, pieces, Vector(TorrentFile(name, length)))
      }

    case _ ⇒
      None
  }

  private def infoHash(v: BEncodedValue): ByteString = {
    val md = MessageDigest.getInstance("SHA-1")
    ByteString(md.digest(v.toBytes.toArray[Byte]))
  }

  def decode(encoded: Seq[BEncodedValue]): Option[Torrent] = encoded match {
    case Seq(BEncodedDictionary(values)) ⇒
      val map = values.toMap
      val announce = map.string("announce")
      val announceList = map.get("announce-list").collect(asAnnounceList)
      val comment = map.string("comment")
      val createdBy = map.string("created by")
      val encoding = map.string("encoding")
      val date = map.long("creation date").map(Instant.ofEpochSecond)
      val info = map.get("info")
      for (announce <- announce.orElse(announceList.flatMap(_.headOption.flatMap(_.headOption))); infoHash <- info.map(infoHash); files <- info.flatMap(filesInfo)) yield {
        Torrent(infoHash, announce, announceList.getOrElse(Nil), createdBy, comment, encoding, date, files)
      }

    case _ ⇒
      None
  }

  def decode(data: ByteString): Option[Torrent] = {
    this.decode(BEncode.parse(data.toArray[Byte]))
  }

  def fromBytes(data: ByteString): Torrent = {
    this.decode(data).getOrElse(throw new IllegalArgumentException("Invalid torrent format"))
  }

  def fromFile(file: String): Torrent = {
    val inputStream = new FileInputStream(file)
    try {
      this.fromBytes(ByteString(IOUtils.toByteArray(inputStream)))
    } finally {
      IOUtils.closeQuietly(inputStream)
    }
  }

  def apply(data: ByteString): Torrent = this.fromBytes(data)

  def apply(data: Array[Byte]): Torrent = this.fromBytes(ByteString(data))

  def apply(data: ByteBuffer): Torrent = this.fromBytes(ByteString(data))
}
