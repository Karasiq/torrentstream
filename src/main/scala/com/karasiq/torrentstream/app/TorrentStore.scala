package com.karasiq.torrentstream.app

import java.io.Closeable
import java.nio.file.Paths

import com.karasiq.mapdb.{MapDbSingleFileProducer, MapDbWrapper}
import com.karasiq.ttorrent.common.Torrent
import com.typesafe.config.Config
import org.mapdb.DBMaker.Maker
import org.mapdb.{BTreeKeySerializer, Serializer}

import scala.collection.mutable

final class TorrentStore(config: Config) extends mutable.AbstractMap[Long, Array[Byte]] with Closeable {
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

  private val nextId = db.db.atomicLong("torrent_id")

  private val map = MapDbWrapper(db).createTreeMap[Long, Array[Byte]]("torrents")(_
    .keySerializer(BTreeKeySerializer.LONG)
    .valueSerializer(Serializer.BYTE_ARRAY)
    .nodeSize(32)
    .valuesOutsideNodesEnable()
  )

  override def +=(kv: (Long, Array[Byte])): TorrentStore.this.type = {
    db.withTransaction { implicit tx ⇒
      map += kv
    }
    this
  }

  override def -=(key: Long): TorrentStore.this.type = {
    db.withTransaction { implicit tx ⇒
      map -= key
    }
    this
  }

  override def get(key: Long): Option[Array[Byte]] = {
    map.get(key)
  }

  override def iterator: Iterator[(Long, Array[Byte])] = {
    map.iterator
  }

  def lastIndex: Long = {
    nextId.get()
  }

  def torrents: Iterator[Torrent] = {
    this.valuesIterator.map(new Torrent(_, false))
  }

  def add(data: Array[Byte]): Long = {
    db.withTransaction { implicit tx ⇒
      val index = nextId.getAndIncrement()
      map += index → data
      index
    }
  }

  override def close(): Unit = {
    DbProvider.close()
  }
}
