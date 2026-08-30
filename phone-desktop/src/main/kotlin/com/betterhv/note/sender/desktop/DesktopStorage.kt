package com.betterhv.note.sender.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.betterhv.note.sender.shared.db.inbox.ExportInboxDatabase
import com.betterhv.note.sender.shared.db.inbox.Inbox_exports
import com.betterhv.note.sender.shared.db.queue.Queue_items
import com.betterhv.note.sender.shared.db.queue.SenderQueueDatabase
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.ContentCounts
import com.betterhv.transfer.core.ExportTransferOffer
import com.betterhv.transfer.core.QueueCoordinator
import com.betterhv.transfer.core.QueueItem
import com.betterhv.transfer.core.QueueState
import com.betterhv.transfer.core.QueueStore
import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.core.TransferLimits
import com.betterhv.transfer.windows.WindowsNativeApi
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DesktopPaths(root: File = defaultRoot()) {
    val root = root.canonicalFile.also(File::mkdirs)
    val outbox = File(this.root, "outbox").also(File::mkdirs)
    val inbox = File(this.root, "inbox").also(File::mkdirs)
    val queueDatabase = File(this.root, "sender_queue.db")
    val inboxDatabase = File(this.root, "export_inbox.db")
    val settings = File(this.root, "settings.properties")

    companion object {
        private fun defaultRoot(): File {
            val local = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
                ?: File(System.getProperty("user.home"), "AppData/Local").path
            return File(local, "BetterHv/NoteLink")
        }
    }
}

data class DesktopPairing(val deviceId: String, val deviceName: String, val sharedKey: ByteArray)

class DesktopSettings(private val paths: DesktopPaths, private val native: WindowsNativeApi) {
    private val lock = Any()
    private val values = Properties()

    init {
        synchronized(lock) {
            if (paths.settings.isFile) paths.settings.inputStream().use(values::load)
        }
    }

    val localDeviceId: String get() = synchronized(lock) {
        values.getProperty(KEY_LOCAL_ID) ?: UUID.randomUUID().toString().also {
            values.setProperty(KEY_LOCAL_ID, it)
            persist()
        }
    }

    var displayName: String
        get() = synchronized(lock) { values.getProperty(KEY_DISPLAY_NAME, "NoteLink-${System.getenv("COMPUTERNAME") ?: "Windows"}") }
        set(value) = synchronized(lock) {
            val normalized = value.trim()
            require(normalized.isNotBlank() && normalized.encodeToByteArray().size <= 48) { "显示名称不能为空且不能超过 48 字节" }
            values.setProperty(KEY_DISPLAY_NAME, normalized)
            persist()
        }

    var receiveEnabled: Boolean
        get() = synchronized(lock) { values.getProperty(KEY_RECEIVE, "true").toBooleanStrictOrNull() ?: true }
        set(value) = synchronized(lock) { values.setProperty(KEY_RECEIVE, value.toString()); persist() }

    var showRecentTransferEvents: Boolean
        get() = synchronized(lock) { values.getProperty(KEY_SHOW_RECENT_EVENTS, "true").toBooleanStrictOrNull() ?: true }
        set(value) = synchronized(lock) { values.setProperty(KEY_SHOW_RECENT_EVENTS, value.toString()); persist() }

    val pairing: DesktopPairing? get() = synchronized(lock) {
        val id = values.getProperty(KEY_PEER_ID) ?: return@synchronized null
        val encoded = values.getProperty(KEY_SECRET) ?: return@synchronized null
        val protected = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return@synchronized null
        val secret = runCatching { native.unprotect(protected) }.getOrNull() ?: return@synchronized null
        DesktopPairing(id, values.getProperty(KEY_PEER_NAME, id), secret)
    }

    /** Owner-side pairing used while the N10Pro enters the code shown by NoteLink. */
    val ownerPairing: DesktopPairing get() = synchronized(lock) {
        val code = ownerPairingCodeLocked()
        DesktopPairing("betterhv-note", "N10Pro", derivePairingKey(code))
    }

    val ownerPairingCode: String get() = synchronized(lock) { ownerPairingCodeLocked() }

    fun beginOwnerPairing() = synchronized(lock) {
        values.remove(KEY_PEER_ID)
        values.remove(KEY_PEER_NAME)
        values.remove(KEY_SECRET)
        values.remove(KEY_OWNER_CODE)
        ownerPairingCodeLocked()
        persist()
    }

