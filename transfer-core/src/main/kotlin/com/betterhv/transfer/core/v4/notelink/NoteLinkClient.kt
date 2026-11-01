package com.betterhv.transfer.core.v4.notelink

import com.betterhv.transfer.core.ContentCounts
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.v4.codec.RecordCodec
import com.betterhv.transfer.core.v4.lan.TcpAttach
import com.betterhv.transfer.core.v4.pairing.PairingException
import com.betterhv.transfer.core.v4.pairing.PairingInitiator
import com.betterhv.transfer.core.v4.pairing.PairingOutcome
import com.betterhv.transfer.core.v4.pairing.PairingRecords
import com.betterhv.transfer.core.v4.session.ClientHandshake
import com.betterhv.transfer.core.v4.session.Exchange
import com.betterhv.transfer.core.v4.session.ExchangeException
import com.betterhv.transfer.core.v4.session.HandshakeException
import com.betterhv.transfer.core.v4.session.PeerConnection
import com.betterhv.transfer.core.v4.session.ProtocolSession
import com.betterhv.transfer.core.v4.session.RecordLink
import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.ErrorCode
import com.betterhv.transfer.proto.v4.FetchRequest
import com.betterhv.transfer.proto.v4.Frame
import com.betterhv.transfer.proto.v4.LeaseAction
import com.betterhv.transfer.proto.v4.LeaseKind
import com.betterhv.transfer.proto.v4.LeaseUpdate
import com.betterhv.transfer.proto.v4.Offer
import com.betterhv.transfer.proto.v4.PairingFrame
import com.betterhv.transfer.proto.v4.Ping
import com.betterhv.transfer.proto.v4.PushOffer
import com.betterhv.transfer.proto.v4.PushState
import com.betterhv.transfer.proto.v4.Request
import com.betterhv.transfer.proto.v4.UpdateCheck
import com.betterhv.transfer.proto.v4.UpdateResult
import com.betterhv.transfer.proto.v4.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.util.UUID

/** Result of [NoteLinkClient.handshake]; the caller owns [connection]. */
class NoteLinkSession(
  val connection: PeerConnection,
  val peerCapabilities: CapabilitySet,
  val pairingKey: ByteArray,
  val localDeviceId: String,
) {
  private val mutableCounts = MutableStateFlow<ContentCounts?>(null)

  /** Latest queue counts the peer announced; null until the first `QueueChanged`. */
  val counts: StateFlow<ContentCounts?> = mutableCounts.asStateFlow()

  private val mutableCapabilities = MutableStateFlow(peerCapabilities)

  /** Latest peer capabilities; updated when the peer reports new LAN addresses. */
  val capabilityUpdates: StateFlow<CapabilitySet> = mutableCapabilities.asStateFlow()

  var capabilities: CapabilitySet
    get() = mutableCapabilities.value
    internal set(value) {
      mutableCapabilities.value = value
    }

  internal fun onNotification(frame: Frame) {
    when (frame.bodyCase) {
      Frame.BodyCase.QUEUE_CHANGED -> mutableCounts.value = NoteLinkProto.counts(frame.queueChanged)
      Frame.BodyCase.CAPABILITY_UPDATE -> capabilities = frame.capabilityUpdate.capabilities
      else -> Unit
    }
  }

  val peerDeviceId: String get() = connection.peerDeviceId
}

/** Phase timestamps of one transfer, in milliseconds since its start. */
data class TransferTiming(
  val link: String,
  val leasedMillis: Long,
  val firstByteMillis: Long,
  val lastByteMillis: Long,
  val bytes: Long,
)

class FetchedItem(val offer: Offer, val inline: ByteArray?, val timing: TransferTiming)

/**
 * The peer leased [offer] to this Note but the stream broke. The lease stays valid for its TTL,
 * so the caller can resume with [NoteLinkClient.download] on a new session.
 */
class InterruptedFetchException(val offer: Offer, cause: Throwable) :
  java.io.IOException("Transfer of ${offer.displayName.ifBlank { "item" }} was interrupted: ${cause.message}", cause)

/**
 * Client-side NoteLink protocol (the Note tablet). Transport-neutral: the caller supplies the BLE
 * control [RecordLink]; TCP links are dialed here from the peer's advertised endpoints.
 */
