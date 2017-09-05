package com.karasiq.torrentstream.app

import java.time.Instant

import akka.http.scaladsl.marshalling._
import akka.util.ByteString
import boopickle.Default._
import org.apache.commons.codec.binary.Hex

private[app] object AppSerializers {
  implicit class StringConversions(private val str: String) extends AnyVal {
    def decodeHexString: ByteString = {
      ByteString(Hex.decodeHex(str.toCharArray))
    }
  }

  trait Marshallers {
    implicit def binaryMarshaller[A, B](implicit ev: Pickler[A], m: Marshaller[ByteString, B]): Marshaller[A, B] = {
      Marshaller(implicit ec ⇒ value ⇒ m(ByteString(Pickle.intoBytes(value))))
    }
  }

  trait Picklers {
    implicit val byteStringPickler: Pickler[ByteString] = new Pickler[ByteString] {
      override def pickle(obj: ByteString)(implicit state: PickleState): Unit = {
        state.pickle(obj.toArray)
      }

      override def unpickle(implicit state: UnpickleState): ByteString = {
        ByteString(state.unpickle[Array[Byte]])
      }
    }

    implicit val instantPickler: Pickler[Instant] = new Pickler[Instant] {
      override def pickle(obj: Instant)(implicit state: PickleState): Unit = {
        state.pickle[Long](obj.getEpochSecond)
        state.pickle[Int](obj.getNano)
      }

      override def unpickle(implicit state: UnpickleState): Instant = {
        val second = state.unpickle[Long]
        val nano = state.unpickle[Int]
        Instant.ofEpochSecond(second, nano)
      }
    }
  }
}

private object MapDbBpSerializer {
  import java.io.{DataInput, DataOutput}
  import java.nio.ByteBuffer

  import org.mapdb.{DataIO, Serializer}

  def apply[T: Pickler]: Serializer[T] = new Serializer[T] {
    override def serialize(out: DataOutput, value: T): Unit = {
      val data = Pickle[T](value).toByteBuffer
      DataIO.packInt(out, data.remaining())
      out.write(data.array())
    }

    override def deserialize(in: DataInput, available: Int): T = {
      val length = DataIO.unpackInt(in)
      val array = new Array[Byte](length)
      in.readFully(array)
      Unpickle[T].fromBytes(ByteBuffer.wrap(array))
    }
  }

  val BYTE_STRING = new Serializer[ByteString] {
    private val arraySerializer = Serializer.BYTE_ARRAY

    override def serialize(out: DataOutput, value: ByteString): Unit = {
      arraySerializer.serialize(out, value.toArray)
    }

    override def deserialize(in: DataInput, available: Int): ByteString = {
      ByteString(arraySerializer.deserialize(in, available))
    }
  }
}