package com.betterhv.note.sender

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.database.sqlite.transaction
import com.betterhv.transfer.android.SenderContentProvider
import com.betterhv.transfer.android.SenderLease
import com.betterhv.transfer.core.ContentCounts
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.QueueCoordinator
import com.betterhv.transfer.core.QueueItem
import com.betterhv.transfer.core.QueueState
import com.betterhv.transfer.core.QueueStore
import com.betterhv.transfer.core.TransferLimits
import com.betterhv.transfer.core.TransferOffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

class PhoneQueueRepository(context: Context) :
  QueueStore,
  SenderContentProvider,
  AutoCloseable {
  internal val appContext = context.applicationContext
  private val db = QueueDatabase(appContext)
  private val outboxDir = File(appContext.filesDir, "outbox").also(File::mkdirs)
  private val coordinator = QueueCoordinator(this)
  val itemFlow: StateFlow<List<QueueItem>> = sharedItems

  init {
    publish()
  }

  fun enqueueImage(uri: Uri, destinationDeviceId: String?): QueueItem {
    val resolver = appContext.contentResolver
    val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") }
      ?: throw IllegalArgumentException("只支持图片")
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { if (it.moveToFirst()) it.getString(0) else null }
    val id = UUID.randomUUID()
    val temp = File(outboxDir, "$id.tmp")
    val target = File(outboxDir, "$id.bin")
    try {
      resolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "无法读取图片" }
        temp.outputStream().buffered().use { output ->
          val buffer = ByteArray(64 * 1024)
          var total = 0L
          while (true) {
            val count = input.read(buffer)
            if (count < 0) {
              break
            }
            total += count
            require(total <= TransferLimits.MAX_IMAGE_BYTES) { "图片不能超过 64 MiB" }
            output.write(buffer, 0, count)
          }
        }
      }
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(temp.absolutePath, bounds)
      require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码图片" }
      require(bounds.outWidth.toLong() * bounds.outHeight <= TransferLimits.MAX_IMAGE_PIXELS) {
        "图片像素不能超过 1 亿"
      }
      check(temp.renameTo(target)) { "无法保存队列图片" }
      val now = System.currentTimeMillis()
      val item = QueueItem(
        id, destinationDeviceId, ContentKind.IMAGE, mime, target.length(), sha256(target),
        now, nextPosition(), QueueState.PENDING, displayName ?: "图片",
      )
      insertWithPayload(item, target.relativeTo(appContext.filesDir).path, null)
      return item
    } catch (t: Throwable) {
      temp.delete()
      target.delete()
      throw t
    }
  }

  fun enqueueText(text: String, destinationDeviceId: String?): QueueItem {
    val normalized = text.trim()
    require(normalized.isNotEmpty()) { "文字为空" }
    val bytes = normalized.encodeToByteArray()
    require(bytes.size <= TransferLimits.MAX_TEXT_BYTES) { "文字不能超过 256 KiB" }
    val now = System.currentTimeMillis()
    val item = QueueItem(
      UUID.randomUUID(), destinationDeviceId, ContentKind.TEXT, "text/plain; charset=utf-8",
      bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes), now, nextPosition(),
      QueueState.PENDING, normalized.lineSequence().first().take(40),
    )
    insertWithPayload(item, null, normalized)
    return item
  }

  fun enqueuePdf(uri: Uri, destinationDeviceId: String?): QueueItem {
    val resolver = appContext.contentResolver
    val mime = resolver.getType(uri)
    require(mime == "application/pdf") { "只支持 PDF" }
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { if (it.moveToFirst()) it.getString(0) else null }
    val id = UUID.randomUUID()
    val temp = File(outboxDir, "$id.tmp")
    val target = File(outboxDir, "$id.pdf")
    try {
      resolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "无法读取 PDF" }
        temp.outputStream().buffered().use { output ->
          val buffer = ByteArray(64 * 1024)
          var total = 0L
          while (true) {
            val count = input.read(buffer)
            if (count < 0) {
              break
            }
            total += count
            require(total <= TransferLimits.MAX_PDF_BYTES) { "PDF 不能超过 512 MiB" }
            output.write(buffer, 0, count)
          }
        }
      }
      require(
        temp.inputStream().use { input ->
          ByteArray(5).also(input::read).contentEquals("%PDF-".encodeToByteArray())
        },
      ) { "文件不是有效的 PDF" }
      check(temp.renameTo(target)) { "无法保存队列 PDF" }
      val now = System.currentTimeMillis()
      val item = QueueItem(
        id, destinationDeviceId, ContentKind.PDF, "application/pdf", target.length(), sha256(target),
        now, nextPosition(), QueueState.PENDING, displayName ?: "文档.pdf",
      )
      insertWithPayload(item, target.relativeTo(appContext.filesDir).path, null)
      return item
    } catch (t: Throwable) {
      temp.delete()
      target.delete()
      throw t
    }
  }

  fun delete(id: UUID): Boolean {
    payloadFile(id)?.delete()
    val removed = remove(id)
    publish()
    return removed
  }

  @Synchronized
  fun clearCache(): Int {
    val removable = items().filter {
      it.state == QueueState.PENDING || it.state == QueueState.FAILED
    }
    if (removable.isEmpty()) return 0
    val payloads = removable.mapNotNull { payloadFile(it.id) }
    val database = writable()
    database.transaction {
      try {
        removable.forEach {
          delete("queue_items", "id=?", arrayOf(it.id.toString()))
        }
      } finally {
      }
    }
    payloads.forEach(File::delete)
    publish()
    return removable.size
  }

  fun move(id: UUID, delta: Int) {
    val ordered = items().map(QueueItem::id).toMutableList()
    val from = ordered.indexOf(id)
    if (from < 0) return
    val to = (from + delta).coerceIn(0, ordered.lastIndex)
    if (from == to) return
    ordered.removeAt(from)
    ordered.add(to, id)
    reorder(ordered)
    publish()
  }

  fun reassignPending(deviceId: String) {
    writable().beginTransaction()
    try {
      items().forEach { update(it.copy(destinationDeviceId = deviceId)) }
      writable().setTransactionSuccessful()
    } finally {
      writable().endTransaction()
    }
    publish()
  }

  fun reassignDestination(oldDeviceId: String, newDeviceId: String) {
    writable().beginTransaction()
    try {
      items().filter { it.destinationDeviceId == oldDeviceId }
        .forEach { update(it.copy(destinationDeviceId = newDeviceId)) }
      writable().setTransactionSuccessful()
    } finally {
      writable().endTransaction()
    }
    publish()
  }

  override fun counts(): ContentCounts {
    val available = items().filter { it.state == QueueState.PENDING }
    return available.contentCounts()
  }

  fun counts(destinationDeviceId: String): ContentCounts {
    val available = items().filter {
      it.state == QueueState.PENDING &&
        (it.destinationDeviceId == null || it.destinationDeviceId == destinationDeviceId)
    }
    return available.contentCounts()
  }

  private fun List<QueueItem>.contentCounts(): ContentCounts = ContentCounts(
    count { it.kind == ContentKind.IMAGE },
    count { it.kind == ContentKind.TEXT },
    count { it.kind == ContentKind.PDF },
  )

  override fun leaseNext(kind: ContentKind, destinationDeviceId: String): SenderLease? {
    val item = coordinator.leaseNext(kind, destinationDeviceId) ?: return null
    publish()
    return PhoneSenderLease(item)
  }

  @Synchronized override fun items(): List<QueueItem> = readable().query(
    "queue_items",
    ITEM_COLUMNS,
    null,
    null,
    null,
    null,
    "position ASC, created_at ASC",
  ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.item()) } }

  @Synchronized override fun find(id: UUID): QueueItem? = readable().query(
    "queue_items",
    ITEM_COLUMNS,
    "id=?",
    arrayOf(id.toString()),
    null,
    null,
    null,
  ).use { if (it.moveToFirst()) it.item() else null }

  @Synchronized override fun next(kind: ContentKind, destinationDeviceId: String?, now: Long): QueueItem? {
    releaseExpiredInternal(now)
    return items().firstOrNull {
      it.kind == kind && it.state == QueueState.PENDING &&
        (it.destinationDeviceId == null || it.destinationDeviceId == destinationDeviceId)
    }
  }

  @Synchronized override fun insert(item: QueueItem) = insertWithPayload(item, null, null)

  @Synchronized override fun update(item: QueueItem) {
    val values = itemValues(item)
    check(writable().update("queue_items", values, "id=?", arrayOf(item.id.toString())) == 1)
    publish()
  }

  @Synchronized override fun remove(id: UUID): Boolean {
    val result = writable().delete("queue_items", "id=?", arrayOf(id.toString())) > 0
    publish()
    return result
  }

  @Synchronized override fun reorder(idsInOrder: List<UUID>) {
    require(idsInOrder.toSet() == items().map(QueueItem::id).toSet())
    val database = writable()
    database.transaction {
      try {
        idsInOrder.forEachIndexed { index, id ->
          update(
            "queue_items",
            ContentValues().apply { put("position", index) },
            "id=?",
            arrayOf(id.toString()),
          )
        }
      } finally { }
    }
    publish()
  }

  @Synchronized override fun releaseExpired(now: Long): Int = releaseExpiredInternal(
    now,
  ).also { if (it > 0) publish() }

  private fun releaseExpiredInternal(now: Long): Int {
    val values = ContentValues().apply {
      put("state", QueueState.PENDING.name)
      putNull("lease_expires_at")
      putNull("failure_reason")
    }
    return writable().update(
      "queue_items",
      values,
      "state IN (?,?,?) AND lease_expires_at IS NOT NULL AND lease_expires_at<=?",
      arrayOf(
        QueueState.LEASED.name,
        QueueState.TRANSFERRING.name,
        QueueState.AWAITING_COMMIT.name,
        now.toString(),
      ),
    )
  }

  private fun insertWithPayload(item: QueueItem, payloadPath: String?, text: String?) {
    val values = itemValues(item).apply {
      put("payload_path", payloadPath)
      put("text_content", text)
    }
    writable().insertOrThrow("queue_items", null, values)
    publish()
  }

  private fun itemValues(item: QueueItem) = ContentValues().apply {
    put("id", item.id.toString())
    put("destination_device_id", item.destinationDeviceId)
    put("kind", item.kind.name)
    put("mime_type", item.mimeType)
    put("byte_length", item.byteLength)
    put("sha256", item.sha256)
    put("created_at", item.createdAt)
    put("position", item.position)
    put("state", item.state.name)
    put("display_name", item.displayName)
    if (item.leaseExpiresAt == null) putNull("lease_expires_at") else put("lease_expires_at", item.leaseExpiresAt)
    put("failure_reason", item.failureReason)
  }

  private fun android.database.Cursor.item() = QueueItem(
    UUID.fromString(getString(0)), getString(1), ContentKind.valueOf(getString(2)), getString(3),
    getLong(4), getBlob(5), getLong(6), getLong(7), QueueState.valueOf(getString(8)), getString(9),
    if (isNull(10)) null else getLong(10), getString(11),
  )

  private fun payloadFile(id: UUID): File? = readable().query(
    "queue_items",
    arrayOf("payload_path"),
    "id=?",
    arrayOf(id.toString()),
    null,
    null,
    null,
  ).use { cursor ->
    if (!cursor.moveToFirst() || cursor.isNull(0)) {
      null
    } else {
      File(appContext.filesDir, cursor.getString(0)).canonicalFile
        .takeIf { it.toPath().startsWith(outboxDir.canonicalFile.toPath()) }
    }
  }

  private fun textValue(id: UUID): String? = readable().query(
    "queue_items",
    arrayOf("text_content"),
    "id=?",
    arrayOf(id.toString()),
    null,
    null,
    null,
  ).use { if (it.moveToFirst()) it.getString(0) else null }

  private fun nextPosition(): Long = (items().maxOfOrNull(QueueItem::position) ?: -1L) + 1L
  private fun publish() {
    sharedItems.value = items()
  }
  private fun readable() = db.readableDatabase
  private fun writable() = db.writableDatabase

  override fun close() = db.close()

  private inner class PhoneSenderLease(item: QueueItem) : SenderLease {
    override var offer = TransferOffer(item)
      private set
    private var finished = false
    override val payloadFile: File? get() = this@PhoneQueueRepository.payloadFile(offer.item.id)
    override val text: String? get() = textValue(offer.item.id)
    override fun heartbeat() {
      if (!finished) {
        offer = offer.copy(item = coordinator.heartbeat(offer.item.id))
      }
      publish()
    }
    override fun markTransferring() {
      if (!finished) {
        offer = offer.copy(item = coordinator.transition(offer.item.id, QueueState.TRANSFERRING))
      }
      publish()
    }
    override fun markAwaitingCommit() {
      if (!finished) {
        offer = offer.copy(item = coordinator.transition(offer.item.id, QueueState.AWAITING_COMMIT))
      }
      publish()
    }
    override fun commit() {
      if (finished) return
      payloadFile?.delete()
      coordinator.commit(offer.item.id)
      finished = true
      publish()
    }
    override fun release() {
      if (!finished) {
        coordinator.release(offer.item.id)
        finished = true
        publish()
      }
    }
  }

  private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").run {
    FileInputStream(file).use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) {
          break
        }
        update(buffer, 0, count)
      }
    }
    digest()
  }

  private class QueueDatabase(context: Context) : SQLiteOpenHelper(context, "sender_queue.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
      db.setForeignKeyConstraintsEnabled(
        true,
      )
      db.enableWriteAheadLogging()
    }
    override fun onCreate(db: SQLiteDatabase) {
      db.execSQL(
        """CREATE TABLE queue_items(
                id TEXT PRIMARY KEY, destination_device_id TEXT, kind TEXT NOT NULL,
                mime_type TEXT NOT NULL, byte_length INTEGER NOT NULL, sha256 BLOB NOT NULL,
                created_at INTEGER NOT NULL, position INTEGER NOT NULL, state TEXT NOT NULL,
                display_name TEXT, lease_expires_at INTEGER, failure_reason TEXT,
                payload_path TEXT, text_content TEXT
            )
        """.trimIndent(),
      )
      db.execSQL("CREATE INDEX queue_position ON queue_items(position)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
  }

  private companion object {
    val sharedItems = MutableStateFlow<List<QueueItem>>(emptyList())
    val ITEM_COLUMNS = arrayOf(
      "id", "destination_device_id", "kind", "mime_type", "byte_length", "sha256",
      "created_at", "position", "state", "display_name", "lease_expires_at", "failure_reason",
    )
  }
}
