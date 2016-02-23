package com.karasiq.torrentstream.frontend.components

import com.karasiq.bootstrap.BootstrapImplicits._
import com.karasiq.bootstrap.buttons.{Button, ButtonGroup, ButtonGroupSize, ButtonStyle}
import com.karasiq.bootstrap.grid.GridSystem
import com.karasiq.bootstrap.icons.FontAwesome
import com.karasiq.bootstrap.table.{PagedTable, TableRow}
import com.karasiq.bootstrap.{Bootstrap, BootstrapComponent}
import com.karasiq.torrentstream.frontend.{TorrentInfo, TorrentStreamApi}
import rx._

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}
import scalatags.JsDom.all._

final class TorrentTable(panel: TorrentInfoPanel, perPage: Int)(implicit ctx: Ctx.Owner) extends BootstrapComponent {
  private val table = new PagedTable {
    override val currentPage: Var[Int] = Var(1)

    val torrentsCount = Var(0)

    override val pages: Rx[Int] = Rx {
      val length = torrentsCount()
      if (length == 0) {
        1
      } else if (length % perPage == 0) {
        length / perPage
      } else {
        length / perPage + 1
      }
    }

    val torrentInfo = Var(Vector.empty[TorrentInfo])

    currentPage.foreach(_ ⇒ update())

    override val content: Rx[Seq[TableRow]] = Rx {
      torrentInfo().map { info ⇒
        val buttons = ButtonGroup(ButtonGroupSize.small,
          Button(ButtonStyle.primary)("file-text".fontAwesome(FontAwesome.fixedWidth), onclick := Bootstrap.jsClick(_ ⇒ panel.torrent.update(Some(info)))),
          Button(ButtonStyle.danger)("trash".fontAwesome(FontAwesome.fixedWidth), onclick := Bootstrap.jsClick { _ ⇒
            TorrentStreamApi.remove(info.infoHash).onSuccess {
              case _ ⇒
                if (panel.torrent.now.contains(info)) {
                  panel.torrent.update(None)
                }
                update()
            }
          })
        )
        TableRow(
          Seq[Modifier](
            Seq[Modifier](GridSystem.col(1), textAlign.center, buttons),
            Seq[Modifier](GridSystem.col(11), info.name)
          ),
          "success".classIf(Rx(panel.torrent().contains(info)))
        )
      }
    }

    override val heading: Rx[Seq[Modifier]] = Rx {
      Seq[Modifier](
        Seq[Modifier](GridSystem.col(1), "Actions"),
        Seq[Modifier](GridSystem.col(11), "Name")
      )
    }

    def update(): Unit = {
      val offset = (currentPage.now - 1) * perPage
      TorrentStreamApi.info(offset, perPage).onComplete {
        case Success(ts) ⇒
          torrentInfo.update(ts)

        case Failure(_) ⇒
          torrentInfo.update(Vector.empty)
      }

      TorrentStreamApi.uploaded().onComplete {
        case Success(ts) ⇒
          torrentsCount.update(ts)

        case Failure(_) ⇒
          torrentsCount.update(0)
      }
    }
  }

  def update(): Unit = {
    table.update()
  }

  override def render(md: Modifier*): Modifier = {
    Rx[Frag](if (table.torrentInfo().isEmpty) "" else table.renderTag(md:_*))
  }
}
