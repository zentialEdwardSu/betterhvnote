package com.betterhv.note.sender

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.betterhv.transfer.core.ContentKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ShareImportInstrumentedTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test fun importsSingleAndMultiplePdfsImagesAndText() = runBlocking {
    val sources = mutableListOf<File>()
    val addedIds = mutableListOf<UUID>()
    PhoneQueueRepository(context).use { queue ->
      try {
        val singlePdf = pdfSource(sources)
        addedIds += enqueueSharedIntent(
          context,
          streamIntent(Intent.ACTION_SEND, "application/pdf", listOf(singlePdf)),
          queue,
          null,
        )

        val multiplePdfs = List(2) { pdfSource(sources) }
        addedIds += enqueueSharedIntent(
          context,
          streamIntent(Intent.ACTION_SEND_MULTIPLE, "application/pdf", multiplePdfs),
          queue,
          null,
        )

        val image = imageSource(sources)
        addedIds += enqueueSharedIntent(
          context,
          streamIntent(Intent.ACTION_SEND, "image/png", listOf(image)),
          queue,
          null,
        )

        addedIds += enqueueSharedIntent(
          context,
          Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "shared text"),
          queue,
          null,
        )

        val imported = queue.items().filter { it.id in addedIds }
        assertEquals(5, imported.size)
        assertEquals(3, imported.count { it.kind == ContentKind.PDF })
        assertEquals(1, imported.count { it.kind == ContentKind.IMAGE })
        assertEquals(1, imported.count { it.kind == ContentKind.TEXT })
      } finally {
        addedIds.forEach(queue::delete)
        sources.forEach(File::delete)
      }
    }
  }

  @Test fun rejectsUnsupportedSharedStreams() {
    val sources = mutableListOf<File>()
    PhoneQueueRepository(context).use { queue ->
      try {
        val archive = sourceFile("zip", sources) { writeBytes(byteArrayOf(1, 2, 3)) }
        val error = assertThrows(IllegalArgumentException::class.java) {
          enqueueSharedIntent(
            context,
            streamIntent(Intent.ACTION_SEND, "application/zip", listOf(archive)),
            queue,
            null,
          )
        }
        assertTrue(error.message.orEmpty().contains("Unsupported shared file type"))
      } finally {
        sources.forEach(File::delete)
      }
    }
  }

  private fun pdfSource(sources: MutableList<File>) = sourceFile("pdf", sources) {
    writeBytes("%PDF-1.4\n%%EOF\n".encodeToByteArray())
  }

  private fun imageSource(sources: MutableList<File>) = sourceFile("png", sources) {
    outputStream().use { output ->
      assertTrue(createBitmap(1, 1).compress(Bitmap.CompressFormat.PNG, 100, output))
    }
  }

  private fun sourceFile(extension: String, sources: MutableList<File>, write: File.() -> Unit) =
    File(context.filesDir, "inbox/share-test-${UUID.randomUUID()}.$extension").apply {
      parentFile?.mkdirs()
      write()
      sources += this
    }

  private fun streamIntent(action: String, mimeType: String, files: List<File>): Intent {
    val uris = files.map {
      FileProvider.getUriForFile(context, "${context.packageName}.files", it)
    }
    return Intent(action).setType(mimeType).apply {
      clipData = ClipData.newUri(context.contentResolver, "shared", uris.first()).also { clip ->
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
      }
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      if (action == Intent.ACTION_SEND) {
        putExtra(Intent.EXTRA_STREAM, uris.single())
      } else {
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
      }
    }
  }
}