    fun pairWithCode(code: String, deviceId: String = "betterhv-note", deviceName: String = "N10Pro") {
        require(code.length == 6 && code.all(Char::isDigit)) { "配对码必须是六位数字" }
        val key = TransferCrypto.hkdfSha256(
            code.encodeToByteArray(), "BetterHv-manual-v1".encodeToByteArray(),
            "paired-device".encodeToByteArray(), 32
        )
        val protected = native.protect(key)
        synchronized(lock) {
            values.setProperty(KEY_PEER_ID, deviceId)
            values.setProperty(KEY_PEER_NAME, deviceName)
            values.setProperty(KEY_SECRET, Base64.getEncoder().encodeToString(protected))
            persist()
        }
    }

    /** Persists the key used by the owner-side manual pairing after authentication succeeds. */
    fun completeOwnerPairing(deviceId: String = "betterhv-note", deviceName: String = "N10Pro") {
        val key = ownerPairing.sharedKey
        val protected = native.protect(key)
        synchronized(lock) {
            values.setProperty(KEY_PEER_ID, deviceId)
            values.setProperty(KEY_PEER_NAME, deviceName)
            values.setProperty(KEY_SECRET, Base64.getEncoder().encodeToString(protected))
            values.remove(KEY_OWNER_CODE)
            persist()
        }
    }

    fun unpair() = synchronized(lock) {
        values.remove(KEY_PEER_ID); values.remove(KEY_PEER_NAME); values.remove(KEY_SECRET); values.remove(KEY_OWNER_CODE)
        persist()
    }

    private fun ownerPairingCodeLocked(): String = values.getProperty(KEY_OWNER_CODE)
        ?: (0..999999).random().toString().padStart(6, '0').also { values.setProperty(KEY_OWNER_CODE, it); persist() }

    private fun derivePairingKey(code: String): ByteArray = TransferCrypto.hkdfSha256(
        code.encodeToByteArray(), "BetterHv-manual-v1".encodeToByteArray(),
        "paired-device".encodeToByteArray(), 32
    )

