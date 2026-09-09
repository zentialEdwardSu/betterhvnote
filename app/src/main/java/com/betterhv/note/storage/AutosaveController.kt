package com.betterhv.note.storage

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** FIFO storage worker; every completed command becomes one atomic SQLite transaction. */
class AutosaveController(private val repository: NotebookRepository, private val onError: (Throwable) -> Unit = {}) :
  AutoCloseable {
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "inknote-storage").apply { isDaemon = true }
  }
  fun schedule(change: DocumentChange) {
    executor.execute {
      try {
        repository.persist(change)
      } catch (t: Throwable) {
        onError(t)
      }
    }
  }

  /** New pages must be persisted before recording their navigation position. */
  fun recordLastOpenedPage(notebookId: java.util.UUID, pageId: java.util.UUID, onFailure: (Throwable) -> Unit) {
    executor.execute {
      try {
        repository.setLastOpenedPage(notebookId, pageId)
      } catch (error: Exception) {
        onFailure(error)
      }
    }
  }

  /** Enqueues behind prior saves and waits for the object + receipt transaction. */
  fun persistWithReceipt(change: DocumentChange, receipt: TransferReceipt, timeoutSeconds: Long = 10): Boolean = try {
    executor.submit { repository.persistWithReceipt(change, receipt) }
      .get(timeoutSeconds, TimeUnit.SECONDS)
    true
  } catch (t: Throwable) {
    onError(t)
    false
  }

  fun flush(timeoutSeconds: Long = 10): Boolean = try {
    executor.submit { repository.checkpoint() }.get(timeoutSeconds, TimeUnit.SECONDS)
    true
  } catch (t: Throwable) {
    onError(t)
    false
  }

  override fun close() {
    flush()
    executor.shutdown()
    executor.awaitTermination(10, TimeUnit.SECONDS)
    repository.close()
  }
}
