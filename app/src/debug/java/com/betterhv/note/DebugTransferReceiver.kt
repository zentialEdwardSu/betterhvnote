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
            ACTION_PAIR_DISCOVERED -> pairDiscovered(
                context,
                requireNotNull(intent.getStringExtra(EXTRA_CODE)),
                intent.getStringExtra(EXTRA_CLIENT_ID)
            )
            ACTION_UNPAIR -> {
                val clientId = requireNotNull(intent.getStringExtra(EXTRA_CLIENT_ID))
                AndroidPairingController(context).unpair(clientId)
                writeResult(context, "UNPAIRED:$clientId")
            }
            ACTION_REQUEST_TEXT -> request(
                context, ContentKind.TEXT, intent.getBooleanExtra(EXTRA_RELEASE, false),
                intent.getStringExtra(EXTRA_CLIENT_ID)
            )
            ACTION_REQUEST_IMAGE -> request(
                context, ContentKind.IMAGE, intent.getBooleanExtra(EXTRA_RELEASE, false),
                intent.getStringExtra(EXTRA_CLIENT_ID)
            )
            ACTION_VERIFY -> verify(context, requireNotNull(intent.getStringExtra(EXTRA_CLIENT_ID)))
        }
    }

    private fun pairDiscovered(context: Context, code: String, clientId: String?) {
        writeResult(context, "RUNNING:PAIR")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val client = PhoneTransferClient(context)
            try {
                val candidates = client.discoverPairingCandidates()
                val candidate = candidates.firstOrNull { clientId == null || it.deviceId == clientId }
                    ?: error("No matching pairing candidate")
                val paired = client.pair(candidate, code)
                writeResult(context, "PAIRED:${paired.id}:${paired.name}")
            } catch (t: Throwable) {
                Log.e(TAG, "Debug pairing failed", t)
                writeResult(context, "ERROR:${t.javaClass.simpleName}:${t.message}")
            } finally {
                client.close()
                pending.finish()
            }
        }
    }

    private fun verify(context: Context, clientId: String) {
        writeResult(context, "RUNNING:VERIFY")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val client = PhoneTransferClient(context)
            try {
                val result = client.verifyConnection(clientId)
                writeResult(context, "VERIFIED:${result.ssidMatch}:${result.remote.modes}")
            } catch (t: Throwable) {
                Log.e(TAG, "Debug verification failed", t)
                writeResult(context, "ERROR:${t.javaClass.simpleName}:${t.message}")
            } finally {
                client.close()
                pending.finish()
            }
        }
    }

    private fun request(context: Context, kind: ContentKind, release: Boolean, clientId: String?) {
        writeResult(context, "RUNNING:${kind.name}")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val client = PhoneTransferClient(context)
            try {
                val lease = requireNotNull(
                    if (clientId == null) client.requestNext(kind) else client.requestNext(clientId, kind)
                ) { "No ${kind.name} item" }
                val description = when (val payload = lease.payload) {
                    is RemotePayload.Text -> "TEXT:${payload.text}"
                    is RemotePayload.Image -> "IMAGE:${payload.stagedFile.length()}:${payload.item.sha256.toHex()}"
                    is RemotePayload.Pdf -> "PDF:${payload.stagedFile.length()}:${payload.item.sha256.toHex()}"
                    is RemotePayload.AppPackage -> "APK:${payload.stagedFile.length()}:${payload.item.sha256.toHex()}"
                }
                if (release) lease.releaseAndAwait() else lease.commitAndAwait()
                when (val payload = lease.payload) {
                    is RemotePayload.Image -> payload.stagedFile.delete()
                    is RemotePayload.Pdf -> payload.stagedFile.delete()
                    is RemotePayload.AppPackage -> payload.stagedFile.delete()
                    is RemotePayload.Text -> Unit
                }
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
        const val ACTION_PAIR_DISCOVERED = "com.betterhv.note.debug.PAIR_DISCOVERED"
        const val ACTION_UNPAIR = "com.betterhv.note.debug.UNPAIR"
        const val ACTION_REQUEST_TEXT = "com.betterhv.note.debug.REQUEST_TEXT"
        const val ACTION_REQUEST_IMAGE = "com.betterhv.note.debug.REQUEST_IMAGE"
        const val ACTION_VERIFY = "com.betterhv.note.debug.VERIFY"
        const val EXTRA_CODE = "code"
        const val EXTRA_RELEASE = "release"
        const val EXTRA_CLIENT_ID = "clientId"
        const val RESULT_FILE = "debug-transfer-result.txt"
        private const val TAG = "BetterHvDebug"
    }
}
