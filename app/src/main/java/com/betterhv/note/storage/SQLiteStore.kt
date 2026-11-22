package com.betterhv.note.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class SharedStoreRegistry<H>(
  private val destroy: (H) -> Unit,
) {
  private data class Entry<H>(val helper: H, var refCount: Int)

  private val lock = Any()
  private val entries = HashMap<File, Entry<H>>()

  fun acquire(file: File, create: (File) -> H): Lease<H> {
    val key = file.canonicalFile
    synchronized(lock) {
      val existing = entries[key]
      val entry = if (existing != null) {
        existing.refCount++
        existing
      } else {
        Entry(create(key), 1).also { entries[key] = it }
      }
      return Lease(key, entry.helper)
    }
  }

  inner class Lease<T>(private val key: File, val helper: T) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    override fun close() {
      if (!closed.compareAndSet(false, true)) return
      synchronized(lock) {
        val entry = entries[key] ?: return
        entry.refCount--
        if (entry.refCount <= 0) {
          entries.remove(key)
          destroy(entry.helper)
        }
      }
    }
  }
}

/** Android SQLite boundary. Core document classes never import SQLite APIs. */
class SQLiteStore(context: Context, file: File) : AutoCloseable {
  private val appContext = context.applicationContext ?: context
  private val lease = registry.acquire(file) { canonical -> SharedHelper(appContext, canonical) }

  val readableDatabase: SQLiteDatabase
    get() {
      check(!lease.isClosed) { "SQLiteStore is closed" }
      return lease.helper.readableDatabase
    }

  val writableDatabase: SQLiteDatabase
    get() {
      check(!lease.isClosed) { "SQLiteStore is closed" }
      return lease.helper.writableDatabase
    }

  fun quickCheck(): Boolean = readableDatabase.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
    cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
  }

  fun checkpointPassive() {
    writableDatabase.rawQuery("PRAGMA wal_checkpoint(PASSIVE)", null).use { cursor ->
      check(cursor.moveToFirst()) { "WAL passive checkpoint failed" }
    }
  }

  fun checkpoint() {
    var attempt = 0
    while (true) {
      val busy = try {
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
          check(cursor.moveToFirst()) { "WAL checkpoint failed" }
          cursor.getInt(0)
        }
      } catch (error: SQLiteDatabaseLockedException) {
        if (attempt >= CHECKPOINT_RETRY_BACKOFFS_MS.size) throw error
        1
      }
      if (busy == 0) return
      if (attempt >= CHECKPOINT_RETRY_BACKOFFS_MS.size) {
        error("WAL checkpoint is busy; snapshot aborted")
      }
      try {
        Thread.sleep(CHECKPOINT_RETRY_BACKOFFS_MS[attempt++])
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        error("WAL checkpoint interrupted")
      }
    }
  }

  override fun close() = lease.close()

  private class SharedHelper(context: Context, file: File) :
    SQLiteOpenHelper(context, file.absolutePath, null, MigrationManager.VERSION) {

    init {
      setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
      super.onConfigure(db)
      db.setForeignKeyConstraintsEnabled(true)
      db.execSQL("PRAGMA synchronous=NORMAL")
      db.rawQuery("PRAGMA busy_timeout=5000", null).close()
    }

    override fun onCreate(db: SQLiteDatabase) = MigrationManager.create(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
      MigrationManager.migrate(db, oldVersion, newVersion)

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
      error("Notebook schema downgrade $oldVersion -> $newVersion is not supported")
    }
  }

  private companion object {
    val CHECKPOINT_RETRY_BACKOFFS_MS = longArrayOf(25L, 50L, 100L, 200L)
    val registry = SharedStoreRegistry<SharedHelper> { helper -> helper.close() }
  }
}
