package com.betterhv.note.sender

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportExternalActionsInstrumentedTest {
    @Test fun pdfAndPngExternalActionsResolveAndShareChooserLaunches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                listOf("application/pdf" to "test.pdf", "image/png" to "test.png").forEach { (mime, name) ->
                    val item = inboxItem(mime, name)
                    val uri = Uri.parse("content://${activity.packageName}.files/inbox/${item.artifactId}.${name.substringAfterLast('.')}")
                    val view = viewExportIntent(activity, item, uri)

                    assertEquals(Intent.ACTION_VIEW, view.action)
                    assertEquals(mime, view.type)
                    assertEquals(uri, view.data)
                    assertNotNull(view.clipData)
                    assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    assertNotNull(activity.packageManager.resolveActivity(view, 0))
                }

                val item = inboxItem("application/pdf", "test.pdf")
                val uri = Uri.parse("content://${activity.packageName}.files/inbox/${item.artifactId}.pdf")
                val chooser = shareExportIntent(activity, item, uri)
                val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)

                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                assertNotNull(chooser.clipData)
                assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals(Intent.ACTION_SEND, send?.action)
                assertEquals(uri, send?.getParcelableExtra(Intent.EXTRA_STREAM))
                assertNotNull(send?.clipData)
                assertTrue(requireNotNull(send).flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                activity.startActivity(chooser)
            }
        }
    }

    private fun inboxItem(mimeType: String, displayName: String) = InboxExport(
        artifactId = UUID.randomUUID(),
        sourceDeviceId = "test-note",
        displayName = displayName,
        mimeType = mimeType,
        byteLength = 1L,
        sha256 = ByteArray(32),
        filePath = "",
        state = InboxExportState.COMPLETE,
        createdAt = 0L,
        receivedAt = 0L,
        error = null
    )
}