object NoteLinkClient {
  private const val HANDSHAKE_TIMEOUT_MILLIS = 6_000L
  private const val PAIRING_STEP_TIMEOUT_MILLIS = 15_000L
  private const val LAN_ATTACH_TIMEOUT_MILLIS = 2_500L
  private const val STREAM_IDLE_MILLIS = 20_000L
  private const val ATTACH_GRACE_MILLIS = 500L
  private const val P2P_PREPARE_MILLIS = 45_000L

  /** Runs SRP pairing over a fresh BLE link. The link is not closed. */
  suspend fun pair(link: RecordLink, initiator: PairingInitiator): PairingOutcome {
    val reassembler = PairingRecords.Reassembler()
    var messageId = 1
    suspend fun exchange(frame: PairingFrame): PairingFrame {
      PairingRecords.encode(frame, false, messageId++, link.maxRecordBytes - RecordCodec.HEADER_BYTES)
        .forEach { link.send(it) }
      return withTimeout(PAIRING_STEP_TIMEOUT_MILLIS) {
        while (true) {
          val record = link.receive() ?: throw PairingException(ErrorCode.ERROR_CONNECTION_REFUSED, "Link closed")
          if (!PairingRecords.isPairingRecord(record)) continue
          val reply = reassembler.add(record) ?: continue
          if (reply.bodyCase == PairingFrame.BodyCase.RESULT && reply.result.error != ErrorCode.ERROR_CODE_UNSPECIFIED) {
            throw PairingException(reply.result.error, describe(reply.result.error), reply.result.attemptsLeft)
          }
          return@withTimeout reply
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
      }
    }
    val challenge = exchange(PairingFrame.newBuilder().setInit(initiator.start()).build())
    if (challenge.bodyCase != PairingFrame.BodyCase.CHALLENGE) {
      throw PairingException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Expected a pairing challenge")
    }
    val confirm = initiator.onChallenge(challenge.challenge)
    val result = exchange(PairingFrame.newBuilder().setConfirm(confirm).build())
    if (result.bodyCase != PairingFrame.BodyCase.RESULT) {
      throw PairingException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Expected a pairing result")
    }
    return initiator.onResult(result.result)
  }

  /**
   * One-round-trip handshake over [link], then attaches the link to a new [PeerConnection].
   * TCP links are attached separately with [attachTcp].
   */
  suspend fun handshake(
    scope: CoroutineScope,
    link: RecordLink,
    pairingKey: ByteArray,
    localDeviceId: String,
    peerDeviceId: String,
    capabilities: CapabilitySet,
    onClosed: (PeerConnection) -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
    onLinkClosed: (String, String) -> Unit = { _, _ -> },
  ): NoteLinkSession {
    val client = ClientHandshake(
      pairingKey = pairingKey,
      deviceId = localDeviceId,
      capabilities = capabilities,
      request = Request.newBuilder().setPing(Ping.newBuilder().setSentAtMs(clock())).build(),
      maxFragmentBody = link.maxRecordBytes - RecordCodec.HELLO_HEADER_BYTES,
      clock = clock,
    )
    client.start().forEach { link.send(it) }
    val reply = ArrayList<ByteArray>()
    val established = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MILLIS) {
      while (true) {
        val record = link.receive() ?: throw HandshakeException(ErrorCode.ERROR_CONNECTION_REFUSED, "Link closed")
        if (PairingRecords.isPairingRecord(record)) {
          val frame = PairingRecords.Reassembler().add(record) ?: continue
          val code = frame.result.error
          throw HandshakeException(code, describe(code), retryable = code == ErrorCode.ERROR_CLOCK_SKEW)
        }
        val fragment = RecordCodec.parse(record)
        if (!fragment.header.isHello || !fragment.header.isResponse) continue
        if (fragment.index == 0) reply.clear()
        reply += record
        if (reply.size == fragment.count) return@withTimeoutOrNull client.complete(reply.toList())
      }
      @Suppress("UNREACHABLE_CODE")
      null
    } ?: throw HandshakeException(ErrorCode.ERROR_CONNECTION_TIMEOUT, "The peer did not answer the handshake")
    val session = ProtocolSession(
      peerDeviceId = peerDeviceId,
      sessionId = SessionKeys.sessionIdLong(established.sessionId),
      keys = established.keys,
      capabilities = established.capabilities,
      initiator = true,
    )
    lateinit var result: NoteLinkSession
    val connection = PeerConnection(
      session = session,
      parentScope = scope,
      responder = { exchange, _ -> exchange.fail(ErrorCode.ERROR_UNSUPPORTED_MODE, "Note serves no requests") },
      onNotification = { result.onNotification(it) },
      onClosed = onClosed,
      onLinkClosed = onLinkClosed,
      clock = clock,
    )
    result = NoteLinkSession(connection, established.serverHello.capabilities, pairingKey, localDeviceId)
    connection.attach(link, session.handshakeLinkCipher(link.maxRecordBytes))
    return result
  }

