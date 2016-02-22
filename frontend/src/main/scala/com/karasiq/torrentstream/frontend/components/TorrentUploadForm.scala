package com.karasiq.torrentstream.frontend.components

import com.karasiq.bootstrap.BootstrapImplicits._
import com.karasiq.bootstrap.alert.{Alert, AlertStyle}
import com.karasiq.bootstrap.form.{Form, FormInput}
import com.karasiq.bootstrap.{Bootstrap, BootstrapHtmlComponent}
import com.karasiq.torrentstream.frontend.TorrentStreamApi
import org.scalajs
import rx._

import scala.language.implicitConversions
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}
import scalatags.JsDom.all._

final class TorrentUploadForm(table: TorrentTable, panel: TorrentInfoPanel)(implicit ctx: Ctx.Owner) extends BootstrapHtmlComponent[scalajs.dom.html.Element] {
  override def renderTag(md: Modifier*): RenderedTag = {
    val fileForm = FormInput.file("Torrent file", onchange := Bootstrap.jsInput { fileForm ⇒
      TorrentStreamApi.upload(fileForm.files.head).onComplete {
        case Success(data) ⇒
          table.update()
          panel.torrent.update(Some(data))

        case Failure(exc) ⇒
          fileForm.parentNode.insertBefore(Alert(AlertStyle.danger, strong(s"File submission error: $exc"), ul(exc.getStackTrace.map(e ⇒ li(i(e.toString))))).render, fileForm)
      }
    })

    Form(fileForm)
  }
}
