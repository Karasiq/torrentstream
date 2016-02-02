package com.karasiq.bittorrent.format

import java.io.FileInputStream
import java.time.Instant

import akka.util.ByteString
import com.karasiq.bittorrent.format.BEncodeImplicits._
import org.apache.commons.io.IOUtils

import scala.util.Try

case class TorrentMetadata(announce: Option[String], announceList: Seq[Seq[String]], createdBy: Option[String], comment: Option[String], encoding: Option[String], date: Option[Instant], files: TorrentFiles)

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
              for (path <- fileData.get("path").collect(asPathSeq); length <- fileData.get("length").collect(asNumber)) yield {
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

  def decode(encoded: Seq[BEncodedValue]): Option[TorrentMetadata] = encoded match {
    case Seq(BEncodedDictionary(values)) ⇒
      val map = values.toMap
      val announce = map.string("announce")
      val announceList = map.get("announce-list").collect(asAnnounceList)
      val comment = map.string("comment")
      val createdBy = map.string("created by")
      val encoding = map.string("encoding")
      val date = map.number("creation date").map(Instant.ofEpochSecond)
      val files = map.get("info").flatMap(filesInfo)
      files.map(TorrentMetadata(announce, announceList.getOrElse(Nil), createdBy, comment, encoding, date, _))

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
