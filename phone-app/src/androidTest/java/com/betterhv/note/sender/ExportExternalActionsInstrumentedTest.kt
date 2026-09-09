package com.betterhv.note.sender

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportExternalActionsInstrumentedTest {
    @SuppressLint("QueryPermissionsNeeded")
    @Test fun noteLinkIsAvailableForSingleAndMultiplePdfShares() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE).forEach { action ->
            val matches = context.packageManager.queryIntentActivities(
                Intent(action).setType("application/pdf").setPackage(context.packageName),
                PackageManager.MATCH_DEFAULT_ONLY,
            )

            assertTrue(matches.any { it.activityInfo.name == MainActivity::class.java.name })
        }
    }

    @Test fun pdfAndPngExternalActionsResolveAndShareChooserLaunches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                listOf("application/pdf" to "test.pdf", "image/png" to "test.png").forEach { (mime, name) ->
                    val item = inboxItem(mime, name)
                    val uri = "content://${activity.packageName}.files/inbox/${item.artifactId}.${name.substringAfterLast('.')}".toUri()
                    val view = viewExportIntent(activity, item, uri)

                    assertEquals(Intent.ACTION_VIEW, view.action)
                    assertEquals(mime, view.type)
                    assertEquals(uri, view.data)
                    assertNotNull(view.clipData)
                    assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                }

                val item = inboxItem("application/pdf", "test.pdf")
                val uri = "content://${activity.packageName}.files/inbox/${item.artifactId}.pdf".toUri()
                val chooser = shareExportIntent(activity, item, uri)
                val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)

                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                assertNotNull(chooser.clipData)
                assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals(Intent.ACTION_SEND, send?.action)
                assertEquals(
                    uri,
                    send?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_STREAM, Uri::class.java) }
                )
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
