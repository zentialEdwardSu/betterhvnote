package com.betterhv.note.export

import android.content.ContentUris
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportDownloadsInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun repeatedSaveOverwritesSingleMediaStoreEntry() {
        val displayName = "overwrite-${System.nanoTime()}_abcdef12.pdf"
        val source = File(context.cacheDir, displayName)
        val taskId = UUID.randomUUID()
        val artifactId = UUID.randomUUID()
        val downloads = ExportDownloads(context)
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/BetterHvNote/"
        try {
            source.writeBytes(byteArrayOf(1, 2, 3))
            downloads.save(artifact(source, displayName, taskId, artifactId))
            source.writeBytes(byteArrayOf(9, 8, 7, 6))
            downloads.save(artifact(source, displayName, taskId, artifactId))

            val matches = context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(displayName, relativePath),
                null
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0)))
                }
            }.orEmpty()
            assertEquals(1, matches.size)
            val bytes = context.contentResolver.openInputStream(matches.single())!!.use { it.readBytes() }
            assertArrayEquals(byteArrayOf(9, 8, 7, 6), bytes)
        } finally {
            context.contentResolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(displayName, relativePath)
            )
            source.delete()
        }
    }

    private fun artifact(file: File, name: String, taskId: UUID, artifactId: UUID) = ExportArtifact(
        id = artifactId,
        taskId = taskId,
        file = file,
        displayName = name,
        mimeType = "application/pdf",
        byteLength = file.length(),
        sha256 = ByteArray(32),
        fingerprint = "test",
        createdAt = System.currentTimeMillis()
    )
}
