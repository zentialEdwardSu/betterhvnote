package com.betterhv.transfer.core.v4.session

import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.core.v4.codec.RecordCodec
import com.betterhv.transfer.core.v4.crypto.Direction
import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.ClientHello
import com.betterhv.transfer.proto.v4.ErrorCode
import com.betterhv.transfer.proto.v4.Offer
import com.betterhv.transfer.proto.v4.Request
import com.betterhv.transfer.proto.v4.ServerHello
import com.betterhv.transfer.proto.v4.SessionTicket
import com.google.protobuf.ByteString

class HandshakeException(
  val code: ErrorCode,
  message: String,
  val retryable: Boolean = false,
) : IllegalStateException(message)

data class PairingKey(val deviceId: String, val psk: ByteArray) {
  override fun equals(other: Any?): Boolean = other is PairingKey &&
    deviceId == other.deviceId && psk.contentEquals(other.psk)

  override fun hashCode(): Int = 31 * deviceId.hashCode() + psk.contentHashCode()
}

/** Resolves the PSK selected by the clear-text `key_id` hint. */
fun interface PairingKeyStore {
  fun resolve(keyId: Int): PairingKey?
}

data class ClientSession(
  val sessionId: ByteArray,
  val keys: SessionKeys,
  val serverHello: ServerHello,
  val capabilities: NegotiatedCapabilities,
  val offers: List<Offer>,
) {
  override fun equals(other: Any?): Boolean = other is ClientSession &&
    sessionId.contentEquals(other.sessionId) && serverHello == other.serverHello

  override fun hashCode(): Int = sessionId.contentHashCode()
}

data class ServerSession(
  val sessionId: ByteArray,
  val keys: SessionKeys,
  val clientHello: ClientHello,
  val capabilities: NegotiatedCapabilities,
  val clientNonce: ByteArray,
  val serverNonce: ByteArray,
)

/**
 * One-round-trip handshake for the v4 protocol.
 *
 * ClientHello and ServerHello are each sealed as a single HELLO record (possibly fragmented by
 * the transport). The clear 16-byte handshake nonce in the record header is the only extra input
 * needed to derive the flight key, so neither side needs a preliminary exchange.
 */
