package com.betterhv.transfer.core.v4.session

import com.betterhv.transfer.proto.v4.ErrorCode
import com.betterhv.transfer.proto.v4.Frame
import com.betterhv.transfer.proto.v4.Ping
import com.betterhv.transfer.proto.v4.ProtocolError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The exchange cannot continue: its link closed, the peer reset it, or it timed out. */
class ExchangeException(val code: ErrorCode, message: String, cause: Throwable? = null) :
  IOException(message, cause)

/** Serves an exchange the peer opened; [first] is the frame that opened it. */
fun interface ExchangeResponder {
  suspend fun serve(exchange: Exchange, first: Frame)
}

/**
 * One request/response or stream conversation, identified by the record header's stream id.
 *
 * The initiator allocates ids (odd for the handshake client, even for the server), so both peers
 * can open exchanges without coordination. Stream id 0 carries unsolicited notifications. An
 * exchange is bound to the link it was opened on; if that link closes the exchange fails and the
 * caller retries on another link, which keeps every exchange strictly ordered.
 */
class Exchange internal constructor(
  val id: Int,
  private val owner: PeerConnection,
  internal val link: PeerConnection.AttachedLink,
) : AutoCloseable {
  private val inbound = Channel<Frame>(INBOUND_CAPACITY)

  val linkLabel: String get() = link.link.label

  /** Largest `StreamData.data` chunk that fits one message on this exchange's link. */
  val maxChunkBytes: Int get() = (link.cipher.maxFrameBytes - CHUNK_OVERHEAD).coerceIn(MIN_CHUNK, MAX_CHUNK)

  suspend fun send(frame: Frame) {
    try {
      link.outbound.send(id to frame)
    } catch (error: ClosedSendChannelException) {
      throw ExchangeException(ErrorCode.ERROR_CONNECTION_REFUSED, "Link ${link.link.label} closed", error)
    }
  }

  /** Next frame of this exchange; a peer [ProtocolError] or stream reset is thrown. */
  suspend fun receive(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Frame {
    val result = withTimeoutOrNull(timeoutMillis) { inbound.receiveCatching() }
      ?: throw ExchangeException(ErrorCode.ERROR_STALLED, "No reply within $timeoutMillis ms")
    val frame = result.getOrNull() ?: throw ExchangeException(
      ErrorCode.ERROR_CONNECTION_REFUSED,
      "Link ${link.link.label} closed",
      result.exceptionOrNull(),
    )
    when (frame.bodyCase) {
      Frame.BodyCase.ERROR -> throw ExchangeException(frame.error.code, frame.error.message.ifBlank { "Peer error" })
      Frame.BodyCase.STREAM_RESET -> throw ExchangeException(
        frame.streamReset.code,
        frame.streamReset.reason.ifBlank { "Stream reset" },
      )
      else -> return frame
    }
  }

  /** Sends one frame and waits for the next one. */
  suspend fun request(frame: Frame, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Frame {
    send(frame)
    return receive(timeoutMillis)
  }

  suspend fun fail(code: ErrorCode, message: String) {
    runCatching {
      send(Frame.newBuilder().setError(ProtocolError.newBuilder().setCode(code).setMessage(message)).build())
    }
  }

  internal suspend fun deliver(frame: Frame) {
    runCatching { inbound.send(frame) }
  }

  internal fun abort(cause: Throwable?) {
    inbound.close(cause)
  }

  override fun close() {
    inbound.close()
    owner.release(this)
  }

  companion object {
    const val DEFAULT_TIMEOUT_MILLIS = 10_000L
    private const val INBOUND_CAPACITY = 64
    private const val CHUNK_OVERHEAD = 48
    private const val MIN_CHUNK = 256
    private const val MAX_CHUNK = 32 * 1024
  }
}

/**
 * All links of one authenticated session: the BLE control link and any number of TCP links
 * (LAN or Wi-Fi Direct). Each link has its own reader and writer coroutine, so a slow BLE
 * notification never delays a LAN stream and no link is ever polled.
 */
class PeerConnection(
  val session: ProtocolSession,
  parentScope: CoroutineScope,
  private val responder: ExchangeResponder,
  private val onNotification: suspend (Frame) -> Unit = {},
  private val onClosed: (PeerConnection) -> Unit = {},
  /** Diagnostics only: a link went away and why. */
  private val onLinkClosed: (label: String, reason: String) -> Unit = { _, _ -> },
  private val keepaliveMillis: Long = DEFAULT_KEEPALIVE_MILLIS,
  private val halfOpenMillis: Long = DEFAULT_HALF_OPEN_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
  private val job = SupervisorJob(parentScope.coroutineContext[Job])
  private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.IO)
  private val nextId = AtomicInteger(if (session.initiator) 1 else 2)
  private val local = ConcurrentHashMap<Int, Exchange>()
  private val remote = ConcurrentHashMap<Int, Exchange>()
  private val attached = MutableStateFlow<List<AttachedLink>>(emptyList())
  private val labels = MutableStateFlow<List<String>>(emptyList())
  @Volatile private var highestRemoteId = 0
  @Volatile private var closed = false
  private val closedSignal = kotlinx.coroutines.CompletableDeferred<Unit>()

  val peerDeviceId: String get() = session.peerDeviceId

  /** Labels of the links that are currently up, fastest first. */
  val links: StateFlow<List<String>> = labels.asStateFlow()

  val isOpen: Boolean get() = !closed

  inner class AttachedLink internal constructor(val link: RecordLink, val cipher: LinkCipher) {
    internal val outbound = Channel<Pair<Int, Frame>>(OUTBOUND_CAPACITY)
    @Volatile internal var lastInbound = clock()
    @Volatile internal var lastOutbound = clock()
    @Volatile internal var reason: String? = null
    internal val done = Job(job)
  }

  init {
    scope.launch { keepalive() }
  }

  /** Starts reading and writing [link]. The returned job completes when the link is detached. */
  fun attach(link: RecordLink, cipher: LinkCipher): Job {
    val entry = AttachedLink(link, cipher)
    if (closed) {
      link.close()
      entry.done.complete()
      return entry.done
    }
    synchronized(this) {
      attached.value = (attached.value + entry).sortedBy { rank(it.link.label) }
      labels.value = attached.value.map { it.link.label }
    }
    scope.launch { writeLoop(entry) }
    scope.launch { readLoop(entry) }
    return entry.done
  }

  fun hasLink(label: String): Boolean = attached.value.any { it.link.label == label }

  /** Suspends until the session closed for any reason. */
  suspend fun awaitClosed() = closedSignal.await()

  /** Milliseconds since any link last received a record; [Long.MAX_VALUE] without links. */
  fun idleMillis(): Long = attached.value.minOfOrNull { clock() - it.lastInbound } ?: Long.MAX_VALUE

  /**
   * One ping round trip on the fastest link. A device that slept may still hold a session whose
   * radio links are dead; this finds out in [timeoutMillis] instead of during a transfer.
   */
  suspend fun probe(timeoutMillis: Long): Boolean = runCatching {
    open().use { exchange ->
      exchange.request(Frame.newBuilder().setPing(Ping.newBuilder().setSentAtMs(clock())).build(), timeoutMillis)
        .bodyCase == Frame.BodyCase.PING
    }
  }.getOrDefault(false)

  /** Drops every link with [label], e.g. when the Wi-Fi network changed. */
  fun closeLink(label: String) {
    attached.value.filter { it.link.label == label }.forEach {
      it.reason = it.reason ?: "closed locally"
      detach(it)
    }
  }

  /** Opens an exchange on [label] when given, otherwise on the fastest link. */
  fun open(label: String? = null): Exchange {
    val entry = pick(label) ?: throw ExchangeException(
      ErrorCode.ERROR_CONNECTION_REFUSED,
      if (label == null) "No link is up" else "Link $label is not up",
    )
    val id = nextId.getAndAdd(2)
    return Exchange(id, this, entry).also { local[id] = it }
  }

  /** Sends a stream-0 notification on the fastest link; false when no link is up. */
  suspend fun notify(frame: Frame): Boolean {
    val entry = pick(null) ?: return false
    return runCatching { entry.outbound.send(0 to frame) }.isSuccess
  }

  internal fun release(exchange: Exchange) {
    local.remove(exchange.id, exchange)
    remote.remove(exchange.id, exchange)
  }

  private fun pick(label: String?): AttachedLink? =
    attached.value.firstOrNull { label == null || it.link.label == label }

  private suspend fun writeLoop(entry: AttachedLink) {
    try {
      for ((streamId, frame) in entry.outbound) {
        for (record in entry.cipher.seal(streamId, frame)) entry.link.send(record)
        entry.lastOutbound = clock()
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      // A failed write means the link is gone; the reader observes the same and detaches.
      entry.reason = entry.reason ?: "write: ${error.javaClass.simpleName}: ${error.message}"
    } finally {
      detach(entry)
    }
  }

  private suspend fun readLoop(entry: AttachedLink) {
    try {
      while (scope.isActive) {
        val record = entry.link.receive() ?: run {
          entry.reason = entry.reason ?: "peer closed"
          null
        } ?: break
        entry.lastInbound = clock()
        val inbound = entry.cipher.open(record) ?: continue
        dispatch(entry, inbound)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      // Authentication, replay and framing failures all end the link; other links continue.
      entry.reason = entry.reason ?: "read: ${error.javaClass.simpleName}: ${error.message}"
    } finally {
      detach(entry)
    }
  }

  private suspend fun dispatch(entry: AttachedLink, inbound: InboundFrame) {
    val id = inbound.streamId
    val frame = inbound.frame
    if (id == 0) {
      if (frame.bodyCase != Frame.BodyCase.PING) onNotification(frame)
      return
    }
    val mine = (id % 2 == 1) == session.initiator
    if (mine) {
      local[id]?.deliver(frame)
      return
    }
    remote[id]?.let {
      it.deliver(frame)
      return
    }
    if (id <= highestRemoteId) return
    highestRemoteId = id
    val exchange = Exchange(id, this, entry)
    remote[id] = exchange
    scope.launch {
      try {
        responder.serve(exchange, frame)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: ExchangeException) {
        exchange.fail(error.code, error.message ?: "Exchange failed")
      } catch (error: Throwable) {
        exchange.fail(ErrorCode.ERROR_INTERNAL, error.message ?: "Exchange failed")
      } finally {
        exchange.close()
      }
    }
  }

  private fun detach(entry: AttachedLink) {
    val remaining = synchronized(this) {
      if (entry !in attached.value) return
      attached.value = attached.value - entry
      labels.value = attached.value.map { it.link.label }
      attached.value
    }
    entry.outbound.close()
    runCatching { entry.link.close() }
    runCatching { onLinkClosed(entry.link.label, entry.reason ?: "unknown") }
    val lost = ExchangeException(ErrorCode.ERROR_CONNECTION_REFUSED, "Link ${entry.link.label} closed")
    (local.values + remote.values).filter { it.link === entry }.forEach { it.abort(lost) }
    entry.done.complete()
    if (remaining.isEmpty()) close()
  }

  private suspend fun keepalive() {
    val tick = (keepaliveMillis / 2).coerceAtLeast(MIN_TICK_MILLIS)
    while (scope.isActive) {
      delay(tick)
      val now = clock()
      for (entry in attached.value) {
        if (now - entry.lastInbound >= halfOpenMillis) {
          entry.reason = entry.reason ?: "half-open ${now - entry.lastInbound} ms"
          runCatching { entry.link.close() }
          detach(entry)
        } else if (now - entry.lastOutbound >= keepaliveMillis) {
          entry.lastOutbound = now
          entry.outbound.trySend(0 to Frame.newBuilder().setPing(Ping.newBuilder().setSentAtMs(now)).build())
        }
      }
    }
  }

  override fun close() {
    val entries = synchronized(this) {
      if (closed) return
      closed = true
      attached.value.also {
        attached.value = emptyList()
        labels.value = emptyList()
      }
    }
    val lost = ExchangeException(ErrorCode.ERROR_CONNECTION_REFUSED, "Session closed")
    entries.forEach { entry ->
      entry.outbound.close()
      runCatching { entry.link.close() }
      entry.done.complete()
    }
    (local.values + remote.values).forEach { it.abort(lost) }
    local.clear()
    remote.clear()
    scope.cancel()
    closedSignal.complete(Unit)
    onClosed(this)
  }

  companion object {
    const val DEFAULT_KEEPALIVE_MILLIS = 8_000L
    const val DEFAULT_HALF_OPEN_MILLIS = 25_000L
    private const val OUTBOUND_CAPACITY = 32
    private const val MIN_TICK_MILLIS = 50L

    /** Lower is preferred: TCP has a few ms of latency, a BLE connection event 7.5–30 ms. */
    fun rank(label: String): Int = when (label) {
      LABEL_LAN -> 0
      LABEL_P2P -> 1
      else -> 2
    }

    const val LABEL_BLE = "ble"
    const val LABEL_LAN = "lan"
    const val LABEL_P2P = "p2p"

    /** Key-derivation label shared by every TCP link; the display label may be lan or p2p. */
    const val LABEL_TCP = "tcp"
  }
}
