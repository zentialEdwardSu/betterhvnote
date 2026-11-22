package com.betterhv.note.storage

import com.betterhv.note.doc.Notebook
import com.betterhv.note.doc.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private class FakeHelper(val path: File)

private class FakeAutosaveTarget : AutosaveTarget {
  val persistCalls = AtomicInteger(0)
  val checkpointCalls = AtomicInteger(0)
  val passiveCheckpointCalls = AtomicInteger(0)
  val closed = AtomicInteger(0)
  var failPersistRemaining: Int = 0
  var persistFailureFactory: () -> Throwable = { IllegalStateException("database is locked") }

  override fun persist(change: DocumentChange) {
    persistCalls.incrementAndGet()
    if (failPersistRemaining > 0) {
      failPersistRemaining--
      throw persistFailureFactory()
    }
  }

  override fun persistWithReceipt(change: DocumentChange, receipt: TransferReceipt?) = Unit

  override fun setLastOpenedPage(notebookId: UUID, pageId: UUID) = Unit

  override fun checkpoint() {
    checkpointCalls.incrementAndGet()
  }

  override fun checkpointPassive() {
    passiveCheckpointCalls.incrementAndGet()
  }

  override fun close() {
    closed.incrementAndGet()
  }
}

class SQLiteStoreRegistryTest {
  @get:Rule val temp = TemporaryFolder()

  @Test
  fun registrySharesPerCanonicalPathAndClosesOnlyOnLastRelease() {
    val destroyed = mutableListOf<FakeHelper>()
    val registry = SharedStoreRegistry<FakeHelper> { destroyed += it }
    val root = temp.newFolder()
    val subDir = File(root, "sub").apply { mkdirs() }
    val fileA = File(root, "default.inknote")
    val fileAAlias = File(subDir, "../default.inknote")
    val fileB = File(root, "other.inknote")

    val lease1 = registry.acquire(fileA, ::FakeHelper)
    val lease2 = registry.acquire(fileAAlias, ::FakeHelper)
    val leaseB = registry.acquire(fileB, ::FakeHelper)

    assertSame(lease1.helper, lease2.helper)
    assertNotSame(lease1.helper, leaseB.helper)
    assertTrue(destroyed.isEmpty())

    // Double-close on lease1 must be idempotent and keep refCount at 1 for lease2.
    lease1.close()
    lease1.close()
    assertTrue(destroyed.isEmpty())

    lease2.close()
    assertEquals(listOf(lease1.helper), destroyed)

    val lease3 = registry.acquire(fileA, ::FakeHelper)
    assertNotSame(lease1.helper, lease3.helper)

    lease3.close()
    leaseB.close()
    assertEquals(3, destroyed.size)
  }

  @Test
  fun autosaveRetriesTransientBusyAndFiresOnRecoveredAfterFailure() {
    val target = FakeAutosaveTarget()
    val errors = mutableListOf<Throwable>()
    val recoveredCount = AtomicInteger(0)
    val notebook = Notebook().also { it.addPage(Page()) }
    val change = DocumentChange.structure(notebook, listOf(notebook.pageAt(0)!!), "test")

    AutosaveController(
      target = target,
      onError = { errors += it },
      onRecovered = { recoveredCount.incrementAndGet() },
      retryBackoffsMs = longArrayOf(1L, 1L, 1L),
    ).use { controller ->
      target.failPersistRemaining = 2
      target.persistFailureFactory = { IllegalStateException("database is locked (SQLITE_BUSY)") }
      controller.schedule(change)
      assertTrue(controller.awaitPending())
      assertEquals(3, target.persistCalls.get())
      assertTrue(errors.isEmpty())
      assertEquals(0, recoveredCount.get())

      // Non-transient failure degrades and fires onError; subsequent flush recovers.
      target.failPersistRemaining = 1
      target.persistFailureFactory = { IllegalStateException("disk I/O error") }
      controller.schedule(change)
      assertTrue(controller.awaitPending())
      assertEquals(1, errors.size)
      assertEquals(0, recoveredCount.get())

      assertTrue(controller.flush(truncateWal = false))
      assertEquals(1, target.passiveCheckpointCalls.get())
      assertEquals(0, target.checkpointCalls.get())
      assertEquals(1, recoveredCount.get())

      assertTrue(controller.flush(truncateWal = true))
      assertEquals(1, target.checkpointCalls.get())
      assertEquals(1, recoveredCount.get())
    }
    assertEquals(1, target.closed.get())
    assertFalse(target.closed.get() == 0)
  }
}
