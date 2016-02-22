package com.karasiq.torrentstream.frontend

import com.karasiq.bootstrap.BootstrapImplicits._
import com.karasiq.bootstrap.grid.GridSystem
import com.karasiq.bootstrap.navbar.{NavigationBar, NavigationTab}
import com.karasiq.bootstrap.table.TableStyle
import com.karasiq.torrentstream.frontend.components.{TorrentInfoPanel, TorrentTable, TorrentUploadForm}
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
      val panel = new TorrentInfoPanel()
      val table = new TorrentTable(panel, 8)
      val fileForm = new TorrentUploadForm(table, panel)
      val container = div(
        GridSystem.mkRow(fileForm),
        GridSystem.mkRow(table.render(TableStyle.bordered, TableStyle.condensed, TableStyle.hover, TableStyle.striped)),
        GridSystem.mkRow(panel)
      )
      val navigationBar = NavigationBar(
        NavigationTab("Torrents", "torrents", "file", container)
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
