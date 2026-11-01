package com.betterhv.transfer.core.v4.consistency

import com.betterhv.transfer.core.v4.queue.DeliveryState
import com.betterhv.transfer.core.v4.queue.DeliveryTracker
import com.betterhv.transfer.core.v4.resume.FileStreamCheckpointStore
import com.betterhv.transfer.core.v4.resume.ResumeManager
import com.betterhv.transfer.core.v4.resume.StreamCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Crash-point injection for the durable receive-side state: a checkpoint survives a restart, a
 * half-written `.tmp` is ignored, and per-target delivery commits are idempotent.
 */
class CrashConsistencyTest {
  private val sha = ByteArray(32) { 11 }
  private var now = 5_000_000L

  @Test
  fun checkpointSurvivesRestartAndIsTheResumeOffset() {
    val directory = Files.createTempDirectory("crash-checkpoints").toFile()
    val first = ResumeManager(FileStreamCheckpointStore(directory), clock = { now })
    first.checkpoint("item", 7L, 3, 4_096, sha)

    val restarted = ResumeManager(FileStreamCheckpointStore(directory), clock = { now })
    assertEquals(4_096L, restarted.offsetFor("item", 7L))
  }

  @Test
  fun halfWrittenTempFileDoesNotCorruptTheLastCommit() {
    val directory = Files.createTempDirectory("crash-tmp").toFile()
    val store = FileStreamCheckpointStore(directory)
    store.save(StreamCheckpoint("item", 1L, 1, 100, sha, now))
    File(directory, "checkpoints.txt.tmp").writeText("garbage|without|six|fields")

    val restarted = FileStreamCheckpointStore(directory)
    assertEquals(listOf("item"), restarted.loadAll().map { it.itemId })
    assertEquals(100L, restarted.loadAll().single().receivedBytes)
  }

  @Test
  fun completionIsIdempotentAcrossRestarts() {
    val directory = Files.createTempDirectory("crash-complete").toFile()
    val manager = ResumeManager(FileStreamCheckpointStore(directory), clock = { now })
    manager.checkpoint("item", 2L, 1, 512, sha)
    manager.complete("item", 2L)
    manager.complete("item", 2L)
    val restarted = ResumeManager(FileStreamCheckpointStore(directory), clock = { now })
    assertEquals(0L, restarted.offsetFor("item", 2L))
  }

  @Test
  fun expiredCheckpointIsDiscardedAfterARestart() {
    val directory = Files.createTempDirectory("crash-expiry").toFile()
    ResumeManager(FileStreamCheckpointStore(directory), clock = { now })
      .checkpoint("old", 3L, 1, 10, sha)
    now += ResumeManager.DEFAULT_MAX_AGE_MILLIS + 1
    val restarted = ResumeManager(FileStreamCheckpointStore(directory), clock = { now })
    assertEquals(0L, restarted.offsetFor("old", 3L))
    assertTrue(FileStreamCheckpointStore(directory).loadAll().isEmpty())
  }

  @Test
  fun deliveryCommitsAreIdempotentAndIsolatedPerTarget() {
    val tracker = DeliveryTracker(clock = { now })
    tracker.plan("content", listOf("a", "b", "c"))
    assertEquals(3, tracker.plan("content", listOf("a", "b", "c")).size)

    tracker.markCommitted("content", "a")
    tracker.markCommitted("content", "a")
    tracker.markFailed("content", "b", "timeout")
    assertEquals(DeliveryState.FAILED, tracker.byContent("content").first { it.peerDeviceId == "b" }.state)
    assertEquals(DeliveryState.COMMITTED, tracker.byContent("content").first { it.peerDeviceId == "a" }.state)
    assertFalse(tracker.isComplete("content"))

    tracker.retry("content", "b")
    tracker.markCommitted("content", "b")
    tracker.markCommitted("content", "c")
    assertTrue(tracker.isComplete("content"))
    assertEquals(3, tracker.byContent("content").count { it.state == DeliveryState.COMMITTED })
  }
}
