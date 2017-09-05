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
  private val RC4SkipBytes = 1024
  private val VerificationConstant = ByteString(0, 0, 0, 0, 0, 0, 0, 0) // Verification constant, 8 bytes
  private val cryptoProvider = new org.bouncycastle.jce.provider.BouncyCastleProvider()
  private val secureRandom = new SecureRandom()

  def apply(infoHash: ByteString)(implicit log: LoggingAdapter): BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] = {
    BidiFlow.fromGraph(new PeerStreamEncryption(infoHash)).named("peerStreamEncryption")
  }

  object DHKeys {
    // Key constants
    val KeyLength = 160
    val PublicKeyLength = 96
    private[this] val P = BigInt("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A63A36210000000000090563", 16)
    private[this] val G = BigInt(2)

    // Key generators
    private[this] val generator = {
      val generator = KeyPairGenerator.getInstance("DH", cryptoProvider)
      generator.initialize(new DHParameterSpec(P.underlying(), G.underlying(), KeyLength))
      generator
    }

    private[this] val keyFactory = {
      KeyFactory.getInstance("DH", cryptoProvider)
    }

    def toBytes(key: PublicKey): ByteString = {
      val keyBytes: Array[Byte] = key match {
        case dhKey: BCDHPublicKey ⇒ dhKey.getY.toByteArray
        case _ ⇒ throw new IllegalArgumentException(s"Not a DH public key: $key")
      }

      if (keyBytes.length == PublicKeyLength + 1)
        ByteString.fromArray(keyBytes, 1, PublicKeyLength)
      else if (keyBytes.length == PublicKeyLength)
        ByteString(keyBytes)
      else
        throw new IllegalArgumentException(s"Invalid DH key length: ${keyBytes.length}")
    }

    def generateKey(): KeyPair = {
      generator.generateKeyPair()
    }

    def tryReadKey(bytes: ByteString): Try[PublicKey] = {
      require(bytes.length == PublicKeyLength, "Invalid DH key length")
      val keyBigInt = BigInt(1, bytes.toArray)
      val keySpec = new DHPublicKeySpec(keyBigInt.underlying(), P.underlying(), G.underlying())
      Try(keyFactory.generatePublic(keySpec))
    }
  }

  object GenCrypto {
    def sha1(data: ByteString): ByteString = {
      val md = MessageDigest.getInstance("SHA-1", cryptoProvider)
      ByteString(md.digest(data.toArray))
    }

    def generatePadding(): ByteString = {
      val length = secureRandom.nextInt(512)
      val outArray = new Array[Byte](length)
      secureRandom.nextBytes(outArray)
      ByteString(outArray)
    }
  }

  private object Stage extends Enumeration {
    val ClientDH, ClientAwaitDH, ClientAwaitConfirmation = Value
    val ServerAwaitDH, ServerAwaitConfirmation = Value
    val Ready = Value
  }
}

/**
  * Peer stream encryption stage
  * @param infoHash Torrent 20 bytes info hash
  * @param log Logging adapter
  * @see [[https://wiki.vuze.com/w/Message_Stream_Encryption]]
  * @todo Server mode
  */
