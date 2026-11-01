package com.betterhv.transfer.core.v4.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTimelineRecorderTest {
  @Test
  fun summaryUsesTheMostRecentSamples() {
    val recorder = LinkTimelineRecorder()
    repeat(100) { value ->
      recorder.record(LinkTimeline("fetch", totalMillis = value.toLong() + 1))
    }
    val summary = recorder.summary(limit = 100)!!
    assertEquals(100, summary.samples)
    assertEquals(50, summary.p50Millis)
    assertEquals(95, summary.p95Millis)
    assertEquals(99, summary.p99Millis)
  }

  @Test
  fun capacityKeepsTheNewestEntries() {
    val recorder = LinkTimelineRecorder(capacity = 3)
    repeat(5) { recorder.record(LinkTimeline("fetch", totalMillis = it.toLong())) }
    assertEquals(3, recorder.size())
    assertEquals(listOf(2L, 3L, 4L), recorder.recent().map { it.totalMillis })
  }

  @Test
  fun emptyRecorderHasNoSummary() {
    assertNull(LinkTimelineRecorder().summary())
  }

  @Test
  fun timelineCarriesNoPayloadOrSecretFields() {
    val fields = LinkTimeline::class.java.declaredFields.map { it.name.lowercase() }
    listOf("payload", "key", "ticket", "nonce", "message", "data", "content").forEach { forbidden ->
      assertTrue("unexpected field $forbidden", fields.none { it.contains(forbidden) })
    }
  }

  @Test
  fun recordingStampsTheTimeAndKeepsPhaseBreakdown() {
    val recorder = LinkTimelineRecorder(clock = { 42L })
    val stored = recorder.record(
      LinkTimeline(
        operation = "connect",
        totalMillis = 900,
        phasesMillis = mapOf("scan" to 120, "connect" to 300, "hello" to 480),
        downgradeReason = "lan-probe-timeout",
        mtu = 247,
      ),
    )
    assertEquals(42L, stored.recordedAtMillis)
    assertEquals(480, stored.phasesMillis.getValue("hello"))
    assertEquals("lan-probe-timeout", stored.downgradeReason)
  }
}
