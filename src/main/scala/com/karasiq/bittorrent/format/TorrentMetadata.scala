package com.karasiq.bittorrent.format

import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant

import akka.util.ByteString
import com.karasiq.bittorrent.format.BEncodeImplicits._
import org.apache.commons.io.IOUtils

import scala.util.Try

case class TorrentMetadata(infoHash: ByteString, announce: String, announceList: Seq[Seq[String]], createdBy: Option[String], comment: Option[String], encoding: Option[String], date: Option[Instant], files: TorrentFiles) {
  def size: Long = files.files.map(_.size).sum
}

case class TorrentFiles(name: String, pieceLength: Long, pieces: ByteString, files: Seq[TorrentFileInfo])
case class TorrentFileInfo(name: String, size: Long)

object TorrentMetadata {
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
    case BEncodedDictionary(data) ⇒
      val files = data.get("files").collect {
        case BEncodedArray(fileList) ⇒
          fileList.flatMap {
            case BEncodedDictionary(fileData) ⇒
              for (path <- fileData.get("path").collect(asPathSeq); length <- fileData.number("length")) yield {
                TorrentFileInfo(path, length)
              }

            case _ ⇒
              Nil
          }
      }
      val length = data.number("length")
      val name = data.string("name")
      val pieceLength = data.number("piece length")
      val pieces = data.get("pieces").map(_.asByteString)
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
          length <- length
          pieceLength <- pieceLength
          pieces <- pieces
        } yield TorrentFiles(name, pieceLength, pieces, Seq(TorrentFileInfo(name, length)))
      }

    case _ ⇒
      None
  }

  private def infoHash(v: BEncodedValue): ByteString = {
    val md = MessageDigest.getInstance("SHA-1")
    ByteString(md.digest(v.toBytes.toArray[Byte]))
  }

  def decode(encoded: Seq[BEncodedValue]): Option[TorrentMetadata] = encoded match {
    case Seq(BEncodedDictionary(values)) ⇒
      val map = values.toMap
      val announce = map.string("announce")
      val announceList = map.get("announce-list").collect(asAnnounceList)
      val comment = map.string("comment")
      val createdBy = map.string("created by")
      val encoding = map.string("encoding")
      val date = map.number("creation date").map(Instant.ofEpochSecond)
      val info = map.get("info")
      for (announce <- announce.orElse(announceList.flatMap(_.headOption.flatMap(_.headOption))); infoHash <- info.map(infoHash); files <- info.flatMap(filesInfo)) yield {
        TorrentMetadata(infoHash, announce, announceList.getOrElse(Nil), createdBy, comment, encoding, date, files)
      }

    case _ ⇒
      None
  }

  def apply(data: ByteString): Option[TorrentMetadata] = {
    this.decode(BEncode.parse(data.toArray[Byte]))
  }

  def fromFile(file: String): Option[TorrentMetadata] = {
    val inputStream = new FileInputStream(file)
    val result = Try(this.decode(BEncode.parse(IOUtils.toByteArray(inputStream))))
    IOUtils.closeQuietly(inputStream)
    result.getOrElse(None)
  }
}