private final class PeerStreamEncryption(infoHash: ByteString)(implicit log: LoggingAdapter)
  extends GraphStage[BidiShape[ByteString, ByteString, ByteString, ByteString]] {

  import PeerStreamEncryption._

  val tcpInput = Inlet[ByteString]("TcpInput")
  val messageInput = Inlet[ByteString]("MessageInput")
  val messageOutput = Outlet[ByteString]("MessageOutput")
  val tcpOutput = Outlet[ByteString]("TcpOutput")

  override def shape: BidiShape[ByteString, ByteString, ByteString, ByteString] = {
    BidiShape(tcpInput, messageOutput, messageInput, tcpOutput)
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    var stage = Stage.ClientDH
    var secret = ByteString.empty // Diffie-Hellman shared secret
    var isRC4Enabled = false

    // -----------------------------------------------------------------------
    // Buffers
    // -----------------------------------------------------------------------
    var messageInputBuffer = List.empty[ByteString]
    var tcpInputBuffer = ByteString.empty // Input buffer

    // -----------------------------------------------------------------------
    // Handshake stages
    // -----------------------------------------------------------------------
    private[this] object Stages {
      def sendPublicKey(): Unit = {
        val bytes = DH.getKeyBytes()
        emit(tcpOutput, bytes ++ GenCrypto.generatePadding(), () ⇒ tryPull(tcpInput))
        stage = Stage.ClientAwaitDH
      }

      def clientRequestDH(): Unit = {
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

              secret = DH.createSecret(bKey)
              RC4.setOwnKey(GenCrypto.sha1(ByteString("keyA") ++ secret ++ infoHash))
              RC4.setPeerKey(GenCrypto.sha1(ByteString("keyB") ++ secret ++ infoHash))
              RC4.reset()

              val hash1 = GenCrypto.sha1(ByteString("req1") ++ secret)
              val hash2 = {
                val array = GenCrypto.sha1(ByteString("req2") ++ infoHash).toArray
                val xor = GenCrypto.sha1(ByteString("req3") ++ secret).toArray
                for (i ← array.indices) array(i) = (array(i) ^ xor(i)).toByte
                ByteString(array)
              }

              val encryptedHandshake = {
                val cryptoProvide = 1 | 2
                val pad = GenCrypto.generatePadding()
                val buffer = ByteBuffer.allocate(VerificationConstant.length + 4 + 2 + pad.length + 2 + handshake.length)
                buffer.put(VerificationConstant.toByteBuffer)
                buffer.putInt(cryptoProvide)
                buffer.putShort(pad.length.toShort)
                buffer.put(pad.toByteBuffer)
                buffer.putShort(handshake.length.toShort)
                buffer.put(handshake.toByteBuffer)
                buffer.flip()
                RC4.encrypt(ByteString(buffer))
              }

              emit(tcpOutput, hash1 ++ hash2 ++ encryptedHandshake)
              stage = Stage.ClientAwaitConfirmation

            case Failure(error) ⇒
              failStage(new IOException("Invalid DH key", error))
          }
        }
      }

      def clientSynchronize(): Unit = {
        @tailrec
        def syncVcPos(): Boolean = {
          if (tcpInputBuffer.length < VerificationConstant.length) {
            false
          } else {
            val (take, keep) = tcpInputBuffer.splitAt(VerificationConstant.length)
            if (RC4.decrypt(take) == VerificationConstant) {
              tcpInputBuffer = keep
              true
            } else {
              tcpInputBuffer = tcpInputBuffer.tail
              RC4.resetPeer()
              syncVcPos()
            }
          }
        }

        if (syncVcPos()) {
          val cryptoSelect = BitTorrentTcpProtocol.int32FromBytes(RC4.decrypt(tcpInputBuffer.take(4)))
          val padLength = BitTorrentTcpProtocol.int32FromBytes(RC4.decrypt(tcpInputBuffer.drop(4).take(2)))
          RC4.decrypt(tcpInputBuffer.drop(4 + 2).take(padLength))
          tcpInputBuffer = tcpInputBuffer.drop(4 + 2 + padLength)
          if ((cryptoSelect & 2) != 0) {
            isRC4Enabled = true
          } else if ((cryptoSelect & 1) != 0) {
            isRC4Enabled = false
          } else {
            failStage(new IOException("No known encryption methods available"))
          }
          log.debug("Peer message stream encryption mode set to {}", if (isRC4Enabled) "RC4" else "plaintext")
          stage = Stage.Ready
          emit(messageOutput, if (isRC4Enabled) RC4.decrypt(tcpInputBuffer) else tcpInputBuffer)
          tcpInputBuffer = ByteString.empty
        }
      }
    }

    // -----------------------------------------------------------------------
    // Cryptography
    // -----------------------------------------------------------------------
    private[this] object DH {
      private[this] val ownKey = DHKeys.generateKey()
      private[this] val dhEngine = KeyAgreement.getInstance("DH", cryptoProvider)
      dhEngine.init(ownKey.getPrivate)

      def getKeyBytes(): ByteString = {
        DHKeys.toBytes(ownKey.getPublic)
      }

      def createSecret(key: PublicKey): ByteString = {
        dhEngine.doPhase(key, true)
        ByteString(dhEngine.generateSecret())
      }
    }

    private[this] object RC4 {
      private[this] val ownRc4Engine = new RC4Engine
      private[this] val peerRc4Engine = new RC4Engine
      private[this] val rc4InBuffer = new Array[Byte](RC4SkipBytes)
      private[this] val rc4OutBuffer = new Array[Byte](RC4SkipBytes)

      def encrypt(data: ByteString): ByteString = {
        processBytes(ownRc4Engine, data)
      }

      def decrypt(data: ByteString): ByteString = {
        processBytes(peerRc4Engine, data)
      }

      def setOwnKey(bytes: ByteString): Unit = {
        ownRc4Engine.init(true, new KeyParameter(bytes.toArray))
      }

      def setPeerKey(bytes: ByteString): Unit = {
        peerRc4Engine.init(true, new KeyParameter(bytes.toArray))
      }

      def resetOwn(): Unit = {
        resetEngine(ownRc4Engine)
      }

      def resetPeer(): Unit = {
        resetEngine(peerRc4Engine)
      }

      def reset(): Unit = {
        resetOwn()
        resetPeer()
      }

      private[this] def processBytes(engine: RC4Engine, data: ByteString): ByteString = {
        val input = data.toByteBuffer
        while (input.remaining() > 0) {
          val length = Array(input.remaining(), RC4SkipBytes).min
          input.get(rc4InBuffer, 0, length)
          engine.processBytes(rc4InBuffer, 0, length, rc4OutBuffer, 0)
          input.position(input.position() - length)
          input.put(rc4OutBuffer, 0, length)
        }
        input.flip()
        ByteString(input)
      }

      private[this] def resetEngine(engine: RC4Engine): Unit = {
        engine.reset()
        engine.processBytes(rc4InBuffer, 0, RC4SkipBytes, rc4OutBuffer, 0)
      }
    }

    // -----------------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------------
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
            Stages.clientRequestDH()
            tryPull(tcpInput)

          case Stage.ClientAwaitConfirmation ⇒
            Stages.clientSynchronize()
            tryPull(tcpInput)

          case Stage.Ready ⇒
            emit(messageOutput, if (isRC4Enabled) RC4.decrypt(tcpInputBuffer) else tcpInputBuffer, () ⇒ if (!hasBeenPulled(tcpInput)) tryPull(tcpInput))
            tcpInputBuffer = ByteString.empty
        }
      }
    })

    setHandler(tcpOutput, new OutHandler {
      override def onPull(): Unit = {
        if (stage == Stage.ClientDH) {
          Stages.sendPublicKey()
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
          emitMultiple(tcpOutput, (messageInputBuffer :+ grab(messageInput))
            .map(msg ⇒ if (isRC4Enabled) RC4.encrypt(msg) else msg),
            () ⇒ if (!hasBeenPulled(messageInput)) tryPull(messageInput))

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

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
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
