package com.karasiq.torrentstream.frontend.components

import com.karasiq.bootstrap.BootstrapHtmlComponent
import com.karasiq.bootstrap.BootstrapImplicits._
import com.karasiq.bootstrap.grid.GridSystem
import com.karasiq.bootstrap.table.TableStyle
import org.scalajs.dom
import rx._

import scalatags.JsDom.all._

final class TorrentManagementPanel(implicit ctx: Ctx.Owner) extends BootstrapHtmlComponent[dom.html.Div] {
  override def renderTag(md: Modifier*): RenderedTag = {
    val panel = new TorrentInfoPanel()
    val table = new TorrentTable(panel, 8)
    val fileForm = new TorrentUploadForm(table, panel)
    div(
      GridSystem.mkRow(fileForm),
      GridSystem.mkRow(table.render(TableStyle.bordered, TableStyle.condensed, TableStyle.hover, TableStyle.striped)),
      GridSystem.mkRow(panel),
      md
    )
  }
}