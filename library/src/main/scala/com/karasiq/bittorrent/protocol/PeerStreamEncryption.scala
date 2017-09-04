package com.karasiq.bittorrent.protocol

import javax.crypto.KeyAgreement
import javax.crypto.spec.{DHParameterSpec, DHPublicKeySpec}
import java.io.IOException
import java.nio.ByteBuffer
import java.security._
import java.util.concurrent.TimeoutException

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

import akka.NotUsed
import akka.event.LoggingAdapter
import akka.stream._
import akka.stream.scaladsl.BidiFlow
import akka.stream.stage._
import akka.util.ByteString
import org.bouncycastle.crypto.engines.RC4Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jcajce.provider.asymmetric.dh.BCDHPublicKey

object PeerStreamEncryption {
  private val CryptoProvider = new org.bouncycastle.jce.provider.BouncyCastleProvider()

  def apply(infoHash: ByteString)(implicit log: LoggingAdapter): BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] = {
    BidiFlow.fromGraph(new PeerStreamEncryption(infoHash)).named("peerStreamEncryption")
  }

  private def sha1(data: ByteString): ByteString = {
    val md = MessageDigest.getInstance("SHA-1", CryptoProvider)
    ByteString(md.digest(data.toArray))
  }

  object DHKeys {
    val PublicKeyLength = 96

    private[this] val P = BigInt("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A63A36210000000000090563", 16)
    private[this] val G = BigInt(2)
    private[this] val KeyLength = 160

    private[this] val generator = {
      val generator = KeyPairGenerator.getInstance("DH", CryptoProvider)
      generator.initialize(new DHParameterSpec(P.underlying(), G.underlying(), KeyLength))
      generator
    }

    def toBytes(key: PublicKey): ByteString = {
      val keyBytes = key.asInstanceOf[BCDHPublicKey].getY.toByteArray
      if (keyBytes.length == PublicKeyLength + 1)
        ByteString.fromArray(keyBytes, 1, PublicKeyLength)
      else if (keyBytes.length == PublicKeyLength)
        ByteString(keyBytes)
      else
        throw new IllegalArgumentException(s"Invalid DH key: $key")
    }

    def generateKey(): KeyPair = {
      generator.generateKeyPair()
    }

    def tryReadKey(bytes: ByteString): Try[PublicKey] = {
      require(bytes.length == PublicKeyLength, "Invalid DH key length")
      val keyFactory = KeyFactory.getInstance("DH", CryptoProvider)
      val keyBigInt = {
        BigInt(1, bytes.toArray)
      }
      val keySpec = new DHPublicKeySpec(keyBigInt.underlying(), P.underlying(), G.underlying())
      Try(keyFactory.generatePublic(keySpec))
    }
  }
}

/**
  * Peer stream encryption stage
  * @param infoHash Torrent 20 bytes info hash
  * @param log Logging adapter
  * @see [[https://wiki.vuze.com/w/Message_Stream_Encryption]]
  */
