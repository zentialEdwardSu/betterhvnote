package com.betterhv.note.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupProgressThrottleTest {
  @Test
  fun dropsSubIntervalAndSubDeltaUpdatesWhileAlwaysEmittingZeroAndOne() {
    var now = 1_000L
    val throttle = BackupProgressThrottle(
      minIntervalMillis = 100L,
      minDelta = 0.02f,
      clockMillis = { now },
    )

    // Initial 0f emits once; duplicate 0f spam within interval is dropped.
    assertTrue(throttle.shouldEmit(0, 100))
    now += 10L
    assertFalse(throttle.shouldEmit(0, 100))

    // Sub-delta (0.01 < 0.02) and sub-interval (20ms < 100ms) is dropped.
    now += 10L
    assertFalse(throttle.shouldEmit(1, 100))

    // Delta >= 0.02 emits even before interval elapses.
    now += 10L
    assertTrue(throttle.shouldEmit(2, 100))

    // Sub-delta and sub-interval again is dropped, then interval >= 100ms allows emit.
    now += 20L
    assertFalse(throttle.shouldEmit(3, 100))
    now += 90L
    assertTrue(throttle.shouldEmit(3, 100))

    // Jump to 99%, then immediately emit 100% (delta = 0.01 < 0.02, elapsed = 0ms): 1f must always emit.
    assertTrue(throttle.shouldEmit(99, 100))
    assertTrue(throttle.shouldEmit(100, 100))
  }
}
