package com.betterhv.transfer.core.v4.error

import com.betterhv.transfer.proto.v4.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FailureModelTest {
  @Test
  fun securityAndProtocolFailuresAreFatal() {
    listOf(
      ErrorCode.ERROR_AUTHENTICATION_FAILED,
      ErrorCode.ERROR_REPLAY_REJECTED,
      ErrorCode.ERROR_UNSUPPORTED_PROTOCOL,
      ErrorCode.ERROR_PROTOCOL_MISMATCH,
      ErrorCode.ERROR_UNKNOWN_PAIRING,
      ErrorCode.ERROR_INTEGRITY_FAILED,
    ).forEach { code ->
      assertEquals(RetryDisposition.FATAL, FailureClassifier.classify(code))
      assertEquals(1, FailureClassifier.maxAttempts(RetryDisposition.FATAL))
    }
  }

  @Test
  fun retryableAndSingleRetryErrorsAreDistinguished() {
    assertEquals(RetryDisposition.RETRY_ONCE, FailureClassifier.classify(ErrorCode.ERROR_CLOCK_SKEW))
    assertEquals(2, FailureClassifier.maxAttempts(RetryDisposition.RETRY_ONCE))
    assertEquals(RetryDisposition.RETRY, FailureClassifier.classify(ErrorCode.ERROR_CONNECTION_TIMEOUT))
    assertEquals(3, FailureClassifier.maxAttempts(RetryDisposition.RETRY))
    assertFalse(RetryPolicy().shouldRetry(3, RetryDisposition.RETRY))
    assertTrue(RetryPolicy().shouldRetry(2, RetryDisposition.RETRY))
  }

  @Test
  fun backoffGrowsAndStaysWithinJitterBounds() {
    val policy = RetryPolicy(baseDelayMillis = 100, maxDelayMillis = 800, jitterRatio = 0.2, random = Random(1))
    val first = policy.delayFor(1)
    assertTrue("first delay $first", first in 80..120)
    val fourth = policy.delayFor(4)
    assertTrue("fourth delay $fourth", fourth in 640..800)
    repeat(50) {
      val delay = policy.delayFor(5)
      assertTrue(delay in 0..800)
    }
  }

  @Test
  fun deadlineCountsDownWithTheInjectedClock() {
    var now = 1_000L
    val deadline = OperationDeadline(timeoutMillis = 500, clock = { now })
    assertEquals(500, deadline.remainingMillis())
    now += 200
    assertEquals(300, deadline.remainingMillis())
    assertFalse(deadline.expired())
    now += 400
    assertTrue(deadline.expired())
    assertEquals(0, deadline.remainingMillis())
  }

  @Test
  fun breakerOpensAfterThresholdAndClosesAfterCooldownOrSuccess() {
    var now = 1_000L
    val breaker = CircuitBreaker(threshold = 3, cooldownMillis = 60_000, clock = { now })
    assertFalse(breaker.recordFailure())
    assertFalse(breaker.recordFailure())
    assertTrue(breaker.recordFailure())
    assertTrue(breaker.isOpen())
    assertEquals(60_000, breaker.retryAfterMillis())

    now += 60_000
    assertFalse(breaker.isOpen())
    assertFalse(breaker.isOpen())

    assertTrue(breaker.recordFailure() || breaker.recordFailure() || breaker.recordFailure())
    breaker.recordSuccess()
    assertFalse(breaker.isOpen())
  }

  @Test
  fun phaseTimeoutsMatchTheDesignBudgets() {
    val timeouts = PhaseTimeouts()
    assertEquals(800, timeouts.connectMillis)
    assertEquals(600, timeouts.helloMillis)
    assertEquals(500, timeouts.lanProbeMillis)
    assertEquals(5_000, timeouts.p2pJoinMillis)
  }
}
