package com.karasiq.torrentstream.frontend

import com.karasiq.bootstrap.BootstrapImplicits._
import com.karasiq.bootstrap.grid.GridSystem
import com.karasiq.bootstrap.navbar.{NavigationBar, NavigationTab}
import org.scalajs.dom
import org.scalajs.jquery.jQuery
import rx._

import scala.scalajs.js.JSApp
import scala.scalajs.js.annotation.JSExport
import scalatags.JsDom.all._

@JSExport
object TorrentStreamFrontend extends JSApp {
  private implicit val context = implicitly[Ctx.Owner]

  @JSExport
  override def main(): Unit = {
    jQuery(() ⇒ {
      val navigationBar = NavigationBar(
        NavigationTab("Torrents", "torrents", "file", new TorrentUploadForm)
      )
      val body = dom.document.body
      body.appendChild(navigationBar.navbar("TorrentStream").render)
      body.appendChild {
        GridSystem.container(id := "main-container")(
          GridSystem.mkRow(navigationBar.content)
        ).render
      }
    })
  }
}
