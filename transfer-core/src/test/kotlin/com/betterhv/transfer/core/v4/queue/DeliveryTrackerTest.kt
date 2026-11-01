package com.betterhv.transfer.core.v4.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryTrackerTest {
  private var now = 1_000L
  private val tracker = DeliveryTracker { now }

  @Test
  fun planningIsIdempotentPerTarget() {
    val first = tracker.plan("content-1", listOf("phone", "tablet", "phone"))
    assertEquals(2, first.size)
    now = 2_000L
    val second = tracker.plan("content-1", listOf("phone", "tablet"))
    assertEquals(first.map { it.peerDeviceId }, second.map { it.peerDeviceId })
    assertEquals(1_000L, second.first().updatedAtMillis)
  }

  @Test
  fun oneTargetCommitDoesNotAffectTheOthers() {
    tracker.plan("content-1", listOf("phone", "tablet"))
    tracker.markCommitted("content-1", "phone")
    assertFalse(tracker.isComplete("content-1"))
    assertEquals(1, tracker.pending("content-1").size)
    assertEquals(DeliveryState.COMMITTED, tracker.byContent("content-1").first { it.peerDeviceId == "phone" }.state)
  }

  @Test
  fun aFailedTargetCanBeRetriedWithoutTouchingCommittedOnes() {
    tracker.plan("content-1", listOf("phone", "tablet"))
    tracker.markCommitted("content-1", "phone")
    now = 3_000L
    val failed = tracker.markFailed("content-1", "tablet", "timeout")
    assertEquals(DeliveryState.FAILED, failed.state)
    assertEquals("timeout", failed.failure)
    assertEquals(3_000L, failed.updatedAtMillis)

    val retried = tracker.retry("content-1", "tablet")
    assertEquals(DeliveryState.PENDING, retried.state)
    assertEquals(null, retried.failure)
    assertEquals(DeliveryState.COMMITTED, tracker.byContent("content-1").first { it.peerDeviceId == "phone" }.state)

    tracker.markCommitted("content-1", "tablet")
    assertTrue(tracker.isComplete("content-1"))
  }

  @Test
  fun unknownDeliveriesAreRejected() {
    val error = runCatching {
      tracker.markCommitted("missing", "phone")
    }.exceptionOrNull()
    assertTrue(error is IllegalArgumentException)
  }

  @Test
  fun forgettingAContentRemovesEveryTarget() {
    tracker.plan("content-1", listOf("phone", "tablet"))
    tracker.plan("content-2", listOf("phone"))
    tracker.forget("content-1")
    assertTrue(tracker.byContent("content-1").isEmpty())
    assertEquals(1, tracker.byContent("content-2").size)
  }
}