  /**
   * Dials every advertised LAN candidate in parallel and attaches the first that answers.
   * Returns the label of the attached link, or null.
   */
  suspend fun attachTcp(
    session: NoteLinkSession,
    candidates: List<Pair<String, Int>>,
    label: String = PeerConnection.LABEL_LAN,
    timeoutMillis: Long = LAN_ATTACH_TIMEOUT_MILLIS,
    onDialFailure: (String, Throwable) -> Unit = { _, _ -> },
  ): String? {
    if (candidates.isEmpty()) return null
    val connection = session.connection
    // Attempts run in their own scope: the first link that authenticates wins at once, and the
    // slower candidates finish (or time out) in the background instead of delaying the caller.
    val dialScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val attempts = candidates.distinct().map { (host, port) ->
      dialScope.async {
        runCatching {
          TcpAttach.dial(
            host = host,
            port = port,
            label = label,
            session = connection.session,
            pairingKey = session.pairingKey,
            localDeviceId = session.localDeviceId,
            peerDeviceId = connection.peerDeviceId,
            connectTimeoutMillis = timeoutMillis.toInt(),
          )
        }.onFailure { onDialFailure("$host:$port", it) }.getOrNull()
      }
    }
    val winner = withTimeoutOrNull(timeoutMillis + ATTACH_GRACE_MILLIS) {
      val pending = attempts.toMutableList()
      while (pending.isNotEmpty()) {
        val (deferred, value) = select {
          pending.forEach { candidate -> candidate.onAwait { candidate to it } }
        }
        pending.remove(deferred)
        if (value != null) return@withTimeoutOrNull value
      }
      null
    }
    dialScope.launch {
      attempts.forEach { attempt ->
        val pair = runCatching { attempt.await() }.getOrNull()
        if (pair != null && pair !== winner) pair.first.close()
      }
    }
    if (winner == null) {
      dialScope.cancel()
      return null
    }
    connection.attach(winner.first, winner.second)
    return label
  }

  fun lanCandidates(capabilities: CapabilitySet): List<Pair<String, Int>> = buildList {
    if (capabilities.hasLan() && capabilities.lan.host.isNotBlank()) add(capabilities.lan.host to capabilities.lan.port)
    capabilities.lanCandidatesList.forEach { if (it.host.isNotBlank()) add(it.host to it.port) }
  }.distinct()

  /**
   * Leases the next [kind] item and streams it into [sink] on one exchange: a single round trip
   * plus the payload. Returns null when the peer's queue has no such item.
   */
  suspend fun fetch(
    session: NoteLinkSession,
    kind: ContentKind,
    sink: (Offer) -> ByteSink,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
    clock: () -> Long = System::currentTimeMillis,
  ): FetchedItem? {
    val started = clock()
    session.connection.open().use { exchange ->
      val result = exchange.request(
        Frame.newBuilder().setFetchRequest(
          FetchRequest.newBuilder()
            .setKind(NoteLinkProto.toProto(kind))
            .setMaxItems(1)
            .setFlags(NoteLinkServer.FETCH_STREAM),
        ).build(),
      )
      if (result.bodyCase != Frame.BodyCase.FETCH_RESULT) {
        throw ExchangeException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Expected a fetch result")
      }
      val offer = result.fetchResult.offersList.firstOrNull() ?: return null
      val leased = clock() - started
      if (!offer.inlineData.isEmpty) {
        val bytes = offer.inlineData.toByteArray()
        if (!java.security.MessageDigest.isEqual(com.betterhv.transfer.core.TransferCrypto.sha256(bytes), offer.sha256.toByteArray())) {
          throw ExchangeException(ErrorCode.ERROR_INTEGRITY_FAILED, "Inline payload failed verification")
        }
        return FetchedItem(offer, bytes, TransferTiming(exchange.linkLabel, leased, leased, leased, bytes.size.toLong()))
      }
      val target = sink(offer)
      val (firstByte, bytes) = try {
        receiveStream(exchange, offer, target, onProgress, clock, started)
      } catch (error: kotlinx.coroutines.CancellationException) {
        target.abort()
        throw error
      } catch (error: ExchangeException) {
        target.abort()
        if (error.code == ErrorCode.ERROR_INTEGRITY_FAILED) throw error
        throw InterruptedFetchException(offer, error)
      } catch (error: Throwable) {
        target.abort()
        throw error
      }
      return FetchedItem(offer, null, TransferTiming(exchange.linkLabel, leased, firstByte, clock() - started, bytes))
    }
  }

