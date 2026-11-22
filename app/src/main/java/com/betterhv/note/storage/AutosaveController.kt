package com.betterhv.note.storage

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface AutosaveTarget : AutoCloseable {
  fun persist(change: DocumentChange)
  fun persistWithReceipt(change: DocumentChange, receipt: TransferReceipt?)
  fun setLastOpenedPage(notebookId: UUID, pageId: UUID)
  fun checkpoint()
  fun checkpointPassive()
}

/** FIFO storage worker; every completed command becomes one atomic SQLite transaction. */
class AutosaveController internal constructor(
  private val target: AutosaveTarget,
  private val onError: (Throwable) -> Unit = {},
  private val onRecovered: () -> Unit = {},
  private val retryBackoffsMs: LongArray = DEFAULT_RETRY_BACKOFFS_MS,
) : AutoCloseable {
  constructor(
    repository: NotebookRepository,
    onError: (Throwable) -> Unit = {},
    onRecovered: () -> Unit = {},
  ) : this(target = repository, onError = onError, onRecovered = onRecovered)

  private val degraded = AtomicBoolean(false)
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "inknote-storage").apply { isDaemon = true }
  }

  fun schedule(change: DocumentChange) {
    executor.execute {
      try {
        runWithRetry { target.persist(change) }
      } catch (t: Throwable) {
        markError(t)
      }
    }
  }

  /** New pages must be persisted before recording their navigation position. */
  fun recordLastOpenedPage(notebookId: UUID, pageId: UUID, onFailure: (Throwable) -> Unit) {
    executor.execute {
      try {
        runWithRetry { target.setLastOpenedPage(notebookId, pageId) }
      } catch (error: Exception) {
        onFailure(error)
      }
    }
  }

  /** Enqueues behind prior saves and waits for the object + receipt transaction. */
  fun persistWithReceipt(change: DocumentChange, receipt: TransferReceipt, timeoutSeconds: Long = 10): Boolean = try {
    executor.submit { runWithRetry { target.persistWithReceipt(change, receipt) } }
      .get(timeoutSeconds, TimeUnit.SECONDS)
    true
  } catch (t: Throwable) {
    if (t is InterruptedException) Thread.currentThread().interrupt()
    markError(t)
    false
  }

  fun awaitPending(timeoutSeconds: Long = 10): Boolean = try {
    executor.submit {}.get(timeoutSeconds, TimeUnit.SECONDS)
    true
  } catch (t: Throwable) {
    if (t is InterruptedException) Thread.currentThread().interrupt()
    false
  }

  fun flush(timeoutSeconds: Long = 10, truncateWal: Boolean = false): Boolean = try {
    executor.submit {
      runWithRetry {
        if (truncateWal) target.checkpoint() else target.checkpointPassive()
      }
    }.get(timeoutSeconds, TimeUnit.SECONDS)
    true
  } catch (t: Throwable) {
    if (t is InterruptedException) Thread.currentThread().interrupt()
    markError(t)
    false
  }

  override fun close() = close(verify = false)

  fun closeForMaintenance() = close(verify = true)

  private fun close(verify: Boolean) {
    val saved = flush(truncateWal = verify)
    executor.shutdown()
    check(executor.awaitTermination(60, TimeUnit.SECONDS)) { "Storage worker did not stop" }
    target.close()
    if (verify) check(saved) { "Pending document writes could not be saved" }
  }

  private fun runWithRetry(block: () -> Unit) {
    var attempt = 0
    while (true) {
      try {
        block()
        if (degraded.compareAndSet(true, false)) {
          onRecovered()
        }
        return
      } catch (t: Throwable) {
        if (!isTransientLockError(t) || attempt >= retryBackoffsMs.size) throw t
        try {
          Thread.sleep(retryBackoffsMs[attempt++])
        } catch (interrupted: InterruptedException) {
          Thread.currentThread().interrupt()
          throw interrupted
        }
      }
    }
  }

  private fun markError(t: Throwable) {
    degraded.set(true)
    onError(t)
  }

  companion object {
    private val DEFAULT_RETRY_BACKOFFS_MS = longArrayOf(25L, 50L, 100L, 200L)

    internal fun isTransientLockError(t: Throwable): Boolean =
      generateSequence(t) { it.cause }.any { cause ->
        cause is SQLiteDatabaseLockedException ||
          (cause is SQLiteException && cause.message?.let(::isBusyMessage) == true) ||
          cause.message?.let(::isBusyMessage) == true
      }

    private fun isBusyMessage(message: String): Boolean =
      message.contains("database is locked", ignoreCase = true) ||
        message.contains("SQLITE_BUSY", ignoreCase = true) ||
        message.contains("WAL checkpoint is busy", ignoreCase = true)
  }
}
