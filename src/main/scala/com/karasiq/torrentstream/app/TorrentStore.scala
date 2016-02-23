package com.karasiq.torrentstream.app

import java.io.Closeable
import java.nio.file.Paths
import java.time.Instant

import akka.util.ByteString
import boopickle.Default._
import com.karasiq.bittorrent.format.Torrent
import com.karasiq.mapdb.{MapDbSingleFileProducer, MapDbWrapper}
import com.typesafe.config.Config
import org.mapdb.BTreeKeySerializer.ArrayKeySerializer
import org.mapdb.DBMaker.Maker
import org.mapdb.Serializer

import scala.collection.mutable

final class TorrentStore(config: Config) extends mutable.Map[ByteString, Torrent] with Closeable with AppSerializers.Picklers {
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

  private val infoMap = MapDbWrapper(db).createTreeMap[Array[Any], TorrentInfo]("torrents_info")(_
    .keySerializer(new ArrayKeySerializer(Serializer.LONG_PACKED, Serializer.INTEGER_PACKED))
    .valueSerializer(MapDbBpSerializer[TorrentInfo])
    .valuesOutsideNodesEnable()
    .nodeSize(32)
  )

  private val timeMap = MapDbWrapper(db).createHashMap[ByteString, Long]("torrents_time")(_
    .keySerializer(MapDbBpSerializer.BYTE_STRING)
    .valueSerializer(Serializer.LONG_PACKED)
  )

  def info(key: ByteString): TorrentInfo = {
    infoMap(Array[Any](timeMap(key), key.hashCode()))
  }

  def infoIterator: Iterator[TorrentInfo] = {
    import scala.collection.JavaConversions._
    infoMap.underlying().descendingMap().valuesIterator
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
    if (!timeMap.contains(infoHash)) {
      map += kv
      val now = Instant.now().toEpochMilli
      infoMap += (Array[Any](now, infoHash.hashCode()) → TorrentInfo.fromTorrent(torrent))
      timeMap += infoHash → now
    }
    this
  }

  override def -=(infoHash: ByteString): TorrentStore.this.type = {
    map -= infoHash
    infoMap -= Array[Any](timeMap.getOrElse(infoHash, 0L), infoHash.hashCode())
    timeMap -= infoHash
    this
  }

  override def size: Int = map.size

  override def close(): Unit = {
    DbProvider.close()
  }
}
