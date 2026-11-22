package com.betterhv.transfer.core.v4.session

import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.ClientHello
import com.betterhv.transfer.proto.v4.ContentKind
import com.betterhv.transfer.proto.v4.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeValidationTest {
  private val now = 1_700_000_000_000L

  private fun hello(
    timeMs: Long = now,
    deviceId: String = "tablet",
    major: Int = 4,
  ): ClientHello = ClientHello.newBuilder()
    .setMajor(major)
    .setMinorMin(0)
    .setMinorMax(0)
    .setDeviceId(deviceId)
    .setClientTimeMs(timeMs)
    .build()

  private fun nonce(value: Int): ByteArray = ByteArray(HandshakeReplayGuard.NONCE_BYTES) { value.toByte() }

  @Test
  fun acceptsFreshHandshakesAndRejectsNonceReplay() {
    val guard = HandshakeReplayGuard()
    assertTrue(guard.check(hello(), nonce(1), now) is HandshakeVerdict.Accept)
    val replay = guard.check(hello(), nonce(1), now)
    assertEquals(ErrorCode.ERROR_REPLAY_REJECTED, (replay as HandshakeVerdict.Reject).code)
  }

  @Test
  fun allowsClockRollbackAndSameMillisecondReconnectWithFreshNonce() {
    val guard = HandshakeReplayGuard()
    assertTrue(guard.check(hello(timeMs = now), nonce(1), now) is HandshakeVerdict.Accept)
    // Same millisecond reconnect with a fresh nonce succeeds.
    assertTrue(guard.check(hello(timeMs = now), nonce(2), now) is HandshakeVerdict.Accept)
    // NTP clock rollback within clockSkewMillis with a fresh nonce succeeds.
    assertTrue(guard.check(hello(timeMs = now - 1_000), nonce(3), now) is HandshakeVerdict.Accept)
    // Replaying any seen nonce is still rejected even with a newer timestamp.
    val replayed = guard.check(hello(timeMs = now + 1_000), nonce(1), now)
    assertEquals(ErrorCode.ERROR_REPLAY_REJECTED, (replayed as HandshakeVerdict.Reject).code)
  }

  @Test
  fun clockSkewIsRetryableAndCarriesServerTime() {
    val guard = HandshakeReplayGuard()
    val result = guard.check(hello(timeMs = now - 10 * 60_000), nonce(1), now)
    val reject = result as HandshakeVerdict.Reject
    assertEquals(ErrorCode.ERROR_CLOCK_SKEW, reject.code)
    assertEquals(now, reject.serverTimeMs)
    assertTrue(reject.retryable)
  }

  @Test
  fun rejectsTheWrongMajorAndMissingIdentity() {
    val guard = HandshakeReplayGuard()
    assertEquals(
      ErrorCode.ERROR_UNSUPPORTED_PROTOCOL,
      (guard.check(hello(major = 5), nonce(1), now) as HandshakeVerdict.Reject).code,
    )
    assertEquals(
      ErrorCode.ERROR_AUTHENTICATION_FAILED,
      (guard.check(hello(deviceId = ""), nonce(2), now) as HandshakeVerdict.Reject).code,
    )
    assertEquals(
      ErrorCode.ERROR_AUTHENTICATION_FAILED,
      (guard.check(hello(), ByteArray(4), now) as HandshakeVerdict.Reject).code,
    )
  }

  @Test
  fun negotiatesTheIntersectionOfCapabilities() {
    val local = CapabilitySet.newBuilder()
      .setTransports(1 or 2)
      .setMaxStreams(8)
      .setMaxRecordBytes(64 * 1024)
      .setMaxInflightBytes(4L * 1024 * 1024)
      .addKinds(ContentKind.CONTENT_IMAGE)
      .addKinds(ContentKind.CONTENT_PDF)
      .setFeatures(1 or 8)
      .build()
    val remote = CapabilitySet.newBuilder()
      .setTransports(2)
      .setMaxStreams(4)
      .setMaxRecordBytes(16 * 1024)
      .setMaxInflightBytes(2L * 1024 * 1024)
      .addKinds(ContentKind.CONTENT_PDF)
      .addKinds(ContentKind.CONTENT_TEXT)
      .setFeatures(8 or 16)
      .build()
    val negotiated = CapabilityNegotiator.negotiate(local, remote)
    assertEquals(2, negotiated.transports)
    assertEquals(4, negotiated.maxStreams)
    assertEquals(16 * 1024, negotiated.maxRecordBytes)
    assertEquals(2L * 1024 * 1024, negotiated.maxInflightBytes)
    assertEquals(listOf(ContentKind.CONTENT_PDF), negotiated.kinds)
    assertEquals(8, negotiated.features)
  }

  @Test
  fun defaultsApplyWhenEitherSideOmitsLimits() {
    val negotiated = CapabilityNegotiator.negotiate(
      CapabilitySet.newBuilder().setTransports(1).build(),
      CapabilitySet.newBuilder().setTransports(1).build(),
    )
    assertEquals(CapabilityNegotiator.DEFAULT_MAX_STREAMS, negotiated.maxStreams)
    assertEquals(CapabilityNegotiator.DEFAULT_MAX_RECORD_BYTES, negotiated.maxRecordBytes)
    assertEquals(CapabilityNegotiator.DEFAULT_MAX_INFLIGHT_BYTES, negotiated.maxInflightBytes)
  }

  @Test
  fun minorSelectionIsBoundedByBothSides() {
    assertEquals(0, CapabilityNegotiator.chooseMinor(0, 0, 0))
    assertEquals(2, CapabilityNegotiator.chooseMinor(1, 3, 2))
    assertEquals(3, CapabilityNegotiator.chooseMinor(1, 3, 9))
    assertNull(CapabilityNegotiator.chooseMinor(2, 3, 1))
    assertNull(CapabilityNegotiator.chooseMinor(3, 2, 3))
  }
}
