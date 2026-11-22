package com.betterhv.note.sender.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.database.sqlite.transaction
import com.betterhv.transfer.android.SenderContentProvider
import com.betterhv.transfer.android.SenderLease
import com.betterhv.transfer.core.ContentCounts
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.HistoryRetentionPolicy
import com.betterhv.transfer.core.QueueCoordinator
import com.betterhv.transfer.core.QueueItem
import com.betterhv.transfer.core.QueueState
import com.betterhv.transfer.core.QueueStore
import com.betterhv.transfer.core.RequeueDecision
import com.betterhv.transfer.core.RequeueSource
import com.betterhv.transfer.core.SendHistoryEntry
import com.betterhv.transfer.core.SendHistoryRecorder
import com.betterhv.transfer.core.SendOrder
import com.betterhv.transfer.core.SendOrderSelector
import com.betterhv.transfer.core.SendOutcome
import com.betterhv.transfer.core.TransferLimits
import com.betterhv.transfer.core.TransferOffer
import com.betterhv.update.NotePackageDescriptor
import com.betterhv.update.NotePackageResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal object PhoneHistoryPolicy {
  fun shouldRetainPayload(kind: ContentKind): Boolean = kind != ContentKind.APP_PACKAGE

  fun isResendable(
    entry: SendHistoryEntry,
    payloadExists: (SendHistoryEntry) -> Boolean,
  ): Boolean = entry.kind != ContentKind.APP_PACKAGE &&
    (entry.text != null || (entry.payloadPath != null && payloadExists(entry)))
}