    private fun persist() {
        paths.settings.parentFile?.mkdirs()
        val temporary = File(paths.settings.parentFile, "${paths.settings.name}.tmp")
        temporary.outputStream().use { values.store(it, "NoteLink Windows settings") }
        runCatching {
            Files.move(temporary.toPath(), paths.settings.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temporary.toPath(), paths.settings.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val KEY_LOCAL_ID = "localDeviceId"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_RECEIVE = "receiveEnabled"
        const val KEY_SHOW_RECENT_EVENTS = "showRecentTransferEvents"
        const val KEY_PEER_ID = "peerDeviceId"
        const val KEY_PEER_NAME = "peerDeviceName"
        const val KEY_SECRET = "protectedSharedKey"
        const val KEY_OWNER_CODE = "ownerPairingCode"
    }
}

interface DesktopSenderLease : AutoCloseable {
    var item: QueueItem
    val file: File?
    val text: String?
    fun heartbeat()
    fun markTransferring()
    fun markAwaitingCommit()
    fun commit()
    fun release()
    override fun close() = release()
}

class DesktopQueueRepository(private val paths: DesktopPaths) : QueueStore, AutoCloseable {
    private val databaseExisted = paths.queueDatabase.isFile && paths.queueDatabase.length() > 0
    private val driver = JdbcSqliteDriver("jdbc:sqlite:${paths.queueDatabase.absolutePath}")
    private val database = SenderQueueDatabase(driver)
    private val queries = database.queueItemQueries
    private val coordinator = QueueCoordinator(this)
    private val mutableItems = MutableStateFlow<List<QueueItem>>(emptyList())
    val itemFlow: StateFlow<List<QueueItem>> = mutableItems

    init {
        if (!databaseExisted) SenderQueueDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA journal_mode=WAL", 0)
        driver.execute(null, "PRAGMA busy_timeout=5000", 0)
        publish()
    }

    @Synchronized fun enqueueFiles(files: List<File>, destinationDeviceId: String?): List<QueueItem> = files.map { source ->
        require(source.isFile) { "文件不存在：${source.name}" }
        val isPdf = source.extension.equals("pdf", ignoreCase = true) ||
            Files.probeContentType(source.toPath()) == "application/pdf"
        val limit = if (isPdf) TransferLimits.MAX_PDF_BYTES else TransferLimits.MAX_IMAGE_BYTES
        require(source.length() <= limit) { if (isPdf) "PDF 不能超过 512 MiB" else "图片不能超过 64 MiB" }
        val mime = if (isPdf) "application/pdf" else {
            Files.probeContentType(source.toPath())?.takeIf { it.startsWith("image/") }
                ?: when (source.extension.lowercase()) {
                    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> error("只支持图片或 PDF")
                }
        }
        val id = UUID.randomUUID()
        val target = File(paths.outbox, "$id.bin")
        val temporary = File(paths.outbox, "$id.tmp")
        source.copyTo(temporary, overwrite = true)
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        QueueItem(
            id, destinationDeviceId, if (isPdf) ContentKind.PDF else ContentKind.IMAGE,
            mime, target.length(), sha256(target),
            System.currentTimeMillis(), nextPosition(), displayName = source.name
        ).also { insertWithPayload(it, target.absolutePath, null) }
    }.also { publish() }

    @Synchronized fun enqueueText(text: String, destinationDeviceId: String?): QueueItem {
        val value = text.trim()
        require(value.isNotBlank()) { "文字不能为空" }
        val bytes = value.encodeToByteArray()
        require(bytes.size <= TransferLimits.MAX_TEXT_BYTES) { "文字不能超过 256 KiB" }
        return QueueItem(
            UUID.randomUUID(), destinationDeviceId, ContentKind.TEXT, "text/plain; charset=utf-8",
            bytes.size.toLong(), TransferCrypto.sha256(bytes), System.currentTimeMillis(), nextPosition(), displayName = value.take(32)
        ).also { insertWithPayload(it, null, value); publish() }
    }

    @Synchronized fun counts(): ContentCounts = items().filter { it.state == QueueState.PENDING }.let { available ->
        ContentCounts(
            available.count { it.kind == ContentKind.IMAGE },
            available.count { it.kind == ContentKind.TEXT },
            available.count { it.kind == ContentKind.PDF }
        )
    }

    @Synchronized fun leaseNext(kind: ContentKind, destinationDeviceId: String): DesktopSenderLease? {
        val leased = coordinator.leaseNext(kind, destinationDeviceId) ?: return null
        publish()
        return Lease(leased)
    }

    @Synchronized fun delete(id: UUID): Boolean {
        payloadPath(id)?.let(::File)?.delete()
        return remove(id)
    }

    @Synchronized override fun items(): List<QueueItem> = queries.selectAll().executeAsList().map(Queue_items::queueItem)

    @Synchronized override fun find(id: UUID): QueueItem? = queries.selectById(id.toString()).executeAsOneOrNull()?.queueItem()

    @Synchronized override fun next(kind: ContentKind, destinationDeviceId: String?, now: Long): QueueItem? {
        releaseExpired(now)
        return items().firstOrNull { it.kind == kind && it.state == QueueState.PENDING &&
            (it.destinationDeviceId == null || it.destinationDeviceId == destinationDeviceId) }
    }

    @Synchronized override fun insert(item: QueueItem) = insertWithPayload(item, null, null)

    @Synchronized override fun update(item: QueueItem) {
        require(find(item.id) != null) { "Unknown queue item ${item.id}" }
        queries.updateItem(
            item.destinationDeviceId, item.kind.name, item.mimeType, item.byteLength, item.sha256,
            item.createdAt, item.position, item.state.name, item.displayName, item.leaseExpiresAt,
            item.failureReason, item.id.toString()
        )
        publish()
    }

    @Synchronized override fun remove(id: UUID): Boolean = (find(id) != null).also {
        queries.deleteById(id.toString()); publish()
    }

    @Synchronized override fun reorder(idsInOrder: List<UUID>) {
        require(idsInOrder.toSet() == items().map(QueueItem::id).toSet())
        database.transaction {
            idsInOrder.forEachIndexed { index, id -> queries.updatePosition(index.toLong(), id.toString()) }
        }
        publish()
    }

    @Synchronized override fun releaseExpired(now: Long): Int {
        val count = queries.countExpired(now).executeAsOne().toInt()
        if (count > 0) { queries.releaseExpired(now); publish() }
        return count
    }

    private fun insertWithPayload(item: QueueItem, payload: String?, text: String?) {
        queries.insertItem(
            item.id.toString(), item.destinationDeviceId, item.kind.name, item.mimeType, item.byteLength,
            item.sha256, item.createdAt, item.position, item.state.name, item.displayName,
            item.leaseExpiresAt, item.failureReason, payload, text
        )
    }

    private fun payloadPath(id: UUID): String? = queries.selectPayloadPath(id.toString()).executeAsOneOrNull()?.payload_path
    private fun text(id: UUID): String? = queries.selectText(id.toString()).executeAsOneOrNull()?.text_content
    private fun nextPosition() = (items().maxOfOrNull(QueueItem::position) ?: -1L) + 1
    private fun publish() { mutableItems.value = items() }
    override fun close() = driver.close()

    private inner class Lease(initial: QueueItem) : DesktopSenderLease {
        override var item = initial
        private var finished = false
        override val file: File? get() = payloadPath(item.id)?.let(::File)?.takeIf(File::isFile)
        override val text: String? get() = text(item.id)
        override fun heartbeat() {
            if (!finished) {
                item = coordinator.heartbeat(item.id)
            }
        }
        override fun markTransferring() {
            if (!finished) {
                item = coordinator.transition(item.id, QueueState.TRANSFERRING)
            }
        }
        override fun markAwaitingCommit() {
            if (!finished) {
                item = coordinator.transition(item.id, QueueState.AWAITING_COMMIT)
            }
        }
        override fun commit() {
            if (!finished) {
                file?.delete()
                coordinator.commit(item.id)
                finished = true
                publish()
            }
        }
        override fun release() {
            if (!finished) {
                coordinator.release(item.id)
                finished = true
                publish()
            }
        }
    }
}

enum class DesktopInboxState { RECEIVING, COMPLETE, FAILED }
data class DesktopInboxItem(
    val artifactId: UUID, val sourceDeviceId: String, val displayName: String, val mimeType: String,
    val byteLength: Long, val sha256: ByteArray, val file: File, val state: DesktopInboxState,
    val createdAt: Long, val receivedAt: Long?, val error: String?
)
sealed interface DesktopInboxBegin { data class Receive(val partial: File) : DesktopInboxBegin; data object AlreadyReceived : DesktopInboxBegin }

class DesktopInboxRepository(private val paths: DesktopPaths) : AutoCloseable {
    private val databaseExisted = paths.inboxDatabase.isFile && paths.inboxDatabase.length() > 0
    private val driver = JdbcSqliteDriver("jdbc:sqlite:${paths.inboxDatabase.absolutePath}")
    private val database = ExportInboxDatabase(driver)
    private val queries = database.inboxExportQueries
    private val mutableItems = MutableStateFlow<List<DesktopInboxItem>>(emptyList())
    val itemFlow: StateFlow<List<DesktopInboxItem>> = mutableItems

    init {
        if (!databaseExisted) ExportInboxDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA journal_mode=WAL", 0)
        driver.execute(null, "PRAGMA busy_timeout=5000", 0)
        publish()
    }

    @Synchronized fun begin(sourceId: String, offer: ExportTransferOffer): DesktopInboxBegin {
        find(offer.artifactId)?.takeIf { it.state == DesktopInboxState.COMPLETE && it.sourceDeviceId == sourceId &&
            it.sha256.contentEquals(offer.sha256) && it.file.isFile }?.let { return DesktopInboxBegin.AlreadyReceived }
        val extension = if (offer.mimeType == "application/pdf") "pdf" else "png"
        val target = File(paths.inbox, "${offer.artifactId}.$extension")
        queries.upsert(
            offer.artifactId.toString(), sourceId, safeName(offer.displayName), offer.mimeType,
            offer.byteLength, offer.sha256, target.absolutePath, DesktopInboxState.RECEIVING.name,
            System.currentTimeMillis(), null, null
        )
        publish()
        return DesktopInboxBegin.Receive(File(paths.inbox, "${offer.artifactId}.part"))
    }

    @Synchronized fun complete(id: UUID, partial: File) {
        val item = requireNotNull(find(id))
        require(partial.length() == item.byteLength && sha256(partial).contentEquals(item.sha256)) { "文件长度或校验值不一致" }
        Files.move(partial.toPath(), item.file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        updateState(id, DesktopInboxState.COMPLETE, null, System.currentTimeMillis())
    }
    @Synchronized fun fail(id: UUID, message: String) = updateState(id, DesktopInboxState.FAILED, message, null)
    @Synchronized fun delete(id: UUID) { find(id)?.file?.delete(); File(paths.inbox, "$id.part").delete(); queries.deleteById(id.toString()); publish() }
    @Synchronized fun find(id: UUID) = items().firstOrNull { it.artifactId == id }
    @Synchronized fun items(): List<DesktopInboxItem> = queries.selectAll().executeAsList().map(Inbox_exports::inboxItem)
    private fun updateState(id: UUID, state: DesktopInboxState, error: String?, receivedAt: Long?) {
        queries.updateState(state.name, receivedAt, error, id.toString())
        publish()
    }
    private fun publish() { mutableItems.value = items() }
    override fun close() = driver.close()
    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(96).ifBlank { "BetterHv-export" }
}

private fun Queue_items.queueItem() = QueueItem(
    UUID.fromString(id), destination_device_id, ContentKind.valueOf(kind), mime_type, byte_length, sha256,
    created_at, position, QueueState.valueOf(state), display_name, lease_expires_at, failure_reason
)

private fun Inbox_exports.inboxItem() = DesktopInboxItem(
    UUID.fromString(artifact_id), source_device_id, display_name, mime_type, byte_length, sha256,
    File(file_path), DesktopInboxState.valueOf(state), created_at, received_at, error
)

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
