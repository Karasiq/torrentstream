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

  object Marshallers {
    implicit def binaryMarshaller[A, B](implicit ev: Pickler[A], m: Marshaller[ByteString, B]): Marshaller[A, B] = {
      Marshaller(implicit ec ⇒ value ⇒ m(ByteString(Pickle.intoBytes(value))))
    }
  }

  object Picklers {
    implicit val byteStringPickler: Pickler[ByteString] = new Pickler[ByteString] {
      override def pickle(obj: ByteString)(implicit state: PickleState): Unit = {
        state.enc.writeByteArray(obj.toArray)
      }

      override def unpickle(implicit state: UnpickleState): ByteString = {
        val byteArray = state.dec.readByteArray()
        ByteString(byteArray)
      }
    }

    implicit val instantPickler: Pickler[Instant] = new Pickler[Instant] {
      override def pickle(obj: Instant)(implicit state: PickleState): Unit = {
        state.enc.writeLong(obj.getEpochSecond)
        state.enc.writeInt(obj.getNano)
      }

      override def unpickle(implicit state: UnpickleState): Instant = {
        val second = state.dec.readLong
        val nano = state.dec.readInt
        Instant.ofEpochSecond(second, nano)
      }
    }
  }
}