  private suspend fun receiveStream(
    exchange: Exchange,
    offer: Offer,
    sink: ByteSink,
    onProgress: (Long, Long) -> Unit,
    clock: () -> Long,
    started: Long,
  ): Pair<Long, Long> {
    var received = 0L
    var firstByte = -1L
    while (true) {
      val frame = exchange.receive(STREAM_IDLE_MILLIS)
      when (frame.bodyCase) {
        Frame.BodyCase.STREAM_DATA -> {
          if (firstByte < 0) firstByte = clock() - started
          if (frame.streamData.offset != received) {
            throw ExchangeException(ErrorCode.ERROR_STREAM_RESET, "Stream gap at $received")
          }
          sink.write(frame.streamData.data.toByteArray())
          received += frame.streamData.data.size()
          if (received > offer.byteLength) throw ExchangeException(ErrorCode.ERROR_INTEGRITY_FAILED, "Too many bytes")
          onProgress(received, offer.byteLength)
        }
        Frame.BodyCase.STREAM_COMPLETE -> {
          if (!sink.complete(offer.sha256.toByteArray(), offer.byteLength)) {
            throw ExchangeException(ErrorCode.ERROR_INTEGRITY_FAILED, "Payload failed SHA-256 verification")
          }
          return (if (firstByte < 0) clock() - started else firstByte) to received
        }
        else -> throw ExchangeException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Unexpected ${frame.bodyCase}")
      }
    }
  }

  /** Commits or releases leased items; returns true when the peer applied every action. */
  suspend fun lease(session: NoteLinkSession, itemId: UUID, action: LeaseKind): Boolean {
    session.connection.open().use { exchange ->
      val reply = exchange.request(
        Frame.newBuilder().setLeaseUpdate(
          LeaseUpdate.newBuilder().addActions(
            LeaseAction.newBuilder()
              .setItemId(com.google.protobuf.ByteString.copyFrom(NoteLinkProto.uuidBytes(itemId)))
              .setAction(action),
          ),
        ).build(),
      )
      return reply.bodyCase == Frame.BodyCase.LEASE_UPDATE &&
        reply.leaseUpdate.actionsList.all { it.result == NoteLinkServer.RESULT_OK }
    }
  }

