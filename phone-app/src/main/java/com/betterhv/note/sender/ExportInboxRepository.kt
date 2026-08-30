package com.betterhv.note.sender

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.betterhv.transfer.core.ExportTransferOffer
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class InboxExportState { RECEIVING, COMPLETE, FAILED }

data class InboxExport(
    val artifactId: UUID,
    val sourceDeviceId: String,
    val displayName: String,
    val mimeType: String,
    val byteLength: Long,
    val sha256: ByteArray,
    val filePath: String,
    val state: InboxExportState,
    val createdAt: Long,
    val receivedAt: Long?,
    val error: String?
)

sealed interface InboxBeginResult {
    data class Receive(val partialFile: File) : InboxBeginResult
    data object AlreadyReceived : InboxBeginResult
}

class ExportInboxRepository(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = InboxDatabase(appContext)
    private val inboxDir = File(appContext.filesDir, "inbox").also(File::mkdirs)
    val items: StateFlow<List<InboxExport>> = sharedItems

    init { publish() }

    @Synchronized
    fun begin(sourceDeviceId: String, offer: ExportTransferOffer): InboxBeginResult {
        val existing = find(offer.artifactId)
        existing?.takeIf {
            it.sourceDeviceId == sourceDeviceId && it.state == InboxExportState.COMPLETE &&
                it.sha256.contentEquals(offer.sha256) && File(it.filePath).isFile
        }?.let { return InboxBeginResult.AlreadyReceived }
        val partial = File(inboxDir, "${offer.artifactId}.part")
        val final = File(inboxDir, "${offer.artifactId}.${extension(offer.mimeType)}")
        if (existing != null && (!existing.sha256.contentEquals(offer.sha256) || existing.byteLength != offer.byteLength)) {
            partial.delete()
            File(existing.filePath).delete()
        }
        ContentValues().apply {
            put("artifact_id", offer.artifactId.toString()); put("source_device_id", sourceDeviceId)
            put("display_name", safeName(offer.displayName)); put("mime_type", offer.mimeType)
            put("byte_length", offer.byteLength); put("sha256", offer.sha256)
            put("file_path", final.absolutePath); put("state", InboxExportState.RECEIVING.name)
            put("created_at", System.currentTimeMillis()); putNull("received_at"); putNull("error")
        }.also {
            database.writableDatabase.insertWithOnConflict(
                "inbox_exports", null, it, SQLiteDatabase.CONFLICT_REPLACE
            )
        }
        publish()
        return InboxBeginResult.Receive(partial)
    }

    @Synchronized
    fun complete(artifactId: UUID, partial: File) {
        val item = requireNotNull(find(artifactId)) { "收件记录不存在" }
        require(partial.length() == item.byteLength) { "文件长度不一致" }
        require(sha256(partial).contentEquals(item.sha256)) { "文件校验失败" }
        val target = File(item.filePath)
        target.parentFile?.mkdirs()
        runCatching {
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        ContentValues().apply {
            put("state", InboxExportState.COMPLETE.name); put("received_at", System.currentTimeMillis()); putNull("error")
        }.also { database.writableDatabase.update("inbox_exports", it, "artifact_id=?", arrayOf(artifactId.toString())) }
        publish()
    }

    @Synchronized
    fun fail(artifactId: UUID, message: String) {
        ContentValues().apply { put("state", InboxExportState.FAILED.name); put("error", message) }.also {
            database.writableDatabase.update("inbox_exports", it, "artifact_id=?", arrayOf(artifactId.toString()))
        }
        publish()
    }

    @Synchronized
    fun cancel(artifactId: UUID) {
        File(inboxDir, "$artifactId.part").delete()
        val item = find(artifactId)
        if (item?.state != InboxExportState.COMPLETE) {
            database.writableDatabase.delete("inbox_exports", "artifact_id=?", arrayOf(artifactId.toString()))
        }
        publish()
    }

    @Synchronized
    fun delete(artifactId: UUID) {
        find(artifactId)?.filePath?.let(::File)?.delete()
        File(inboxDir, "$artifactId.part").delete()
        database.writableDatabase.delete("inbox_exports", "artifact_id=?", arrayOf(artifactId.toString()))
        publish()
    }

    @Synchronized
    fun clearCache(): Int {
        val removable = query().filter { it.state != InboxExportState.RECEIVING }
        if (removable.isEmpty()) return 0
        val writable = database.writableDatabase
        writable.beginTransaction()
        try {
            removable.forEach {
                writable.delete("inbox_exports", "artifact_id=?", arrayOf(it.artifactId.toString()))
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        removable.forEach {
            File(it.filePath).delete()
            File(inboxDir, "${it.artifactId}.part").delete()
        }
        publish()
        return removable.size
    }

    fun contentUri(item: InboxExport): Uri {
        require(item.state == InboxExportState.COMPLETE)
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", File(item.filePath))
    }

    fun saveToDownloads(item: InboxExport): String {
        require(item.state == InboxExportState.COMPLETE)
        val source = File(item.filePath)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, timestamped(item.displayName))
                put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/NoteLink")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建 Downloads 文件")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    FileInputStream(source).use { it.copyTo(output, 64 * 1024) }
                } ?: error("无法写入 Downloads 文件")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        } else error("当前系统版本不支持无权限保存到 Downloads")
        return item.displayName
    }

    @Synchronized
    fun find(id: UUID): InboxExport? = query().firstOrNull { it.artifactId == id }

    @Synchronized
    private fun query(): List<InboxExport> = database.readableDatabase.query(
        "inbox_exports", COLUMNS, null, null, null, null, "COALESCE(received_at, created_at) DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(InboxExport(
                UUID.fromString(cursor.getString(0)), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                cursor.getLong(4), cursor.getBlob(5), cursor.getString(6), InboxExportState.valueOf(cursor.getString(7)),
                cursor.getLong(8), if (cursor.isNull(9)) null else cursor.getLong(9), cursor.getString(10)
            ))
        }
    }

    private fun publish() { sharedItems.value = query() }

    override fun close() = database.close()

    private fun safeName(value: String): String = value.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120).ifBlank { "export" }
    private fun extension(mime: String) = if (mime == "application/pdf") "pdf" else "png"
    private fun timestamped(name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return "${base}_${System.currentTimeMillis()}$ext"
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

    private class InboxDatabase(context: Context) : SQLiteOpenHelper(context, "export_inbox.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE inbox_exports(
                artifact_id TEXT PRIMARY KEY, source_device_id TEXT NOT NULL, display_name TEXT NOT NULL,
                mime_type TEXT NOT NULL, byte_length INTEGER NOT NULL, sha256 BLOB NOT NULL,
                file_path TEXT NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL,
                received_at INTEGER, error TEXT
            )""".trimIndent())
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        val sharedItems = MutableStateFlow<List<InboxExport>>(emptyList())
        val COLUMNS = arrayOf(
            "artifact_id", "source_device_id", "display_name", "mime_type", "byte_length", "sha256",
            "file_path", "state", "created_at", "received_at", "error"
        )
    }
}
