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
import com.karasiq.bittorrent.format.TorrentMetadata
import com.karasiq.bittorrent.streams.TorrentSource
import com.karasiq.torrentstream.{FileOffset, TorrentStreamingStage}
import org.apache.commons.codec.binary.Hex
import org.apache.commons.io.FilenameUtils

class AppHandler(torrentManager: ActorRef, store: TorrentStore)(implicit actorSystem: ActorSystem, actorMaterializer: ActorMaterializer) extends AppSerializers {
  private val config = actorSystem.settings.config.getConfig("karasiq.torrentstream.streamer")
  private val bufferSize = config.getInt("buffer-size")

  // Extracts `Range` header value
  private def extractOffsets(torrent: TorrentMetadata): Directive1[Vector[(Long, Long)]] = {
    optionalHeaderValueByType[Range]().map {
      case Some(ranges) ⇒
        ranges.ranges.map(r ⇒ r.getSliceFirst().orElse(r.getOffset()).getOrElse(0L).asInstanceOf[Long] → r.getSliceLast().getOrElse(torrent.size).asInstanceOf[Long]).toVector
      case None ⇒
        Vector.empty[(Long, Long)]
    }
  }

  private def createTorrentStream(torrent: TorrentMetadata, fileName: String, offsets: Seq[(Long, Long)]): Directive[(Long, Source[ByteString, Unit])] = {
    val file = torrent.files.files.find(_.name == fileName).getOrElse(torrent.files.files.head)
    val pieces = if (offsets.nonEmpty) {
      FileOffset.absoluteOffsets(torrent, offsets.map(o ⇒ FileOffset(file, o._1, o._2)))
    } else {
      FileOffset.file(torrent, file)
    }
    val source = Source.single(torrent)
      .via(TorrentSource.dispatcher(torrentManager))
      .flatMapConcat(dsp ⇒ TorrentSource.pieces(dsp.actorRef, pieces.pieces.toVector))
      .buffer(bufferSize, OverflowStrategy.backpressure)
      .transform(() ⇒ new TorrentStreamingStage(torrent.files.pieceLength, pieces.offsets))
    tprovide(pieces.size, source)
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
                  log.info(s"Torrent uploaded: {} {}", torrent.files.name, torrent.infoHashString)
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
