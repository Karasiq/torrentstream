package com.karasiq.bittorrent.format

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant

import akka.util.ByteString
import org.apache.commons.io.FileUtils

import com.karasiq.bittorrent.format.Torrent._

trait TorrentParser {
  def tryDecode(encodedTorrent: Seq[BEncodedValue]): Option[Torrent]
  def createInfoHash(infoValue: BEncodedValue): ByteString

  def tryDecode(data: ByteString): Option[Torrent] = {
    tryDecode(BEncode.parse(data.toArray[Byte]))
  }

  def fromBytes(data: ByteString): Torrent = {
    tryDecode(data).getOrElse(throw new IllegalArgumentException("Invalid torrent format"))
  }

  def fromByteArray(data: Array[Byte]): Torrent = {
    fromBytes(ByteString(data))
  }

  def fromByteBuffer(data: ByteBuffer): Torrent = {
    fromBytes(ByteString(data))
  }

  def fromFile(file: String): Torrent = {
    val byteArray = FileUtils.readFileToByteArray(new File(file))
    fromBytes(ByteString(byteArray))
  }
}

trait DefaultTorrentParser extends TorrentParser {
  import com.karasiq.bittorrent.format.BEncodeImplicits._

  def tryDecode(encoded: Seq[BEncodedValue]): Option[Torrent] = encoded match {
    case Seq(BEncodedDictionary(values)) ⇒
      val map = values.toMap
      val announce = map.string("announce")
      val announceList = map.get("announce-list").collect(toAnnounceList)
      val comment = map.string("comment")
      val createdBy = map.string("created by")
      val encoding = map.string("encoding")
      val date = map.long("creation date").map(Instant.ofEpochSecond)
      val info = map.get("info")
      for {
        announce ← announce.orElse(announceList.flatMap(_.headOption.flatMap(_.headOption)))
        infoHash ← info.map(createInfoHash)
        files ← info.flatMap(toFileList)
      } yield Torrent(infoHash, announce, announceList.getOrElse(Nil), createdBy, comment, encoding, date, files)

    case _ ⇒
      None
  }

  def createInfoHash(v: BEncodedValue): ByteString = {
    val md = MessageDigest.getInstance(PieceHashAlgorithm)
    ByteString(md.digest(v.toBytes.toArray[Byte]))
  }

  private[this] def toAnnounceList: PartialFunction[BEncodedValue, Seq[Seq[String]]] = {
    case BEncodedArray(values) ⇒
      values.collect {
        case BEncodedArray(urls) ⇒
          urls.map(_.asString)
      }
  }

  private[this] def toFilePath: PartialFunction[BEncodedValue, String] = {
    case BEncodedArray(values) ⇒
      values.map(_.asString).mkString("/")
  }

  private[this] def toFileList(v: BEncodedValue): Option[TorrentContent] = v match {
    case BEncodedDictionary(seq) ⇒
      val data = seq.toMap
      val files = data.get("files").collect {
        case BEncodedArray(fileList) ⇒
          fileList.flatMap {
            case BEncodedDictionary(fileValues) ⇒
              val map = fileValues.toMap
              for {
                path ← map.get("path").collect(toFilePath)
                length ← map.long("length")
              } yield TorrentFile(path, length)

            case _ ⇒
              Nil
          }
      }
      val name = data.string("name")
      val pieceLength = data.int("piece length")
      val pieces = data.bytes("pieces")
      if (files.isDefined) {
        for {
          name ← name
          pieceLength ← pieceLength
          pieces ← pieces
          files ← files
        } yield TorrentContent(name, pieceLength, pieces, files)
      } else {
        for {
          name ← name
          length ← data.long("length")
          pieceLength ← pieceLength
          pieces ← pieces
        } yield TorrentContent(name, pieceLength, pieces, Vector(TorrentFile(name, length)))
      }

    case _ ⇒
      None
  }
}
