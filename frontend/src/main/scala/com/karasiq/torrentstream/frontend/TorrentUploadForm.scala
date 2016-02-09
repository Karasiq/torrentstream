package com.karasiq.torrentstream.frontend

import boopickle.Default._
import com.karasiq.bootstrap.BootstrapImplicits._
import com.karasiq.bootstrap.alert.{Alert, AlertStyle}
import com.karasiq.bootstrap.form.{Form, FormInput}
import com.karasiq.bootstrap.grid.GridSystem
import com.karasiq.bootstrap.{Bootstrap, BootstrapHtmlComponent}
import org.scalajs
import org.scalajs.dom.ext.Ajax
import org.scalajs.dom.raw.XMLHttpRequest
import rx._

import scala.language.implicitConversions
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js.URIUtils
import scala.scalajs.js.typedarray.{ArrayBuffer, TypedArrayBuffer}
import scala.util.{Failure, Success}
import scalatags.JsDom.all._

// TODO: M3U generation, uploaded torrent catalog
class TorrentUploadForm(implicit ctx: Ctx.Owner) extends BootstrapHtmlComponent[scalajs.dom.html.Element] {
  private def readBinary[T: Pickler](xhr: XMLHttpRequest): T = {
    Unpickle[T].fromBytes(TypedArrayBuffer.wrap(xhr.response.asInstanceOf[ArrayBuffer]))
  }

  override def renderTag(md: Modifier*): RenderedTag = {
    val torrent: Var[Option[TorrentData]] = Var(None)
    val torrentPanel = Rx {
      if (torrent().isEmpty) span() else {
        val info = torrent().get
        Bootstrap.well(
          h2("Torrent file info"),
          p(strong("Name: "), info.name),
          p(strong("Info hash: "), info.infoHash),
          p(strong("Comment: "), info.comment),
          p(strong("Created by: "), info.createdBy),
          p(strong("Size: "), f"${info.size / 1024.0 / 1024.0}%.2f MB"),
          p(strong("Announce URLs"), ul(info.announceList.flatten.map(url ⇒ li(url)))),
          p(strong("Files"), ul(for((file, size) <- info.files) yield {
            val link = s"/stream?hash=${info.infoHash}&file=${URIUtils.encodeURIComponent(file)}"
            val name = file.split("[\\\\/]").last
            val download = "download".attr
            li(a(href := link, target := "_blank", download := name, file, f" (${size / 1024.0 / 1024.0}%.2f MB)"))
          }))
        )
      }
    }
    val fileForm = FormInput.file("Torrent file", onchange := Bootstrap.jsInput { fileForm ⇒
      Ajax.post("/upload", fileForm.files.head, timeout = 10000, responseType = "arraybuffer")
        .map(readBinary[TorrentData])
        .onComplete {
          case Success(data) ⇒
            torrent.update(Some(data))

          case Failure(exc) ⇒
            fileForm.parentNode.insertBefore(Alert(AlertStyle.danger, strong(s"File submission error: $exc"), ul(exc.getStackTrace.map(e ⇒ li(i(e.toString))))).render, fileForm)
        }
    })

    div(
      GridSystem.mkRow(Form(fileForm)),
      GridSystem.mkRow(torrentPanel)
    )
  }
}
