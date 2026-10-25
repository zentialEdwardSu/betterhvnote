package com.betterhv.note.backup

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
  @get:Rule val temporary = TemporaryFolder()

  private fun source(): File = temporary.newFolder().also { directory ->
    File(directory, "documents").mkdirs()
    File(directory, "documents/default.inknote").writeText("database-snapshot")
    File(directory, "settings.json").writeText("settings-snapshot")
  }

  @Test fun archiveRoundTripPreservesEveryByteAndMetadata() {
    val source = source()
    val manifest = BackupArchive.manifest(source, 8, 12)
    val output = ByteArrayOutputStream()
    BackupArchive.write(source, manifest, output)
    val restored = temporary.newFolder()
    assertEquals(manifest, BackupArchive.read(ByteArrayInputStream(output.toByteArray()), restored))
    manifest.files.forEach { assertArrayEquals(File(source, it.path).readBytes(), File(restored, it.path).readBytes()) }
  }

  @Test fun rejectsEscapingAndUnexpectedPayloadPaths() {
    listOf("../outside", "/outside", "documents/../outside", "documents\\assets\\x", "C:/x",
      "documents/assets/../secret", "documents/assets//x", "credentials.json").forEach {
      assertFalse(it, BackupArchive.payloadPath(it))
    }
    assertTrue(BackupArchive.payloadPath("documents/assets/photo.png"))
  }

  @Test fun rejectsMissingPayloadAndTamperedBytes() {
    val root = source()
    val manifest = BackupArchive.manifest(root, 8, 1)
    for (tamper in listOf(false, true)) {
      val archive = ByteArrayOutputStream()
      ZipOutputStream(archive).use { zip ->
        zip.putNextEntry(ZipEntry("manifest.json")); zip.write(Gson().toJson(manifest).toByteArray()); zip.closeEntry()
        if (tamper) manifest.files.forEach { entry ->
          zip.putNextEntry(ZipEntry(entry.path)); zip.write(ByteArray(entry.size.toInt()) { 42 }); zip.closeEntry()
        }
      }
      assertThrows(IllegalArgumentException::class.java) {
        BackupArchive.read(ByteArrayInputStream(archive.toByteArray()), temporary.newFolder())
      }
    }
  }

  @Test fun cancellationStopsWriting() {
    val root = source()
    assertThrows(java.util.concurrent.CancellationException::class.java) {
      BackupArchive.write(root, BackupArchive.manifest(root, 8, 1), ByteArrayOutputStream()) { _, _ ->
        throw java.util.concurrent.CancellationException()
      }
    }
  }
}