private final class PeerStreamEncryption(infoHash: ByteString)(implicit log: LoggingAdapter)
  extends GraphStage[BidiShape[ByteString, ByteString, ByteString, ByteString]] {

  // TODO: Server mode
  import PeerStreamEncryption.{sha1, DHKeys}

  val tcpInput: Inlet[ByteString] = Inlet("TcpInput")
  val messageInput: Inlet[ByteString] = Inlet("MessageInput")
  val messageOutput: Outlet[ByteString] = Outlet("MessageOutput")
  val tcpOutput: Outlet[ByteString] = Outlet("TcpOutput")

  override def shape: BidiShape[ByteString, ByteString, ByteString, ByteString] = {
    BidiShape(tcpInput, messageOutput, messageInput, tcpOutput)
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    object Stage extends Enumeration {
      val ClientDH, ClientAwaitDH, ClientAwaitConfirmation = Value
      val ServerAwaitDH, ServerAwaitConfirmation = Value
      val Ready = Value
    }

    var stage = Stage.ClientDH
    var rc4Enabled = false

    var messageInputBuffer = List.empty[ByteString]
    var tcpInputBuffer = ByteString.empty // Input buffer

    val ownKey = DHKeys.generateKey()
    val dhEngine = KeyAgreement.getInstance("DH", PeerStreamEncryption.CryptoProvider)
    dhEngine.init(ownKey.getPrivate)

    val ownRc4Engine = new RC4Engine
    val peerRc4Engine = new RC4Engine
    val secureRandom = new SecureRandom()

    var secret = ByteString.empty // Diffie-Hellman shared secret
    val VerificationConstant = ByteString(0, 0, 0, 0, 0, 0, 0, 0) // Verification constant, 8 bytes

    def randomPadding: ByteString = {
      secureRandom.nextBytes(rc4InBuffer)
      ByteString(rc4InBuffer).take(secureRandom.nextInt(512))
    }

    val rc4InBuffer = new Array[Byte](1024)
    val rc4OutBuffer = new Array[Byte](1024)

    def rc4(engine: RC4Engine, data: ByteString): ByteString = {
      val input = data.toByteBuffer
      while (input.remaining() > 0) {
        val length = Array(input.remaining(), 1024).min
        input.get(rc4InBuffer, 0, length)
        engine.processBytes(rc4InBuffer, 0, length, rc4OutBuffer, 0)
        input.position(input.position() - length)
        input.put(rc4OutBuffer, 0, length)
      }
      input.flip()
      ByteString(input)
    }

    def rc4Encrypt(data: ByteString): ByteString = {
      rc4(ownRc4Engine, data)
    }

    def rc4Decrypt(data: ByteString): ByteString = {
      rc4(peerRc4Engine, data)
    }

    def resetRc4(engine: RC4Engine): Unit = {
      engine.reset()
      engine.processBytes(rc4InBuffer, 0, 1024, rc4OutBuffer, 0)
    }

    def sendPublicKey(): Unit = {
      val bytes = DHKeys.toBytes(ownKey.getPublic)
      emit(tcpOutput, bytes ++ randomPadding, () ⇒ tryPull(tcpInput))
      stage = Stage.ClientAwaitDH
    }

    def clientStage2(): Unit = {
      if (tcpInputBuffer.length >= DHKeys.PublicKeyLength && messageInputBuffer.nonEmpty) {
        val (take, keep) = tcpInputBuffer.splitAt(DHKeys.PublicKeyLength)
        tcpInputBuffer = keep
        DHKeys.tryReadKey(take) match {
          case Success(bKey) ⇒
            val handshake: ByteString = {
              if (messageInputBuffer.nonEmpty) {
                val handshake = messageInputBuffer.head
                messageInputBuffer = messageInputBuffer.tail
                handshake
              } else {
                ByteString.empty
              }
            }
            dhEngine.doPhase(bKey, true)
            secret = ByteString(dhEngine.generateSecret())
            ownRc4Engine.init(true, new KeyParameter(sha1(ByteString("keyA") ++ secret ++ infoHash).toArray))
            peerRc4Engine.init(false, new KeyParameter(sha1(ByteString("keyB") ++ secret ++ infoHash).toArray))
            resetRc4(ownRc4Engine)
            resetRc4(peerRc4Engine)
            val hash1 = sha1(ByteString("req1") ++ secret)
            val hash2 = {
              val array = sha1(ByteString("req2") ++ infoHash).toArray
              val xor = sha1(ByteString("req3") ++ secret).toArray
              for (i <- array.indices) array(i) = (array(i) ^ xor(i)).toByte
              ByteString(array)
            }
            val encrypted = {
              val cryptoProvide = 1 | 2
              val pad = randomPadding
              val buffer = ByteBuffer.allocate(VerificationConstant.length + 4 + 2 + pad.length + 2 + handshake.length)
              buffer.put(VerificationConstant.toByteBuffer)
              buffer.putInt(cryptoProvide)
              buffer.putShort(pad.length.toShort)
              buffer.put(pad.toByteBuffer)
              buffer.putShort(handshake.length.toShort)
              buffer.put(handshake.toByteBuffer)
              buffer.flip()
              rc4Encrypt(ByteString(buffer))
            }
            emit(tcpOutput, hash1 ++ hash2 ++ encrypted)
            stage = Stage.ClientAwaitConfirmation

          case Failure(error) ⇒
            failStage(new IOException("Invalid DH key", error))
        }
      }
    }

    def clientStage3(): Unit = {
      @tailrec
      def syncVcPos(): Boolean = {
        if (tcpInputBuffer.length < VerificationConstant.length) {
          false
        } else {
          val (take, keep) = tcpInputBuffer.splitAt(VerificationConstant.length)
          if (rc4Decrypt(take) == VerificationConstant) {
            tcpInputBuffer = keep
            true
          } else {
            tcpInputBuffer = tcpInputBuffer.tail
            resetRc4(peerRc4Engine)
            syncVcPos()
          }
        }
      }

      if (syncVcPos()) {
        val cryptoSelect = BitTorrentTcpProtocol.int32FromBytes(rc4Decrypt(tcpInputBuffer.take(4)))
        val padLength = BitTorrentTcpProtocol.int32FromBytes(rc4Decrypt(tcpInputBuffer.drop(4).take(2)))
        rc4Decrypt(tcpInputBuffer.drop(4 + 2).take(padLength))
        tcpInputBuffer = tcpInputBuffer.drop(4 + 2 + padLength)
        if ((cryptoSelect & 2) != 0) {
          rc4Enabled = true
        } else if ((cryptoSelect & 1) != 0) {
          rc4Enabled = false
        } else {
          failStage(new IOException("No known encryption methods available"))
        }
        log.debug("Peer message stream encryption mode set to {}", if (rc4Enabled) "RC4" else "plaintext")
        stage = Stage.Ready
        emit(messageOutput, if (rc4Enabled) rc4Decrypt(tcpInputBuffer) else tcpInputBuffer)
        tcpInputBuffer = ByteString.empty
      }
    }

    setHandler(tcpInput, new InHandler {
      override def onPush(): Unit = {
        val bytes = grab(tcpInput)
        if (tcpInputBuffer.length > 614400) {
          failStage(new IOException("Buffer overflow"))
        }
        tcpInputBuffer ++= bytes
        stage match {
          case Stage.ClientDH ⇒
            // Nothing

          case Stage.ClientAwaitDH ⇒
            clientStage2()
            tryPull(tcpInput)

          case Stage.ClientAwaitConfirmation ⇒
            clientStage3()
            tryPull(tcpInput)

          case Stage.Ready ⇒
            emit(messageOutput, if (rc4Enabled) rc4Decrypt(tcpInputBuffer) else tcpInputBuffer, () ⇒ if (!hasBeenPulled(tcpInput)) tryPull(tcpInput))
            tcpInputBuffer = ByteString.empty
        }
      }
    })

    setHandler(tcpOutput, new OutHandler {
      override def onPull(): Unit = {
        if (stage == Stage.ClientDH) {
          sendPublicKey()
        } else if (!hasBeenPulled(messageInput)) {
          pull(messageInput)
        }
      }
    })

    setHandler(messageInput, new InHandler {
      override def onPush(): Unit = {
        if (stage != Stage.Ready || !isAvailable(tcpOutput)) {
          messageInputBuffer :+= grab(messageInput)
        } else {
          emitMultiple(tcpOutput, (messageInputBuffer :+ grab(messageInput)).map(msg ⇒ if (rc4Enabled) rc4Encrypt(msg) else msg), () ⇒ if (!hasBeenPulled(messageInput)) tryPull(messageInput))
          messageInputBuffer = List.empty
        }
      }
    })

    setHandler(messageOutput, new OutHandler {
      override def onPull(): Unit = {
        if (stage == Stage.Ready && !hasBeenPulled(tcpInput)) {
          pull(tcpInput)
        }
      }
    })

    override protected def onTimer(timerKey: Any): Unit = {
      if (timerKey == "HandshakeTimeout" && stage != Stage.Ready) {
        failStage(new TimeoutException("Handshake timeout"))
      }
    }

    override def preStart(): Unit = {
      super.preStart()
      scheduleOnce("HandshakeTimeout", 30 seconds)
    }
  }
}
