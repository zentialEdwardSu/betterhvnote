package com.betterhv.transfer.core.v4.error

import com.betterhv.transfer.proto.v4.ErrorCode
import java.util.Random

enum class RetryDisposition { RETRY, RETRY_ONCE, FATAL }

/**
 * Single place that decides whether an error may be retried. Security and protocol-version
 * failures are fatal; clock skew and stale-handle style failures get exactly one retry; everything
 * else follows the bounded retry policy.
 */
object FailureClassifier {
  fun classify(code: ErrorCode): RetryDisposition = when (code) {
    ErrorCode.ERROR_AUTHENTICATION_FAILED,
    ErrorCode.ERROR_REPLAY_REJECTED,
    ErrorCode.ERROR_UNSUPPORTED_PROTOCOL,
    ErrorCode.ERROR_PROTOCOL_MISMATCH,
    ErrorCode.ERROR_UNKNOWN_PAIRING,
    ErrorCode.ERROR_INTEGRITY_FAILED,
    ErrorCode.ERROR_INVALID_ENDPOINT,
    -> RetryDisposition.FATAL

    ErrorCode.ERROR_CLOCK_SKEW,
    ErrorCode.ERROR_TICKET_EXPIRED,
    -> RetryDisposition.RETRY_ONCE

    else -> RetryDisposition.RETRY
  }

  fun maxAttempts(disposition: RetryDisposition): Int = when (disposition) {
    RetryDisposition.FATAL -> 1
    RetryDisposition.RETRY_ONCE -> 2
    RetryDisposition.RETRY -> 3
  }
}

/** Exponential backoff with bounded jitter; the cap keeps multi-device reconnects from stampeding. */
class RetryPolicy(
  private val baseDelayMillis: Long = 250,
  private val maxDelayMillis: Long = 2_000,
  private val jitterRatio: Double = 0.2,
  private val random: Random = Random(),
) {
  init {
    require(baseDelayMillis > 0)
    require(maxDelayMillis >= baseDelayMillis)
    require(jitterRatio in 0.0..1.0)
  }

  fun delayFor(attempt: Int): Long {
    require(attempt >= 1)
    val exponential = (baseDelayMillis shl (attempt - 1).coerceAtMost(20)).coerceAtMost(maxDelayMillis)
    val jitter = (exponential * jitterRatio).toLong()
    if (jitter <= 0) return exponential
    return (exponential - jitter + random.nextLong(2 * jitter + 1)).coerceIn(0, maxDelayMillis)
  }

  fun shouldRetry(attempt: Int, disposition: RetryDisposition): Boolean =
    attempt < FailureClassifier.maxAttempts(disposition)
}

class OperationDeadline(
  timeoutMillis: Long,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  init {
    require(timeoutMillis > 0)
  }

  private val startedAt = clock()
  private val timeout = timeoutMillis

  fun elapsedMillis(): Long = clock() - startedAt

  fun remainingMillis(): Long = (timeout - elapsedMillis()).coerceAtLeast(0)

  fun expired(): Boolean = remainingMillis() == 0L
}

/**
 * Stops automatic retries after repeated failures and re-arms after a cooldown. The breaker is
 * intentionally in-memory: a restart must not inherit a stale "broken" opinion.
 */
class CircuitBreaker(
  private val threshold: Int = 3,
  private val cooldownMillis: Long = 60_000,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  init {
    require(threshold > 0)
  }

  private var failures = 0
  private var openedAt = 0L

  @Synchronized
  fun recordFailure(nowMillis: Long = clock()): Boolean {
    failures++
    if (failures >= threshold && openedAt == 0L) openedAt = nowMillis
    return isOpen(nowMillis)
  }

  @Synchronized
  fun recordSuccess() {
    failures = 0
    openedAt = 0L
  }

  @Synchronized
  fun isOpen(nowMillis: Long = clock()): Boolean {
    if (openedAt == 0L) return false
    if (nowMillis - openedAt >= cooldownMillis) {
      openedAt = 0L
      failures = 0
      return false
    }
    return true
  }

  @Synchronized
  fun retryAfterMillis(nowMillis: Long = clock()): Long =
    if (openedAt == 0L) 0 else (cooldownMillis - (nowMillis - openedAt)).coerceAtLeast(0)
}

/** Per-phase budgets from design §6.3 and §8.7, kept in one place for tests and diagnostics. */
data class PhaseTimeouts(
  val connectMillis: Long = 800,
  val serviceDiscoveryMillis: Long = 600,
  val mtuMillis: Long = 300,
  val helloMillis: Long = 600,
  val lanProbeMillis: Long = 500,
  val p2pJoinMillis: Long = 5_000,
  val streamIdleMillis: Long = 10_000,
)