class ClientHandshake(
  private val pairingKey: ByteArray,
  private val deviceId: String,
  private val capabilities: CapabilitySet,
  private val request: Request,
  private val listenerTicket: SessionTicket? = null,
  private val maxFragmentBody: Int = DEFAULT_FRAGMENT_BODY,
  private val clock: () -> Long = System::currentTimeMillis,
  private val randomBytes: (Int) -> ByteArray = TransferCrypto::randomBytes,
) {
  private val keyId = SessionKeys.keyId(pairingKey)
  private var clientNonce: ByteArray? = null
  private var clientHello: ClientHello? = null

  fun start(): List<ByteArray> {
    check(clientNonce == null) { "Handshake already started" }
    val nonce = randomBytes(SessionKeys.NONCE_BYTES)
    val nonceCopy = nonce.copyOf()
    val helloBuilder = ClientHello.newBuilder()
      .setMajor(MAJOR)
      .setMinorMin(MINOR)
      .setMinorMax(MINOR)
      .setDeviceId(deviceId)
      .setClientTimeMs(clock())
      .setCapabilities(capabilities)
      .setRequest(request)
      .setMaxRecordBytes(maxFragmentBody)
    listenerTicket?.let(helloBuilder::setListenerTicket)
    val hello = helloBuilder.build()
    val header = RecordCodec.Header(
      flags = RecordCodec.FLAG_ENCRYPTED or RecordCodec.FLAG_HELLO,
      keyId = keyId,
      sessionId = 0L,
      streamId = 0,
      messageId = 1,
    )
    val records = RecordCodec.seal(
      SessionKeys.helloKey(pairingKey, keyId, nonceCopy),
      header,
      hello.toByteArray(),
      maxFragmentBody,
      nonceCopy,
    )
    clientNonce = nonceCopy
    clientHello = hello
    return records
  }

  fun complete(serverRecords: List<ByteArray>): ClientSession {
    val nonce = requireNotNull(clientNonce) { "Handshake was not started" }
    val firstRecord = serverRecords.firstOrNull()
      ?: throw failure(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Empty reply")
    val first = RecordCodec.parse(firstRecord)
    if (!first.header.isHello || !first.header.isResponse || first.header.sessionId != 0L) {
      throw failure(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Unexpected server flight")
    }
    if (first.header.messageId != 1 || first.header.keyId != 0) {
      throw failure(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Unexpected reply sequence")
    }
    val serverNonce = handshakeNonce(serverRecords.first())
    val plaintext = try {
      RecordCodec.open(
        SessionKeys.replyKey(pairingKey, nonce, serverNonce),
        serverRecords,
      )
    } catch (error: RecordCodec.RecordException) {
      throw failure(error.code, error.message ?: "Reply authentication failed")
    }
    val hello = try {
      ServerHello.parseFrom(plaintext)
    } catch (error: Throwable) {
      throw failure(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Malformed ServerHello")
    }
    if (hello.major != MAJOR) {
      throw failure(ErrorCode.ERROR_UNSUPPORTED_PROTOCOL, "Server major ${hello.major}")
    }
    if (hello.minor !in MINOR..MINOR) {
      throw failure(ErrorCode.ERROR_UNSUPPORTED_PROTOCOL, "Server minor ${hello.minor}")
    }
    val sessionId = hello.sessionId.toByteArray()
    if (sessionId.size != SessionKeys.SESSION_ID_BYTES || sessionId.all { it == 0.toByte() }) {
      throw failure(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Invalid session id")
    }
    if (!hello.serverNonce.isEmpty &&
      !hello.serverNonce.toByteArray().contentEquals(serverNonce)
    ) {
      throw failure(ErrorCode.ERROR_AUTHENTICATION_FAILED, "Server nonce mismatch")
    }
    val keys = SessionKeys.derive(pairingKey, keyId, nonce, serverNonce, sessionId)
    return ClientSession(
      sessionId = sessionId,
      keys = keys,
      serverHello = hello,
      capabilities = CapabilityNegotiator.negotiate(capabilities, hello.capabilities),
      offers = hello.offersList,
    )
  }

  private fun handshakeNonce(record: ByteArray): ByteArray {
    if (record.size < RecordCodec.HELLO_HEADER_BYTES) {
      throw failure(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Missing handshake nonce")
    }
    return record.copyOfRange(RecordCodec.HEADER_BYTES, RecordCodec.HELLO_HEADER_BYTES)
  }

  private fun failure(code: ErrorCode, message: String): HandshakeException =
    HandshakeException(code, message)
}

class ServerHandshake(
  private val pairings: PairingKeyStore,
  private val capabilities: CapabilitySet,
  private val offers: (Request) -> List<Offer> = { emptyList() },
  private val replayGuard: HandshakeReplayGuard = HandshakeReplayGuard(),
  private val maxFragmentBody: Int = DEFAULT_FRAGMENT_BODY,
  private val keepaliveMillis: Int = 5_000,
  private val clock: () -> Long = System::currentTimeMillis,
  private val randomBytes: (Int) -> ByteArray = TransferCrypto::randomBytes,
) {
  fun accept(
    records: List<ByteArray>,
    offersFor: ((ClientHello) -> List<Offer>)? = null,
    listenerTicketFor: ((ClientHello, ByteArray) -> SessionTicket?)? = null,
  ): Pair<List<ByteArray>, ServerSession> {
    val firstRecord = records.firstOrNull()
      ?: throw failure(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Empty hello")
    val first = RecordCodec.parse(firstRecord)
    if (!first.header.isHello || first.header.isResponse) {
      throw failure(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Expected a ClientHello")
    }
    val pairing = pairings.resolve(first.header.keyId)
      ?: throw failure(ErrorCode.ERROR_UNKNOWN_PAIRING, "Unknown pairing key")
    val clientNonce = handshakeNonce(records.first())
    val plaintext = try {
      RecordCodec.open(
        SessionKeys.helloKey(pairing.psk, first.header.keyId, clientNonce),
        records,
      )
    } catch (error: RecordCodec.RecordException) {
      throw failure(error.code, error.message ?: "ClientHello authentication failed")
    }
    val hello = try {
      ClientHello.parseFrom(plaintext)
    } catch (error: Throwable) {
      throw failure(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Malformed ClientHello")
    }
    if (hello.deviceId != pairing.deviceId) {
      throw failure(ErrorCode.ERROR_AUTHENTICATION_FAILED, "Device id does not match the pairing")
    }
    when (val verdict = replayGuard.check(hello, clientNonce, clock())) {
      is HandshakeVerdict.Reject -> throw HandshakeException(
        verdict.code,
        verdict.message,
        verdict.retryable,
      )

      HandshakeVerdict.Accept -> Unit
    }
    val minor = CapabilityNegotiator.chooseMinor(hello.minorMin, hello.minorMax)
      ?: throw failure(ErrorCode.ERROR_UNSUPPORTED_PROTOCOL, "No overlapping protocol minor")
    val serverNonce = randomBytes(SessionKeys.NONCE_BYTES)
    val sessionId = newSessionId()
    val replyBuilder = ServerHello.newBuilder()
      .setMajor(MAJOR)
      .setMinor(minor)
      .setSessionId(ByteString.copyFrom(sessionId))
      .setServerNonce(ByteString.copyFrom(serverNonce))
      .setCapabilities(capabilities)
      .addAllOffers(offersFor?.invoke(hello) ?: offers(hello.request))
      .setAcceptedFeatures(capabilities.features and hello.capabilities.features)
      .setKeepaliveMs(keepaliveMillis)
      .setServerTimeMs(clock())
    listenerTicketFor?.invoke(hello, sessionId)?.let(replyBuilder::setListenerTicket)
    val reply = replyBuilder.build()
    val header = RecordCodec.Header(
      flags = RecordCodec.FLAG_ENCRYPTED or RecordCodec.FLAG_HELLO or RecordCodec.FLAG_RESPONSE,
      keyId = 0,
      sessionId = 0L,
      streamId = 0,
      messageId = 1,
    )
    val replyRecords = RecordCodec.seal(
      SessionKeys.replyKey(pairing.psk, clientNonce, serverNonce),
      header,
      reply.toByteArray(),
      maxFragmentBody,
      serverNonce,
    )
    val session = ServerSession(
      sessionId = sessionId,
      keys = SessionKeys.derive(pairing.psk, first.header.keyId, clientNonce, serverNonce, sessionId),
      clientHello = hello,
      capabilities = CapabilityNegotiator.negotiate(capabilities, hello.capabilities),
      clientNonce = clientNonce,
      serverNonce = serverNonce,
    )
    return replyRecords to session
  }

  private fun handshakeNonce(record: ByteArray): ByteArray {
    if (record.size < RecordCodec.HELLO_HEADER_BYTES) {
      throw failure(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Missing handshake nonce")
    }
    return record.copyOfRange(RecordCodec.HEADER_BYTES, RecordCodec.HELLO_HEADER_BYTES)
  }

  private fun newSessionId(): ByteArray {
    repeat(4) {
      val candidate = randomBytes(SessionKeys.SESSION_ID_BYTES)
      if (candidate.any { byte -> byte != 0.toByte() }) return candidate
    }
    throw failure(ErrorCode.ERROR_INTERNAL, "Could not allocate a session id")
  }

  private fun failure(code: ErrorCode, message: String): HandshakeException =
    HandshakeException(code, message)
}

private const val MAJOR = 4
private const val MINOR = 0
private const val DEFAULT_FRAGMENT_BODY = 256
