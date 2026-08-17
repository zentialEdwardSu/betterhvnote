package com.betterhv.note.export

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ExportDownloads(private val context: Context) {
    @Synchronized
    fun save(artifact: ExportArtifact): String {
        return if (Build.VERSION.SDK_INT >= 29) saveWithMediaStore(artifact) else saveLegacy(artifact)
    }

    private fun saveWithMediaStore(artifact: ExportArtifact): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/BetterHvNote/"
        val existing = findExisting(collection, artifact.displayName, relativePath)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, artifact.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, artifact.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val created = existing == null
        val uri = existing?.also { resolver.update(it, values, null, null) }
            ?: resolver.insert(collection, values)
            ?: error("无法创建 Downloads 文件")
        try {
            resolver.openOutputStream(uri, "rwt")?.use { output ->
                FileInputStream(artifact.file).use { it.copyTo(output, 64 * 1024) }
            } ?: error("无法写入 Downloads 文件")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return artifact.displayName
        } catch (t: Throwable) {
            if (created) resolver.delete(uri, null, null)
            else resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            throw t
        }
    }

    private fun findExisting(collection: Uri, name: String, relativePath: String): Uri? {
        val resolver = context.contentResolver
        return resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, relativePath),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(artifact: ExportArtifact): String {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BetterHvNote"
        ).also(File::mkdirs)
        val target = File(directory, artifact.displayName)
        val temp = File(directory, ".${target.name}.tmp")
        try {
            FileInputStream(artifact.file).use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output, 64 * 1024); output.fd.sync() }
            }
            runCatching {
                Files.move(
                    temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.getOrElse {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return target.name
        } finally {
            temp.delete()
        }
    }
}
