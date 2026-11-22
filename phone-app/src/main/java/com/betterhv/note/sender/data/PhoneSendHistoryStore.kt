package com.betterhv.note.sender.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.SendHistoryEntry
import com.betterhv.transfer.core.SendHistoryStore
import com.betterhv.transfer.core.SendOutcome
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Android persistence for send history, kept in its own database file so the existing queue and inbox
 * schemas never need an in-place migration. Payload copies live under `files/history/<historyId>.bin`.
 */
class PhoneSendHistoryStore(context: Context) : SendHistoryStore, AutoCloseable {
  private val appContext = context.applicationContext
  private val database = HistoryDatabase(appContext)
  private val historyDir = File(appContext.filesDir, HISTORY_DIR).also(File::mkdirs)

  @Synchronized
  override fun insert(entry: SendHistoryEntry): SendHistoryEntry {
    writable().insertWithOnConflict(
      "send_history",
      null,
      values(entry),
      SQLiteDatabase.CONFLICT_IGNORE
    )
    return findByQueueItem(entry.queueItemId) ?: entry
  }

  @Synchronized
  override fun entries(limit: Int): List<SendHistoryEntry> {
    val bounded = limit.takeIf { it != Int.MAX_VALUE }?.toString()
    return readable().query(
      "send_history",
      COLUMNS,
      null,
      null,
      null,
      null,
      "completed_at DESC, id ASC",
      bounded,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.entry()) } }
  }

  @Synchronized
  override fun find(id: UUID): SendHistoryEntry? = queryOne("id=?", arrayOf(id.toString()))

  @Synchronized
  override fun findByQueueItem(queueItemId: UUID): SendHistoryEntry? =
    queryOne("queue_item_id=?", arrayOf(queueItemId.toString()))

  @Synchronized
  override fun updatePayloadPath(id: UUID, payloadPath: String?) {
    val values = ContentValues().apply {
      if (payloadPath == null) putNull("payload_path") else put("payload_path", payloadPath)
    }
    writable().update("send_history", values, "id=?", arrayOf(id.toString()))
  }

  @Synchronized
  override fun delete(id: UUID): Boolean =
    writable().delete("send_history", "id=?", arrayOf(id.toString())) > 0

  @Synchronized
  override fun clear() {
    writable().delete("send_history", null, null)
  }

  /** Moves [source] into the history directory and returns its path relative to `filesDir`. */
  @Synchronized
  fun retainPayload(source: File, historyId: UUID): String? {
    if (!source.isFile) return null
    val target = File(historyDir, "$historyId.bin")
    val moved = runCatching {
      Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }.isSuccess || source.renameTo(target)
    if (!moved) return null
    return "$HISTORY_DIR/${target.name}"
  }

  /** Resolves a stored payload path, refusing anything outside the history directory. */
  fun resolvePayload(relativePath: String): File? = runCatching {
    File(appContext.filesDir, relativePath).canonicalFile
  }.getOrNull()?.takeIf { it.toPath().startsWith(historyDir.canonicalFile.toPath()) }

  fun payloadExists(entry: SendHistoryEntry): Boolean =
    entry.payloadPath?.let(::resolvePayload)?.isFile == true

  fun deletePayload(entry: SendHistoryEntry) {
    entry.payloadPath?.let(::resolvePayload)?.delete()
  }

  fun deletePayloadById(id: UUID) {
    File(historyDir, "$id.bin").delete()
  }

  fun payloadFiles(): Map<UUID, File> = historyDir.listFiles().orEmpty().mapNotNull { file ->
    runCatching { UUID.fromString(file.name.substringBefore('.')) }.getOrNull()?.let { it to file }
  }.toMap()

  override fun close() = database.close()

  private fun queryOne(selection: String, args: Array<String>): SendHistoryEntry? =
    readable().query(
      "send_history",
      COLUMNS,
      selection,
      args,
      null,
      null,
      null,
      "1",
    ).use { if (it.moveToFirst()) it.entry() else null }

  private fun values(entry: SendHistoryEntry) = ContentValues().apply {
    put("id", entry.id.toString())
    put("queue_item_id", entry.queueItemId.toString())
    put("destination_device_id", entry.destinationDeviceId)
    put("destination_name", entry.destinationName)
    put("kind", entry.kind.name)
    put("mime_type", entry.mimeType)
    put("byte_length", entry.byteLength)
    put("sha256", entry.sha256)
    put("display_name", entry.displayName)
    put("completed_at", entry.completedAt)
    put("outcome", entry.outcome.name)
    put("failure_reason", entry.failureReason)
    put("payload_path", entry.payloadPath)
    put("text_content", entry.text)
  }

  private fun android.database.Cursor.entry() = SendHistoryEntry(
    id = UUID.fromString(getString(0)),
    queueItemId = UUID.fromString(getString(1)),
    destinationDeviceId = getString(2),
    destinationName = getString(3),
    kind = ContentKind.valueOf(getString(4)),
    mimeType = getString(5),
    byteLength = getLong(6),
    sha256 = getBlob(7),
    displayName = getString(8),
    completedAt = getLong(9),
    outcome = SendOutcome.valueOf(getString(10)),
    failureReason = getString(11),
    payloadPath = getString(12),
    text = getString(13),
  )

  private fun readable() = database.readableDatabase
  private fun writable() = database.writableDatabase

  private class HistoryDatabase(context: Context) :
    SQLiteOpenHelper(context, "send_history.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
      db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
      db.execSQL(
        """CREATE TABLE send_history(
                id TEXT NOT NULL PRIMARY KEY, queue_item_id TEXT NOT NULL,
                destination_device_id TEXT, destination_name TEXT, kind TEXT NOT NULL,
                mime_type TEXT NOT NULL, byte_length INTEGER NOT NULL, sha256 BLOB NOT NULL,
                display_name TEXT, completed_at INTEGER NOT NULL, outcome TEXT NOT NULL,
                failure_reason TEXT, payload_path TEXT, text_content TEXT
            )
        """.trimIndent(),
      )
      db.execSQL("CREATE UNIQUE INDEX send_history_queue_item ON send_history(queue_item_id)")
      db.execSQL("CREATE INDEX send_history_completed ON send_history(completed_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
  }

  private companion object {
    const val HISTORY_DIR = "history"
    val COLUMNS = arrayOf(
      "id", "queue_item_id", "destination_device_id", "destination_name", "kind", "mime_type",
      "byte_length", "sha256", "display_name", "completed_at", "outcome", "failure_reason",
      "payload_path", "text_content",
    )
  }
}
