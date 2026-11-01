package com.betterhv.transfer.core.v4.resume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ResumeManagerTest {
  private class MemoryStore : StreamCheckpointStore {
    val entries = mutableMapOf<Pair<String, Long>, StreamCheckpoint>()
    override fun loadAll(): List<StreamCheckpoint> = entries.values.toList()
    override fun save(checkpoint: StreamCheckpoint) {
      entries[checkpoint.itemId to checkpoint.sessionId] = checkpoint
    }

    override fun remove(itemId: String, sessionId: Long) {
      entries.remove(itemId to sessionId)
    }
  }

  private var now = 1_000_000L
  private val store = MemoryStore()
  private val manager = ResumeManager(store, clock = { now })
  private val sha = ByteArray(32) { 7 }

  @Test
  fun checkpointIsReturnedAsTheResumeOffset() {
    assertEquals(0L, manager.offsetFor("item-1", 42L))
    manager.checkpoint("item-1", 42L, 3, 512, sha)
    assertEquals(512L, manager.offsetFor("item-1", 42L))
    assertEquals(0L, manager.offsetFor("item-1", 43L))
  }

  @Test
  fun completingRemovesTheCheckpoint() {
    manager.checkpoint("item-1", 42L, 3, 512, sha)
    manager.complete("item-1", 42L)
    assertEquals(0L, manager.offsetFor("item-1", 42L))
  }

  @Test
  fun expiredCheckpointsAreDiscardedOnReadAndCleanup() {
    manager.checkpoint("old", 1L, 1, 10, sha)
    manager.checkpoint("new", 2L, 2, 20, sha)
    now += ResumeManager.DEFAULT_MAX_AGE_MILLIS + 1
    manager.checkpoint("new", 2L, 2, 30, sha)
    assertEquals(0L, manager.offsetFor("old", 1L))
    assertEquals(30L, manager.offsetFor("new", 2L))
    assertEquals(1, store.entries.size)
    assertEquals(0, manager.cleanup())
  }

  @Test
  fun partFilesUseItemAndSessionNames() {
    val directory = Files.createTempDirectory("parts").toFile()
    val file = manager.partFile(directory, "artifact/../1", 0xabL)
    assertTrue(file.name.startsWith("artifact1"))
    assertTrue(file.name.endsWith(".ab.part"))
    assertFalse(file.name.contains("/"))
  }

  @Test
  fun fileStoreRoundTripsAndRewritesAtomically() {
    val directory = Files.createTempDirectory("checkpoints").toFile()
    val fileStore = FileStreamCheckpointStore(directory)
    fileStore.save(StreamCheckpoint("a", 1L, 2, 10, sha, 100))
    fileStore.save(StreamCheckpoint("b", 2L, 4, 20, sha, 200))
    assertEquals(2, fileStore.loadAll().size)
    assertEquals(10L, fileStore.loadAll().first { it.itemId == "a" }.receivedBytes)

    fileStore.save(StreamCheckpoint("a", 1L, 2, 15, sha, 300))
    assertEquals(2, fileStore.loadAll().size)
    assertEquals(15L, fileStore.loadAll().first { it.itemId == "a" }.receivedBytes)

    fileStore.remove("a", 1L)
    assertEquals(listOf("b"), fileStore.loadAll().map { it.itemId })
    assertTrue(File(directory, "checkpoints.txt").isFile)
  }

  @Test
  fun corruptLinesAreIgnored() {
    val directory = Files.createTempDirectory("corrupt").toFile()
    File(directory, "checkpoints.txt").writeText("bad|line\n")
    assertTrue(FileStreamCheckpointStore(directory).loadAll().isEmpty())
  }
}
