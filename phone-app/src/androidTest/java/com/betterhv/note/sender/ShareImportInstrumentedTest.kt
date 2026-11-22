package com.betterhv.note.sender

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.betterhv.note.sender.data.PhoneQueueRepository
import com.betterhv.note.sender.share.ShareIntake
import com.betterhv.note.sender.share.ShareRejectReason
import com.betterhv.transfer.core.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ShareImportInstrumentedTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun importsSingleAndMultiplePdfsImagesAndText() {
    withQueue { queue, sources, added ->
      val intake = ShareIntake(context, queue)
      added += intake.accept(
        streamIntent(
          Intent.ACTION_SEND,
          "application/pdf",
          listOf(pdfSource(sources))
        ), null
      )
        .accepted
      added += intake.accept(
        streamIntent(
          Intent.ACTION_SEND_MULTIPLE,
          "application/pdf",
          List(2) { pdfSource(sources) }),
        null,
      ).accepted
      added += intake.accept(
        streamIntent(
          Intent.ACTION_SEND,
          "image/png",
          listOf(imageSource(sources))
        ), null
      )
        .accepted
      added += intake.accept(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "shared text"),
        null,
      ).accepted

      val imported = queue.items().filter { it.id in added }
      assertEquals(5, imported.size)
      assertEquals(3, imported.count { it.kind == ContentKind.PDF })
      assertEquals(1, imported.count { it.kind == ContentKind.IMAGE })
      assertEquals(1, imported.count { it.kind == ContentKind.TEXT })
    }
  }

  @Test
  fun sniffsScreenshotWithGenericProviderType() {
    withQueue { queue, sources, added ->
      val screenshot = sourceFile("bin", sources) {
        outputStream().use { output ->
          assertTrue(
            createBitmap(
              4,
              3
            ).compress(Bitmap.CompressFormat.PNG, 100, output)
          )
        }
      }
      val result = ShareIntake(context, queue).accept(
        streamIntent(Intent.ACTION_SEND, "application/octet-stream", listOf(screenshot)),
        null,
      )
      added += result.accepted

      val item = queue.items().single { it.id in added }
      assertEquals(ContentKind.IMAGE, item.kind)
      assertEquals("image/png", item.mimeType)
      assertTrue(result.rejected.isEmpty())
    }
  }

  @Test
  fun keepsSupportedItemsWhenOneItemFails() {
    withQueue { queue, sources, added ->
      val unsupported = sourceFile("zip", sources) { writeBytes(byteArrayOf(1, 2, 3)) }
      val intent = streamIntent(
        Intent.ACTION_SEND_MULTIPLE,
        "*/*",
        listOf(imageSource(sources), unsupported, pdfSource(sources)),
      )
      val result = ShareIntake(context, queue).accept(intent, null)
      added += result.accepted

      assertEquals(2, result.accepted.size)
      assertEquals(1, result.rejected.size)
      assertEquals(ShareRejectReason.UNSUPPORTED_TYPE, result.rejected.single().reason)
      assertEquals(2, queue.items().count { it.id in added })
    }
  }

  @Test
  fun ignoresContentlessShare() {
    withQueue { queue, _, added ->
      val result =
        ShareIntake(context, queue).accept(Intent(Intent.ACTION_SEND).setType("image/*"), null)

      assertTrue(result.openedOnly)
      assertTrue(result.accepted.isEmpty())
      assertTrue(added.isEmpty())
    }
  }

  @Test
  fun ignoresDuplicateDeliveryOfTheSameIntent() {
    withQueue { queue, sources, added ->
      val intake = ShareIntake(context, queue)
      val intent = streamIntent(Intent.ACTION_SEND, "image/png", listOf(imageSource(sources)))

      added += intake.accept(intent, null).accepted
      val duplicate = intake.accept(intent, null)

      assertTrue(duplicate.openedOnly)
      assertTrue(duplicate.accepted.isEmpty())
      assertEquals(1, queue.items().count { it.id in added })
    }
  }

  @Test
  fun declaresSingleTaskLaunchMode() {
    val info = context.packageManager.getActivityInfo(
      ComponentName(context, MainActivity::class.java),
      0,
    )

    assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
  }

  private fun withQueue(block: (PhoneQueueRepository, MutableList<File>, MutableList<UUID>) -> Unit) {
    val sources = mutableListOf<File>()
    val added = mutableListOf<UUID>()
    PhoneQueueRepository(context).use { queue ->
      try {
        block(queue, sources, added)
      } finally {
        added.distinct().forEach { runCatching { queue.delete(it) } }
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
