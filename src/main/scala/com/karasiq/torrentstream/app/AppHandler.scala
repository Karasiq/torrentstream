package com.karasiq.torrentstream.app

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.headers.{ContentDispositionTypes, Range, `Content-Disposition`}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.ByteString
import boopickle.Default._
import com.karasiq.bittorrent.format.{TorrentFileInfo, TorrentMetadata, TorrentPiece}
import com.karasiq.bittorrent.streams.TorrentSource
import org.apache.commons.codec.binary.Hex
import org.apache.commons.io.FilenameUtils

class AppHandler(torrentManager: ActorRef, store: TorrentStore)(implicit actorSystem: ActorSystem, actorMaterializer: ActorMaterializer) extends AppSerializers {
  // Extracts `Range` header value
  private def extractOffsets(torrent: TorrentMetadata): Directive1[Vector[(Long, Long)]] = {
    optionalHeaderValueByType[Range]().map {
      case Some(ranges) ⇒
        ranges.ranges.map(r ⇒ r.getSliceFirst().getOrElse(0L).asInstanceOf[Long] → r.getSliceLast().getOrElse(torrent.size).asInstanceOf[Long]).toVector
      case None ⇒
        Vector.empty[(Long, Long)]
    }
  }

  private def createTorrentStream(torrent: TorrentMetadata, file: String, offsets: Seq[(Long, Long)]): Directive[(Long, Source[ByteString, Unit])] = {
    def pieceInRange(index: Int, ranges: Seq[(Long, Long)]): Boolean = {
      val pieceStart = index.toLong * torrent.files.pieceLength
      val pieceEnd = pieceStart + torrent.files.pieceLength
      ranges.exists {
        case (start, end) ⇒
          start <= pieceEnd && end >= pieceStart
      }
    }

    val pieces = TorrentPiece.sequence(torrent.files).zipWithIndex.collect {
      case (piece @ TorrentPiece(_, _, TorrentFileInfo(name, _)), index) if name == file ⇒
        piece → index
    }
    val fileOffset = pieces.headOption.map(_._2.toLong * torrent.files.pieceLength).getOrElse(0L)

    val absoluteOffsets = offsets.map(ofs ⇒ (ofs._1 + fileOffset) → (ofs._2 + fileOffset))

    val size = if (absoluteOffsets.isEmpty) torrent.size else absoluteOffsets.foldLeft(0L) {
      case (result, (start, end)) ⇒
        result + (end - start)
    }

    val selectedPieces = pieces.filter(p ⇒ absoluteOffsets.isEmpty || pieceInRange(p._2, absoluteOffsets)).toVector
    val source = Source.single(torrent)
      .via(TorrentSource.dispatcher(torrentManager))
      .flatMapConcat(dsp ⇒ TorrentSource.pieces(dsp.actorRef, selectedPieces))
      .buffer(100, OverflowStrategy.backpressure)
      .map(_.data)
//      .transform(() ⇒ new TorrentStreamingStage(torrent.files.pieceLength, if (absoluteOffsets.nonEmpty) absoluteOffsets else {
//        val start = pieces.head._2.toLong * torrent.files.pieceLength
//        val end = pieces.last._2.toLong * torrent.files.pieceLength + pieces.last._1.size
//        Seq(start → end)
//      }))

    tprovide(size, source)
  }

  val route = {
    get {
      (path("stream") & parameters('hash, 'file)) { (hash, file) ⇒
        val torrentId = ByteString(Hex.decodeHex(hash.toCharArray))
        validate(store.contains(torrentId), "Invalid torrent id") {
          val torrent = store(torrentId)
          extractOffsets(torrent) { offsets ⇒
            createTorrentStream(torrent, file, offsets) { (size, stream) ⇒
              respondWithHeader(`Content-Disposition`(ContentDispositionTypes.attachment, Map("filename" → FilenameUtils.getName(file)))) {
                complete(if (offsets.isEmpty) StatusCodes.OK else StatusCodes.PartialContent, HttpEntity(ContentTypes.NoContentType, size, stream))
              }
            }
          }
        }
      } ~
      pathEndOrSingleSlash {
        getFromResource("webapp/index.html")
      } ~
      getFromResourceDirectory("webapp")
    } ~
      post {
        (path("upload") & entity(as[ByteString])) { data ⇒
          TorrentMetadata(data) match {
            case Some(torrent) ⇒
              onSuccess(store.add(torrent)) {
                extractLog { log ⇒
                  log.info(s"Torrent uploaded: {} {}", torrent.name, torrent.infoHashString)
                  complete(StatusCodes.OK, TorrentInfo.fromTorrent(torrent))
                }
              }

            case None ⇒
              complete(StatusCodes.BadRequest, "Invalid torrent file")
          }
        }
      }
  }
}
