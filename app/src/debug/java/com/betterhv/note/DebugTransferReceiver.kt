package com.betterhv.note

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.betterhv.transfer.android.AndroidPairingController
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.RemotePayload
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Debug-only end-to-end transport probe. Production insertion continues through InsertionCoordinator. */
class DebugTransferReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAIR -> {
                AndroidPairingController(context).confirmManual(
                    "betterhv-phone", "NoteLink", requireNotNull(intent.getStringExtra(EXTRA_CODE))
                )
                writeResult(context, "PAIRED")
            }
            ACTION_REQUEST_TEXT -> request(context, ContentKind.TEXT, intent.getBooleanExtra(EXTRA_RELEASE, false))
            ACTION_REQUEST_IMAGE -> request(context, ContentKind.IMAGE, intent.getBooleanExtra(EXTRA_RELEASE, false))
        }
    }

    private fun request(context: Context, kind: ContentKind, release: Boolean) {
        writeResult(context, "RUNNING:${kind.name}")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val client = PhoneTransferClient(context)
            try {
                val lease = requireNotNull(client.requestNext(kind)) { "No ${kind.name} item" }
                val description = when (val payload = lease.payload) {
                    is RemotePayload.Text -> "TEXT:${payload.text}"
                    is RemotePayload.Image -> "IMAGE:${payload.stagedFile.length()}:${payload.item.sha256.toHex()}"
                }
                if (release) lease.release() else lease.commit()
                (lease.payload as? RemotePayload.Image)?.stagedFile?.delete()
                writeResult(context, "${if (release) "RELEASED" else "COMMITTED"}:$description")
            } catch (t: Throwable) {
                Log.e(TAG, "Debug transfer failed", t)
                writeResult(context, "ERROR:${t.javaClass.simpleName}:${t.message}")
            } finally {
                client.close()
                pending.finish()
            }
        }
    }

    private fun writeResult(context: Context, value: String) {
        File(context.filesDir, RESULT_FILE).writeText(value)
        Log.i(TAG, value)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val ACTION_PAIR = "com.betterhv.note.debug.PAIR"
        const val ACTION_REQUEST_TEXT = "com.betterhv.note.debug.REQUEST_TEXT"
        const val ACTION_REQUEST_IMAGE = "com.betterhv.note.debug.REQUEST_IMAGE"
        const val EXTRA_CODE = "code"
        const val EXTRA_RELEASE = "release"
        const val RESULT_FILE = "debug-transfer-result.txt"
        private const val TAG = "BetterHvDebug"
    }
}
