package com.karasiq.torrentstream.frontend

import boopickle.Default._
import com.karasiq.bootstrap.BootstrapImplicits._
import com.karasiq.bootstrap.alert.{Alert, AlertStyle}
import com.karasiq.bootstrap.form.{Form, FormInput}
import com.karasiq.bootstrap.{Bootstrap, BootstrapHtmlComponent}
import org.scalajs
import org.scalajs.dom
import org.scalajs.dom.ext.Ajax
import org.scalajs.dom.raw.XMLHttpRequest
import rx.Var

import scala.language.implicitConversions
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js.typedarray.{ArrayBuffer, TypedArrayBuffer}
import scala.util.{Failure, Success}
import scalatags.JsDom.all._

class TorrentUploadForm extends BootstrapHtmlComponent[scalajs.dom.html.Form] {
  private def readBinary[T: Pickler](xhr: XMLHttpRequest): T = {
    Unpickle[T].fromBytes(TypedArrayBuffer.wrap(xhr.response.asInstanceOf[ArrayBuffer]))
  }

  override def renderTag(md: Modifier*): RenderedTag = {
    val torrent: Var[Option[TorrentStoreEntry]] = Var(None)
    val fileForm = FormInput.file("Torrent file", onchange := Bootstrap.jsInput { fileForm ⇒
      Ajax.post("/upload", fileForm.files.head, timeout = 10000, responseType = "arraybuffer")
        .map(readBinary[TorrentStoreEntry])
        .onComplete {
          case Success(data) ⇒
            // TODO: Torrent info panel
            torrent.update(Some(data))
            dom.alert(data.toString)

          case Failure(exc) ⇒
            fileForm.parentNode.insertBefore(Alert(AlertStyle.danger, strong(s"File submission error: $exc"), ul(exc.getStackTrace.map(e ⇒ li(i(e.toString))))).render, fileForm)
        }
    })
    Form(fileForm)
  }
}
