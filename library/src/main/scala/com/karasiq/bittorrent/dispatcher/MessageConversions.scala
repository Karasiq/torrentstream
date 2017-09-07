package com.karasiq.bittorrent.dispatcher

import com.karasiq.bittorrent.protocol.PeerMessages._

private[bittorrent] object MessageConversions {
  implicit class PieceBlockInfoOps(val pb: PieceBlockInfo) extends AnyVal {
    def request: PieceBlockRequest = {
      PieceBlockRequest(pb.index, pb.offset, pb.length)
    }

    def cancel: CancelBlockDownload = {
      CancelBlockDownload(pb.index, pb.offset, pb.length)
    }

    def failed: BlockDownloadFailed = {
      BlockDownloadFailed(pb.index, pb.offset, pb.length)
    }

    def isRelatedTo(pb1: PieceBlockInfo): Boolean = {
      pb.index == pb1.index && pb.offset == pb1.offset && pb.length == pb1.length
    }
  }

  implicit class PieceBlockOps(val pb: PieceBlockData) extends AnyVal {
    def downloaded: DownloadedBlock = {
      DownloadedBlock(pb.index, pb.offset, pb.data)
    }

    def message: PieceBlock = {
      PieceBlock(pb.index, pb.offset, pb.data)
    }
  }
}