  /** Pushes an export; returns false when the peer already holds it. */
  suspend fun push(
    session: NoteLinkSession,
    offer: PushOffer,
    open: () -> InputStream,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
  ): Boolean {
    session.connection.open().use { exchange ->
      val status = exchange.request(Frame.newBuilder().setPushOffer(offer).build())
      if (status.bodyCase != Frame.BodyCase.PUSH_STATUS) {
        throw ExchangeException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Expected a push status")
      }
      when (status.pushStatus.state) {
        PushState.PUSH_COMPLETE -> return false
        PushState.PUSH_ACCEPTED -> Unit
        else -> throw ExchangeException(ErrorCode.ERROR_BUSY, "NoteLink rejected the export")
      }
      val sha = offer.sha256.toByteArray()
      exchange.send(NoteLinkProto.streamOpen(offer.itemId, offer.byteLength, sha, 0, NoteLinkProto.FLAG_UPLOAD))
      val buffer = ByteArray(exchange.maxChunkBytes)
      var sent = 0L
      withContext(Dispatchers.IO) {
        open().use { input ->
          while (sent < offer.byteLength) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), offer.byteLength - sent).toInt())
            if (read < 0) throw ExchangeException(ErrorCode.ERROR_INTERNAL, "Export file ended early")
            exchange.send(NoteLinkProto.streamData(buffer, read, sent))
            sent += read
            onProgress(sent, offer.byteLength)
          }
        }
      }
      val done = exchange.request(NoteLinkProto.streamComplete(sha, sent), STREAM_IDLE_MILLIS)
      if (done.bodyCase != Frame.BodyCase.STREAM_COMPLETE) {
        throw ExchangeException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Export was not confirmed")
      }
      return true
    }
  }

  /** Asks the peer for a newer build; PREPARING results are reported through [onProgress]. */
  suspend fun checkUpdate(
    session: NoteLinkSession,
    check: UpdateCheck,
    onProgress: (UpdateResult) -> Unit = {},
  ): UpdateResult {
    session.connection.open().use { exchange ->
      exchange.send(Frame.newBuilder().setUpdateCheck(check).build())
      while (true) {
        val frame = exchange.receive(STREAM_IDLE_MILLIS)
        if (frame.bodyCase != Frame.BodyCase.UPDATE_RESULT) {
          throw ExchangeException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Expected an update result")
        }
        if (frame.updateResult.state != UpdateState.UPDATE_PREPARING) return frame.updateResult
        onProgress(frame.updateResult)
      }
    }
  }

  /** Streams an item that is already leased (e.g. an update package) into [sink]. */
  suspend fun download(
    session: NoteLinkSession,
    offer: Offer,
    sink: ByteSink,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
    clock: () -> Long = System::currentTimeMillis,
  ): TransferTiming {
    val started = clock()
    session.connection.open().use { exchange ->
      exchange.send(NoteLinkProto.streamOpen(offer.itemId, offer.byteLength, offer.sha256.toByteArray(), 0))
      val (firstByte, bytes) = try {
        receiveStream(exchange, offer, sink, onProgress, clock, started)
      } catch (error: Throwable) {
        sink.abort()
        throw error
      }
      return TransferTiming(exchange.linkLabel, 0, firstByte, clock() - started, bytes)
    }
  }

  /** Asks the peer to host a Wi-Fi Direct group; returns it once the group is ready. */
  suspend fun requestP2p(session: NoteLinkSession, timeoutMillis: Long = P2P_PREPARE_MILLIS): com.betterhv.transfer.proto.v4.P2pGroup {
    session.connection.open().use { exchange ->
      val request = CapabilitySet.newBuilder()
        .setP2P(com.betterhv.transfer.proto.v4.P2pGroup.newBuilder().setState(com.betterhv.transfer.proto.v4.P2pState.P2P_STATE_STARTING))
        .build()
      val reply = exchange.request(
        Frame.newBuilder().setCapabilityUpdate(com.betterhv.transfer.proto.v4.CapabilityUpdate.newBuilder().setCapabilities(request)).build(),
        timeoutMillis,
      )
      if (reply.bodyCase != Frame.BodyCase.CAPABILITY_UPDATE || !reply.capabilityUpdate.capabilities.hasP2P()) {
        throw ExchangeException(ErrorCode.ERROR_UNSUPPORTED_MODE, "Peer did not start Wi-Fi Direct")
      }
      session.capabilities = reply.capabilityUpdate.capabilities
      return reply.capabilityUpdate.capabilities.p2P
    }
  }

  /** Re-reads the peer's capabilities, e.g. after it reported new LAN addresses. */
  suspend fun refreshCapabilities(session: NoteLinkSession): CapabilitySet {
    session.connection.open().use { exchange ->
      val reply = exchange.request(
        Frame.newBuilder().setCapabilityUpdate(com.betterhv.transfer.proto.v4.CapabilityUpdate.getDefaultInstance()).build(),
      )
      if (reply.bodyCase == Frame.BodyCase.CAPABILITY_UPDATE) session.capabilities = reply.capabilityUpdate.capabilities
      return session.capabilities
    }
  }

  fun describe(code: ErrorCode): String = when (code) {
    ErrorCode.ERROR_UNKNOWN_PAIRING -> "NoteLink no longer knows this Note; pair again"
    ErrorCode.ERROR_AUTHENTICATION_FAILED -> "Wrong pairing code"
    ErrorCode.ERROR_RATE_LIMITED -> "Too many wrong codes; start pairing again on NoteLink"
    ErrorCode.ERROR_TICKET_EXPIRED -> "The pairing code expired; start pairing again"
    ErrorCode.ERROR_PENDING_APPROVAL -> "Enter the code on NoteLink first"
    ErrorCode.ERROR_CLOCK_SKEW -> "The device clocks differ too much"
    ErrorCode.ERROR_REPLAY_REJECTED -> "Handshake was rejected as a replay"
    else -> "NoteLink rejected the connection (${code.name})"
  }
}
