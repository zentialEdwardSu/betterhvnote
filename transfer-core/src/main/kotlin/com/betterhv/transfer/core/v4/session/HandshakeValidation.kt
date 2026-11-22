package com.betterhv.transfer.core.v4.session

import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.ClientHello
import com.betterhv.transfer.proto.v4.ContentKind
import com.betterhv.transfer.proto.v4.ErrorCode

/** Result of validating a `ClientHello` before any session state is created. */
sealed interface HandshakeVerdict {
  data object Accept : HandshakeVerdict
  data class Reject(
    val code: ErrorCode,
    val message: String,
    val serverTimeMs: Long? = null,
    val retryable: Boolean = false,
  ) : HandshakeVerdict
}

/**
 * Bounded replay protection for handshakes.
 *
 * The nonce cache is in-memory; [lastClientTimeMs] is persisted by the caller across process
 * restarts (see design §15.4), so a captured hello cannot be replayed after a restart.
 */
class HandshakeReplayGuard(
  private val capacity: Int = 256,
  private val clockSkewMillis: Long = 120_000L,
) {
  init {
    require(capacity > 0)
  }

  private val nonces = object : LinkedHashMap<String, Long>(capacity, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > capacity
  }
  private val lastClientTime = HashMap<String, Long>()

  @Synchronized
  fun check(hello: ClientHello, clientNonce: ByteArray, nowMs: Long): HandshakeVerdict {
    if (hello.deviceId.isBlank()) {
      return HandshakeVerdict.Reject(ErrorCode.ERROR_AUTHENTICATION_FAILED, "Missing device id")
    }
    if (hello.major != SUPPORTED_MAJOR) {
      return HandshakeVerdict.Reject(
        ErrorCode.ERROR_UNSUPPORTED_PROTOCOL,
        "Unsupported major ${hello.major}",
      )
    }
    if (clientNonce.size != NONCE_BYTES) {
      return HandshakeVerdict.Reject(ErrorCode.ERROR_AUTHENTICATION_FAILED, "Invalid handshake nonce")
    }
    val skew = nowMs - hello.clientTimeMs
    if (skew > clockSkewMillis || skew < -clockSkewMillis) {
      return HandshakeVerdict.Reject(
        ErrorCode.ERROR_CLOCK_SKEW,
        "Client clock is outside the handshake window",
        serverTimeMs = nowMs,
        retryable = true,
      )
    }
    val nonce = clientNonce.joinToString("") { "%02x".format(it) }
    if (nonces.containsKey(nonce)) {
      return HandshakeVerdict.Reject(ErrorCode.ERROR_REPLAY_REJECTED, "Replayed handshake nonce")
    }
    nonces[nonce] = nowMs
    val previous = lastClientTime[hello.deviceId]
    lastClientTime[hello.deviceId] = maxOf(previous ?: Long.MIN_VALUE, hello.clientTimeMs)
    return HandshakeVerdict.Accept
  }

  @Synchronized
  fun reset() {
    nonces.clear()
    lastClientTime.clear()
  }

  companion object {
    const val SUPPORTED_MAJOR = 4
    const val SUPPORTED_MINOR = 0
    const val NONCE_BYTES = 16
  }
}

/** Limits agreed by both peers. Endpoints stay in each peer's own capability set. */
data class NegotiatedCapabilities(
  val transports: Int,
  val maxStreams: Int,
  val maxRecordBytes: Int,
  val maxInflightBytes: Long,
  val kinds: List<ContentKind>,
  val features: Int,
)

object CapabilityNegotiator {
  const val DEFAULT_MAX_STREAMS = 8
  const val DEFAULT_MAX_RECORD_BYTES = 64 * 1024
  const val DEFAULT_MAX_INFLIGHT_BYTES = 4L * 1024L * 1024L

  fun negotiate(local: CapabilitySet, remote: CapabilitySet): NegotiatedCapabilities {
    val transports = local.transports and remote.transports
    val localKinds = local.kindsList
    val remoteKinds = remote.kindsList
    val kinds = when {
      localKinds.isEmpty() -> remoteKinds
      remoteKinds.isEmpty() -> localKinds
      else -> localKinds.filter { it in remoteKinds }
    }
    return NegotiatedCapabilities(
      transports = transports,
      maxStreams = minPositive(local.maxStreams, remote.maxStreams, DEFAULT_MAX_STREAMS),
      maxRecordBytes = minPositive(local.maxRecordBytes, remote.maxRecordBytes, DEFAULT_MAX_RECORD_BYTES),
      maxInflightBytes = minPositive(local.maxInflightBytes, remote.maxInflightBytes, DEFAULT_MAX_INFLIGHT_BYTES),
      kinds = kinds,
      features = local.features and remote.features,
    )
  }

  /** Returns the highest minor both sides can speak, or null when the ranges do not overlap. */
  fun chooseMinor(clientMin: Int, clientMax: Int, serverSupported: Int = 0): Int? {
    if (clientMin > clientMax) return null
    if (serverSupported < clientMin) return null
    return minOf(serverSupported, clientMax)
  }

  private fun minPositive(first: Int, second: Int, fallback: Int): Int {
    val values = listOf(first, second).filter { it > 0 }
    return values.minOrNull() ?: fallback
  }

  private fun minPositive(first: Long, second: Long, fallback: Long): Long {
    val values = listOf(first, second).filter { it > 0 }
    return values.minOrNull() ?: fallback
  }
}
