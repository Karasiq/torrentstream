package com.karasiq.torrentstream.frontend.components

import scala.scalajs.js.URIUtils
import scalatags.JsDom.all._

import rx._

import com.karasiq.bootstrap.{Bootstrap, BootstrapComponent}
import com.karasiq.bootstrap.BootstrapImplicits._
import com.karasiq.torrentstream.shared.TorrentInfo

final class TorrentInfoPanel(implicit ctx: Ctx.Owner) extends BootstrapComponent {
  val torrent: Var[Option[TorrentInfo]] = Var(None)

  override def render(md: Modifier*): Modifier = Rx[Frag] {
    if (torrent().isEmpty) {
      ""
    } else {
      val info = this.torrent().get
      Bootstrap.well(
        p(strong("Name: "), info.name),
        p(strong("Info hash: "), info.infoHash),
        p(strong("Comment: "), if (info.comment.startsWith("https://") || info.comment.startsWith("http://")) a(href := info.comment, info.comment) else info.comment),
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
}
