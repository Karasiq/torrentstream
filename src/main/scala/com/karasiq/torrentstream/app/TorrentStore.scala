package com.karasiq.torrentstream.app

import java.io.Closeable
import java.nio.file.Paths
import java.time.Instant

import akka.util.ByteString
import boopickle.Default._
import com.karasiq.bittorrent.format.Torrent
import com.karasiq.mapdb.{MapDbSingleFileProducer, MapDbWrapper}
import com.typesafe.config.Config
import org.apache.commons.codec.binary.Hex
import org.mapdb.BTreeKeySerializer
import org.mapdb.DBMaker.Maker

import scala.collection.mutable

final class TorrentStore(config: Config) extends mutable.Map[ByteString, Torrent] with Closeable {
  private object DbProvider extends MapDbSingleFileProducer(Paths.get(config.getString("karasiq.torrentstream.store.path"))) {
    override protected def setSettings(dbMaker: Maker): Maker = {
      dbMaker
        .transactionDisable()
        .cacheWeakRefEnable()
        .executorEnable()
        .compressionEnable()
        .asyncWriteEnable()
        .asyncWriteFlushDelay(1000)
    }
  }

  private val db = DbProvider()

  private val map = MapDbWrapper(db).createHashMap[ByteString, Torrent]("torrents")(_
    .keySerializer(MapDbBpSerializer.BYTE_STRING)
    .valueSerializer(MapDbBpSerializer[Torrent])
    .counterEnable()
  )

  private val infoMap = MapDbWrapper(db).createTreeMap[Long, Set[TorrentInfo]]("torrents_uploaded")(_
    .keySerializer(BTreeKeySerializer.LONG)
    .valueSerializer(MapDbBpSerializer[Set[TorrentInfo]])
    .nodeSize(32)
    .valuesOutsideNodesEnable()
  )


  def info(key: ByteString): TorrentInfo = {
    TorrentInfo.fromTorrent(map(key))
  }

  def infoIterator: Iterator[TorrentInfo] = {
    import scala.collection.JavaConversions._
    infoMap.underlying().descendingMap().valuesIterator.flatten.filter(t ⇒ map.contains(infoHashOf(t)))
  }

  private def infoHashOf(t: TorrentInfo): ByteString = {
    ByteString(Hex.decodeHex(t.infoHash.toCharArray))
  }

  override def get(key: ByteString): Option[Torrent] = {
    map.get(key)
  }

  override def iterator: Iterator[(ByteString, Torrent)] = {
    map.iterator
  }

  override def contains(key: ByteString): Boolean = {
    map.contains(key)
  }

  override def +=(kv: (ByteString, Torrent)): TorrentStore.this.type = {
    val (infoHash, torrent) = kv
    map += infoHash → torrent

    val now = Instant.now().toEpochMilli
    infoMap += now → infoMap.get(now).fold(Set(TorrentInfo.fromTorrent(torrent)))(_ + TorrentInfo.fromTorrent(torrent))
    this
  }

  override def -=(key: ByteString): TorrentStore.this.type = {
    map -= key
    this
  }

  override def size: Int = map.size

  override def close(): Unit = {
    infoMap.iterator.foreach {
      case (time, ts) ⇒
        val (keep, drop) = ts.partition(t ⇒ map.contains(infoHashOf(t)))
        if (keep.isEmpty) {
          infoMap -= time
        } else if (drop.nonEmpty) {
          infoMap += time → keep
        }
    }
    DbProvider.close()
  }
}