class PhoneQueueRepository(
  context: Context,
  sendOrder: () -> SendOrder = { SendOrder.QUEUE },
  private val deviceName: (String?) -> String? = { null },
) :
  QueueStore,
  SenderContentProvider,
  AutoCloseable {
  internal val appContext = context.applicationContext
  private val db = QueueDatabase(appContext)
  private val outboxDir = File(appContext.filesDir, "outbox").also(File::mkdirs)
  private val coordinator = QueueCoordinator(this, order = sendOrder)
  private val history = PhoneSendHistoryStore(appContext)
  private val retention = HistoryRetentionPolicy()
  private val recorder = SendHistoryRecorder(history, policy = retention)
  val itemFlow: StateFlow<List<QueueItem>> = sharedItems
  val historyFlow: StateFlow<List<SendHistoryEntry>> = sharedHistory

  init {
    publish()
    publishHistory()
    reconcileHistory()
  }

  @Synchronized
  fun enqueueImage(uri: Uri, destinationDeviceId: String?): QueueItem {
    val resolver = appContext.contentResolver
    val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") }
      ?: throw IllegalArgumentException("Only images are supported")
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { if (it.moveToFirst()) it.getString(0) else null }
    val id = UUID.randomUUID()
    val temp = File(outboxDir, "$id.tmp")
    val target = File(outboxDir, "$id.bin")
    try {
      resolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Could not read image" }
        temp.outputStream().buffered().use { output ->
          val buffer = ByteArray(64 * 1024)
          var total = 0L
          while (true) {
            val count = input.read(buffer)
            if (count < 0) {
              break
            }
            total += count
            require(total <= TransferLimits.MAX_IMAGE_BYTES) { "Image cannot exceed 64 MiB" }
            output.write(buffer, 0, count)
          }
        }
      }
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(temp.absolutePath, bounds)
      require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Could not decode image" }
      require(bounds.outWidth.toLong() * bounds.outHeight <= TransferLimits.MAX_IMAGE_PIXELS) {
        "Image cannot exceed 100 million pixels"
      }
      check(temp.renameTo(target)) { "Could not save queued image" }
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

  @Synchronized
  fun enqueueText(text: String, destinationDeviceId: String?): QueueItem {
    val normalized = text.trim()
    require(normalized.isNotEmpty()) { "Text is empty" }
    val bytes = normalized.encodeToByteArray()
    require(bytes.size <= TransferLimits.MAX_TEXT_BYTES) { "Text cannot exceed 256 KiB" }
    val now = System.currentTimeMillis()
    val item = QueueItem(
      UUID.randomUUID(), destinationDeviceId, ContentKind.TEXT, "text/plain; charset=utf-8",
      bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes), now, nextPosition(),
      QueueState.PENDING, normalized.lineSequence().first().take(40),
    )
    insertWithPayload(item, null, normalized)
    return item
  }

  @Synchronized
  fun enqueuePdf(uri: Uri, destinationDeviceId: String?): QueueItem {
    val resolver = appContext.contentResolver
    val mime = resolver.getType(uri)
    require(mime == "application/pdf") { "Only PDF files are supported" }
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { if (it.moveToFirst()) it.getString(0) else null }
    val id = UUID.randomUUID()
    val temp = File(outboxDir, "$id.tmp")
    val target = File(outboxDir, "$id.pdf")
    try {
      resolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Could not read PDF" }
        temp.outputStream().buffered().use { output ->
          val buffer = ByteArray(64 * 1024)
          var total = 0L
          while (true) {
            val count = input.read(buffer)
            if (count < 0) {
              break
            }
            total += count
            require(total <= TransferLimits.MAX_PDF_BYTES) { "PDF cannot exceed 512 MiB" }
            output.write(buffer, 0, count)
          }
        }
      }
      require(
        temp.inputStream().use { input ->
          ByteArray(5).also(input::read).contentEquals("%PDF-".encodeToByteArray())
        },
      ) { "File is not a valid PDF" }
      check(temp.renameTo(target)) { "Could not save queued PDF" }
      val now = System.currentTimeMillis()
      val item = QueueItem(
        id,
        destinationDeviceId,
        ContentKind.PDF,
        "application/pdf",
        target.length(),
        sha256(target),
        now,
        nextPosition(),
        QueueState.PENDING,
        displayName ?: "文档.pdf",
      )
      insertWithPayload(item, target.relativeTo(appContext.filesDir).path, null)
      return item
    } catch (t: Throwable) {
      temp.delete()
      target.delete()
      throw t
    }
  }

  /** Enqueues an image that ShareIntake already copied and classified from its bytes. */
  @Synchronized
  fun enqueueImageFile(
    source: File,
    mimeType: String,
    displayName: String?,
    destinationDeviceId: String?,
    precomputedSha256: ByteArray? = null,
  ): QueueItem {
    require(source.isFile) { "Shared image is missing" }
    require(mimeType.startsWith("image/")) { "Only images are supported" }
    require(source.length() in 1..TransferLimits.MAX_IMAGE_BYTES) {
      "Image must be non-empty and cannot exceed 64 MiB"
    }
    return enqueueFile(
      source,
      ContentKind.IMAGE,
      mimeType,
      displayName ?: "图片",
      destinationDeviceId,
      precomputedSha256,
    )
  }

  /** Enqueues a PDF that ShareIntake already copied and classified from its bytes. */
  @Synchronized
  fun enqueuePdfFile(
    source: File,
    displayName: String?,
    destinationDeviceId: String?,
    precomputedSha256: ByteArray? = null,
  ): QueueItem {
    require(source.isFile) { "Shared PDF is missing" }
    require(source.length() in 1..TransferLimits.MAX_PDF_BYTES) {
      "PDF must be non-empty and cannot exceed 512 MiB"
    }
    require(isPdf(source)) { "File is not a valid PDF" }
    return enqueueFile(
      source,
      ContentKind.PDF,
      "application/pdf",
      displayName ?: "文档.pdf",
      destinationDeviceId,
      precomputedSha256,
    )
  }

  private fun enqueueFile(
    source: File,
    kind: ContentKind,
    mimeType: String,
    displayName: String,
    destinationDeviceId: String?,
    precomputedSha256: ByteArray? = null,
  ): QueueItem {
    if (precomputedSha256 != null) {
      require(precomputedSha256.size == QueueItem.SHA256_BYTES) { "sha256 must contain 32 bytes" }
    }
    val id = UUID.randomUUID()
    val target = File(outboxDir, "$id.bin")
    moveFile(source, target)
    return try {
      val digest = precomputedSha256?.copyOf() ?: sha256(target)
      val item = QueueItem(
        id, destinationDeviceId, kind, mimeType, target.length(), digest,
        System.currentTimeMillis(), nextPosition(), QueueState.PENDING, displayName,
      )
      insertWithPayload(item, target.relativeTo(appContext.filesDir).path, null)
      item
    } catch (error: Throwable) {
      target.delete()
      throw error
    }
  }

  private fun moveFile(source: File, target: File) {
    val moved = runCatching {
      Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }.isSuccess || source.renameTo(target)
    if (!moved) {
      source.copyTo(target, overwrite = true)
      source.delete()
    }
  }

  /** Whether a retained payload or text payload is still available for re-sending. */
  fun canResend(entry: SendHistoryEntry): Boolean =
    PhoneHistoryPolicy.isResendable(entry, history::payloadExists)

  /**
   * Re-queues a history entry without consuming it. The original destination wins while it is still
   * paired; otherwise the current selection becomes the destination.
   */
  @Synchronized
  fun requeueHistory(
    historyId: UUID,
    currentDeviceId: String?,
    pairedDeviceIds: Set<String>,
  ): UUID? {
    val entry = history.find(historyId) ?: return null
    val payload = entry.payloadPath?.let(history::resolvePayload)
    val decision =
      recorder.requeueDecision(entry, payload?.isFile == true, pairedDeviceIds, currentDeviceId)
    if (decision !is RequeueDecision.Ready) return null
    val destination = decision.plan.destinationDeviceId
    return when (val source = decision.plan.source) {
      is RequeueSource.Text -> enqueueText(source.value, destination).id
      is RequeueSource.Payload -> requeuePayload(entry, source.path, destination)
    }
  }

  @Synchronized
  fun deleteHistory(historyId: UUID): Boolean {
    history.find(historyId)?.let(history::deletePayload)
    val removed = history.delete(historyId)
    publishHistory()
    return removed
  }

  @Synchronized
  fun clearHistory() {
    history.entries().forEach(history::deletePayload)
    history.clear()
    publishHistory()
  }

  private fun requeuePayload(entry: SendHistoryEntry, path: String, destination: String?): UUID? {
    val source = history.resolvePayload(path)?.takeIf(File::isFile) ?: return null
    val temp = File(appContext.cacheDir, "requeue-${UUID.randomUUID()}.bin")
    return runCatching {
      source.copyTo(temp, overwrite = true)
      when (entry.kind) {
        ContentKind.PDF -> enqueuePdfFile(temp, entry.displayName, destination).id
        else -> enqueueImageFile(temp, entry.mimeType, entry.displayName, destination).id
      }
    }.also { temp.delete() }.getOrNull()
  }

  private fun isPdf(file: File): Boolean = file.inputStream().use { input ->
    val header = ByteArray(PDF_MAGIC.size)
    var read = 0
    while (read < header.size) {
      val count = input.read(header, read, header.size - read)
      if (count < 0) break
      read += count
    }
    read == header.size && header.contentEquals(PDF_MAGIC)
  }

  @Synchronized
  fun enqueueAppPackage(
    source: File,
    descriptor: NotePackageDescriptor,
    destinationDeviceId: String,
  ): QueueItem {
    require(source.isFile && source.length() == descriptor.apk.byteLength)
    require(sha256(source).contentEquals(descriptor.expectedSha256))
    items().filter {
      it.kind == ContentKind.APP_PACKAGE && it.destinationDeviceId == destinationDeviceId &&
        it.state in setOf(QueueState.PENDING, QueueState.FAILED)
    }.forEach { delete(it.id) }
    val id = UUID.randomUUID()
    val temporary = File(outboxDir, "$id.tmp")
    val target = File(outboxDir, "$id.apk")
    try {
      source.copyTo(temporary, overwrite = true)
      check(temporary.renameTo(target)) { "Could not save Note APK" }
      return QueueItem(
        id, destinationDeviceId, ContentKind.APP_PACKAGE, NotePackageResolver.NOTE_PACKAGE_MIME,
        target.length(), descriptor.expectedSha256, System.currentTimeMillis(), nextPosition(),
        QueueState.PENDING, descriptor.apk.name,
      ).also { insertWithPayload(it, target.relativeTo(appContext.filesDir).path, null) }
    } catch (error: Throwable) {
      temporary.delete(); target.delete(); throw error
    }
  }

  @Synchronized
  fun delete(id: UUID): Boolean {
    find(id)?.takeIf { it.state == QueueState.FAILED }?.let(::recordFailure)
    payloadFile(id)?.delete()
    val removed = remove(id)
    if (removed) pruneHistory()
    publish()
    return removed
  }

  @Synchronized
  fun clearCache(): Int {
    val removable = items().filter {
      it.state == QueueState.PENDING || it.state == QueueState.FAILED
    }
    if (removable.isEmpty()) return 0
    removable.filter { it.state == QueueState.FAILED }.forEach(::recordFailure)
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
    pruneHistory()
    publish()
    return removable.size
  }

  @Synchronized
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

  /** Re-targets unsent items addressed to a Note that is no longer paired to [deviceId]. */
  @Synchronized
  fun adoptOrphans(pairedIds: Set<String>, deviceId: String): Int {
    val orphans = items().filter {
      val destination = it.destinationDeviceId
      destination != null && destination !in pairedIds &&
        it.state in setOf(QueueState.PENDING, QueueState.FAILED)
    }
    orphans.forEach { update(it.copy(destinationDeviceId = deviceId)) }
    return orphans.size
  }

  @Synchronized
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

  /** Re-targets only provisional `pending:` rows after the peer's real device id is learned. */
  @Synchronized
  fun resolvePendingDestination(deviceId: String) {
    val pending = items().filter { it.destinationDeviceId?.startsWith("pending:") == true }
    if (pending.isEmpty()) return
    writable().beginTransaction()
    try {
      pending.forEach { update(it.copy(destinationDeviceId = deviceId)) }
      writable().setTransactionSuccessful()
    } finally {
      writable().endTransaction()
    }
    publish()
  }

  @Synchronized
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

  @Synchronized
  override fun counts(): ContentCounts {
    val available = items().filter { it.state == QueueState.PENDING }
    return available.contentCounts()
  }

  @Synchronized
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
    count { it.kind == ContentKind.APP_PACKAGE },
  )

  @Synchronized
  override fun leaseNext(kind: ContentKind, destinationDeviceId: String): SenderLease? {
    val item = coordinator.leaseNext(kind, destinationDeviceId) ?: return null
    publish()
    return PhoneSenderLease(item)
  }

  @Synchronized
  override fun items(): List<QueueItem> = readable().query(
    "queue_items",
    ITEM_COLUMNS,
    null,
    null,
    null,
    null,
    "position ASC, created_at ASC",
  ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.item()) } }

  @Synchronized
  override fun find(id: UUID): QueueItem? = readable().query(
    "queue_items",
    ITEM_COLUMNS,
    "id=?",
    arrayOf(id.toString()),
    null,
    null,
    null,
  ).use { if (it.moveToFirst()) it.item() else null }

  @Synchronized
  override fun next(
    kind: ContentKind,
    destinationDeviceId: String?,
    now: Long,
    order: SendOrder,
  ): QueueItem? {
    releaseExpiredInternal(now)
    return SendOrderSelector.nextPending(items(), kind, destinationDeviceId, order)
  }

  @Synchronized
  override fun insert(item: QueueItem) = insertWithPayload(item, null, null)

  @Synchronized
  override fun update(item: QueueItem) {
    val values = itemValues(item)
    check(writable().update("queue_items", values, "id=?", arrayOf(item.id.toString())) == 1)
    publish()
  }

  @Synchronized
  override fun remove(id: UUID): Boolean {
    val result = writable().delete("queue_items", "id=?", arrayOf(id.toString())) > 0
    publish()
    return result
  }

  @Synchronized
  override fun reorder(idsInOrder: List<UUID>) {
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
      } finally {
      }
    }
    publish()
  }

  @Synchronized
  override fun releaseExpired(now: Long): Int = releaseExpiredInternal(
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
    if (item.leaseExpiresAt == null) putNull("lease_expires_at") else put(
      "lease_expires_at",
      item.leaseExpiresAt,
    )
    put("failure_reason", item.failureReason)
    put("content_id", item.contentId)
    put("delivery_group", item.deliveryGroup)
  }

  private fun android.database.Cursor.item() = QueueItem(
    UUID.fromString(getString(0)), getString(1), ContentKind.valueOf(getString(2)), getString(3),
    getLong(4), getBlob(5), getLong(6), getLong(7), QueueState.valueOf(getString(8)), getString(9),
    if (isNull(10)) null else getLong(10), getString(11), getString(12), getString(13),
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

  private fun publishHistory() {
    sharedHistory.value = history.entries(retention.maxEntries)
  }

  /**
   * Commit sequence: keep a copy of the payload, record the history row, then drop the queue row.
   * [reconcileHistory] completes the sequence if the process dies between these steps.
   */
  private fun recordSuccess(item: QueueItem) {
    val text = if (item.kind == ContentKind.TEXT) textValue(item.id) else null
    val entry = recorder.record(
      item,
      deviceName(item.destinationDeviceId),
      SendOutcome.SUCCEEDED,
      text = text,
    )
    if (PhoneHistoryPolicy.shouldRetainPayload(item.kind)) {
      val payload = payloadFile(item.id)
      if (payload != null && payload.isFile) {
        history.retainPayload(payload, entry.id)?.let { recorder.attachPayload(entry, it) }
      }
    }
    Log.i(TAG, "history.success kind=${item.kind} bytes=${item.byteLength}")
    publishHistory()
  }

  private fun recordFailure(item: QueueItem) {
    runCatching {
      recorder.record(
        item,
        deviceName(item.destinationDeviceId),
        SendOutcome.FAILED,
        failureReason = item.failureReason,
      )
    }
    Log.i(TAG, "history.failure kind=${item.kind}")
    publishHistory()
  }

  private fun pruneHistory() {
    val plan = runCatching { recorder.retentionPlan() }.getOrNull() ?: return
    if (plan.isEmpty) return
    plan.payloadFilesToDelete.forEach(history::deletePayload)
    plan.deleteEntries.forEach { history.delete(it.id) }
    plan.clearPayloads.forEach { history.updatePayloadPath(it.id, null) }
    Log.i(
      TAG,
      "history.prune rows=${plan.deleteEntries.size} payloads=${plan.payloadFilesToDelete.size}",
    )
    publishHistory()
  }

  private fun reconcileHistory() {
    val entries = history.entries()
    val referenced =
      entries.mapNotNull { it.payloadPath?.let(history::resolvePayload)?.absolutePath }.toSet()
    val orphans = history.payloadFiles().filterValues { it.absolutePath !in referenced }.keys
    val missing = entries.filter { it.payloadPath != null && !history.payloadExists(it) }
      .map(SendHistoryEntry::id)
    val plan = recorder.reconcile(
      queueItems = items(),
      queuePayloadPresent = { payloadFile(it.id)?.isFile == true },
      history = entries,
      missingHistoryPayloadIds = missing,
      orphanPayloadIds = orphans,
    )
    plan.completedQueueItemIds.forEach { remove(it) }
    plan.orphanPayloadIds.forEach(history::deletePayloadById)
    plan.prunedHistoryIds.forEach { history.updatePayloadPath(it, null) }
    if (plan.completedQueueItemIds.isNotEmpty()) publish()
    if (plan.completedQueueItemIds.isNotEmpty() || plan.prunedHistoryIds.isNotEmpty()) publishHistory()
  }

  private fun readable() = db.readableDatabase
  private fun writable() = db.writableDatabase

  override fun close() {
    history.close()
    db.close()
  }

  private inner class PhoneSenderLease(item: QueueItem) : SenderLease {
    override var offer = TransferOffer(item)
      private set
    private var finished = false
    override val payloadFile: File?
      get() = synchronized(this@PhoneQueueRepository) {
        if (finished) null else this@PhoneQueueRepository.payloadFile(offer.item.id)
      }
    override val text: String?
      get() = synchronized(this@PhoneQueueRepository) {
        if (finished) null else textValue(offer.item.id)
      }

    override fun heartbeat(): Unit = synchronized(this@PhoneQueueRepository) {
      if (finished) return
      requireActiveLease()
      offer = offer.copy(item = coordinator.heartbeat(offer.item.id))
      publish()
    }

    override fun markTransferring(): Unit = synchronized(this@PhoneQueueRepository) {
      if (finished) return
      requireActiveLease()
      offer = offer.copy(item = coordinator.transition(offer.item.id, QueueState.TRANSFERRING))
      publish()
    }

    override fun markAwaitingCommit(): Unit = synchronized(this@PhoneQueueRepository) {
      if (finished) return
      requireActiveLease()
      offer = offer.copy(item = coordinator.transition(offer.item.id, QueueState.AWAITING_COMMIT))
      publish()
    }

    override fun commit(): Unit = synchronized(this@PhoneQueueRepository) {
      if (finished) return
      requireActiveLease()
      val item = offer.item
      runCatching { recordSuccess(item) }
      payloadFile(item.id)?.delete()
      coordinator.commit(item.id)
      pruneHistory()
      finished = true
      publish()
    }

    override fun release(): Unit = synchronized(this@PhoneQueueRepository) {
      if (finished) return
      val current = find(offer.item.id)
      if (current != null && (current.state in ACTIVE_LEASE_STATES || current.state == QueueState.FAILED)) {
        coordinator.release(offer.item.id)
      }
      finished = true
      publish()
    }

    private fun requireActiveLease() {
      val current = find(offer.item.id)
      check(current != null && current.state in ACTIVE_LEASE_STATES) {
        "Lease for ${offer.item.id} is no longer active (${current?.state})"
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

  private class QueueDatabase(context: Context) :
    SQLiteOpenHelper(context, "sender_queue.db", null, 2) {
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
                payload_path TEXT, text_content TEXT,
                content_id TEXT, delivery_group TEXT
            )
        """.trimIndent(),
      )
      db.execSQL("CREATE INDEX queue_position ON queue_items(position)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
      if (oldVersion < 2) {
        db.execSQL("ALTER TABLE queue_items ADD COLUMN content_id TEXT")
        db.execSQL("ALTER TABLE queue_items ADD COLUMN delivery_group TEXT")
      }
    }
  }

  private companion object {
    const val TAG = "NoteLink"
    val ACTIVE_LEASE_STATES = setOf(QueueState.LEASED, QueueState.TRANSFERRING, QueueState.AWAITING_COMMIT)
    val sharedItems = MutableStateFlow<List<QueueItem>>(emptyList())
    val sharedHistory = MutableStateFlow<List<SendHistoryEntry>>(emptyList())
    val PDF_MAGIC = "%PDF-".encodeToByteArray()
    val ITEM_COLUMNS = arrayOf(
      "id", "destination_device_id", "kind", "mime_type", "byte_length", "sha256",
      "created_at", "position", "state", "display_name", "lease_expires_at", "failure_reason",
      "content_id", "delivery_group",
    )
  }
}
