package com.karasiq.torrentstream.app

import java.io.Closeable
import java.nio.file.Paths

import com.karasiq.mapdb.transaction.TxCtx
import com.karasiq.mapdb.{MapDbSingleFileProducer, MapDbWrapper}
import com.karasiq.ttorrent.common.Torrent
import com.typesafe.config.Config
import org.apache.commons.codec.binary.Hex
import org.mapdb.DBMaker.Maker
import org.mapdb.{BTreeKeySerializer, Serializer}

import scala.concurrent.Future

case class TorrentInfoHash(bytes: Array[Byte]) extends AnyVal {
  def asString: String = Torrent.byteArrayToHexString(bytes)
}

object TorrentInfoHash {
  def fromTorrentData(data: Array[Byte]): TorrentInfoHash = {
    TorrentInfoHash(new Torrent(data, false).getInfoHash)
  }

  def fromString(str: String): TorrentInfoHash = {
    TorrentInfoHash(Hex.decodeHex(str.toCharArray))
  }
}

final class TorrentStore(config: Config) extends Map[TorrentInfoHash, Array[Byte]] with Closeable {
  private object DbProvider extends MapDbSingleFileProducer(Paths.get(config.getString("torrentstream.store.path"))) {
    override protected def setSettings(dbMaker: Maker): Maker = {
      dbMaker
        .executorEnable()
        .compressionEnable()
        .asyncWriteEnable()
        .asyncWriteFlushDelay(1000)
    }
  }

  private val db = DbProvider()

  private val map = MapDbWrapper(db).createTreeMap[Array[Byte], Array[Byte]]("torrents")(_
    .keySerializer(BTreeKeySerializer.BYTE_ARRAY)
    .valueSerializer(Serializer.BYTE_ARRAY)
    .nodeSize(32)
    .valuesOutsideNodesEnable()
  )

  override def +[B1 >: Array[Byte]](kv: (TorrentInfoHash, B1)): Map[TorrentInfoHash, B1] = {
    throw new IllegalArgumentException
  }

  override def -(key: TorrentInfoHash): Map[TorrentInfoHash, Array[Byte]] = {
    throw new IllegalArgumentException
  }

  override def get(key: TorrentInfoHash): Option[Array[Byte]] = {
    map.get(key.bytes)
  }

  override def iterator: Iterator[(TorrentInfoHash, Array[Byte])] = {
    map.iterator.map(kv ⇒ TorrentInfoHash(kv._1) → kv._2)
  }

  override def contains(key: TorrentInfoHash): Boolean = {
    map.contains(key.bytes)
  }

  def torrents: Iterator[Torrent] = {
    map.valuesIterator.map(new Torrent(_, false))
  }

  def add(data: Array[Byte])(implicit tx: TxCtx = db.newTransaction): Future[Unit] = {
    db.scheduleTransaction { implicit tx ⇒
      map += TorrentInfoHash.fromTorrentData(data).bytes → data
    }
  }

  override def close(): Unit = {
    DbProvider.close()
  }
}
