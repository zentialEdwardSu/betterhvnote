package com.betterhv.note.sender.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.betterhv.note.sender.shared.db.history.SendHistoryDatabase
import com.betterhv.note.sender.shared.db.history.Send_history
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.SendHistoryEntry
import com.betterhv.transfer.core.SendHistoryStore
import com.betterhv.transfer.core.SendOutcome
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Windows persistence for send history. Like the Android store it owns a separate database file and
 * a `history/` payload directory, so the existing queue and inbox databases keep their schema.
 */
class DesktopSendHistoryStore(private val paths: DesktopPaths) : SendHistoryStore, AutoCloseable {
  private val databaseExisted = paths.historyDatabase.isFile && paths.historyDatabase.length() > 0
  private val driver = JdbcSqliteDriver("jdbc:sqlite:${paths.historyDatabase.absolutePath}")
  private val database = SendHistoryDatabase(driver)
  private val queries = database.sendHistoryQueries

  init {
    if (!databaseExisted) SendHistoryDatabase.Schema.create(driver)
    driver.execute(null, "PRAGMA journal_mode=WAL", 0)
    driver.execute(null, "PRAGMA busy_timeout=5000", 0)
  }

  @Synchronized
  override fun insert(entry: SendHistoryEntry): SendHistoryEntry {
    queries.insertIgnore(
      entry.id.toString(),
      entry.queueItemId.toString(),
      entry.destinationDeviceId,
      entry.destinationName,
      entry.kind.name,
      entry.mimeType,
      entry.byteLength,
      entry.sha256,
      entry.displayName,
      entry.completedAt,
      entry.outcome.name,
      entry.failureReason,
      entry.payloadPath,
      entry.text,
    )
    return findByQueueItem(entry.queueItemId) ?: entry
  }

  @Synchronized
  override fun entries(limit: Int): List<SendHistoryEntry> =
    queries.selectAll().executeAsList().take(limit).map { it.entry() }

  @Synchronized
  override fun find(id: UUID): SendHistoryEntry? =
    queries.selectById(id.toString()).executeAsOneOrNull()?.entry()

  @Synchronized
  override fun findByQueueItem(queueItemId: UUID): SendHistoryEntry? =
    queries.selectByQueueItem(queueItemId.toString()).executeAsOneOrNull()?.entry()

  @Synchronized
  override fun updatePayloadPath(id: UUID, payloadPath: String?) {
    queries.updatePayloadPath(payloadPath, id.toString())
  }

  @Synchronized
  override fun delete(id: UUID): Boolean {
    val existed = find(id) != null
    queries.deleteById(id.toString())
    return existed
  }

  @Synchronized
  override fun clear() {
    queries.deleteAll()
  }

  /** Moves [source] into the history directory and returns its absolute path. */
  @Synchronized
  fun retainPayload(source: File, historyId: UUID): String? {
    if (!source.isFile) return null
    val target = File(paths.history, "$historyId.bin")
    val moved = runCatching {
      Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }.isSuccess || source.renameTo(target)
    return if (moved) target.absolutePath else null
  }

  /** Resolves a stored payload path, refusing anything outside the history directory. */
  fun resolvePayload(path: String): File? = runCatching {
    File(path).canonicalFile
  }.getOrNull()?.takeIf { it.toPath().startsWith(paths.history.canonicalFile.toPath()) }

  fun payloadExists(entry: SendHistoryEntry): Boolean =
    entry.payloadPath?.let(::resolvePayload)?.isFile == true

  fun deletePayload(entry: SendHistoryEntry) {
    entry.payloadPath?.let(::resolvePayload)?.delete()
  }

  fun deletePayloadById(id: UUID) {
    File(paths.history, "$id.bin").delete()
  }

  fun payloadFiles(): Map<UUID, File> = paths.history.listFiles().orEmpty().mapNotNull { file ->
    runCatching { UUID.fromString(file.name.substringBefore('.')) }.getOrNull()?.let { it to file }
  }.toMap()

  override fun close() = driver.close()

  private fun Send_history.entry() = SendHistoryEntry(
    id = UUID.fromString(id),
    queueItemId = UUID.fromString(queue_item_id),
    destinationDeviceId = destination_device_id,
    destinationName = destination_name,
    kind = ContentKind.valueOf(kind),
    mimeType = mime_type,
    byteLength = byte_length,
    sha256 = sha256,
    displayName = display_name,
    completedAt = completed_at,
    outcome = SendOutcome.valueOf(outcome),
    failureReason = failure_reason,
    payloadPath = payload_path,
    text = text_content,
  )
}
