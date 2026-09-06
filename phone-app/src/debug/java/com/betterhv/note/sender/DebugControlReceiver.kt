package com.betterhv.note.sender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.betterhv.transfer.android.AndroidPairingController
import java.io.File

/** ADB-only driver compiled into debug APKs so restrictive vendor ROMs can be tested without touch injection. */
class DebugControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val queue = PhoneQueueRepository(context)
        try {
            val pairing = AndroidPairingController(context)
            when (intent.action) {
                ACTION_PAIR -> pairing.confirmManual(
                    "betterhv-note", "N10Pro", requireNotNull(intent.getStringExtra(EXTRA_CODE))
                )
                ACTION_TEXT -> queue.enqueueText(
                    intent.getStringExtra(EXTRA_TEXT) ?: "BetterHv BLE debug text",
                    pairing.pairedDevice?.id
                )
                ACTION_IMAGE -> {
                    val source = File(context.filesDir, "camera/debug-transfer.png")
                    source.parentFile?.mkdirs()
                    source.outputStream().use {
                        createBitmap(640, 480)
                            .apply { eraseColor(0xff3f51b5.toInt()) }
                            .compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    val requestedBytes = intent.getLongExtra(EXTRA_IMAGE_BYTES, 0L)
                    if (requestedBytes > source.length()) {
                        java.io.RandomAccessFile(source, "rw").use { it.setLength(requestedBytes) }
                    }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source)
                    val item = queue.enqueueImage(uri, pairing.pairedDevice?.id)
                    if (intent.getBooleanExtra(EXTRA_MOVE_TO_FRONT, false)) {
                        while (queue.items().firstOrNull()?.id != item.id) queue.move(item.id, -1)
                    }
                    File(context.filesDir, DEBUG_ITEM_ID_FILE).writeText(item.id.toString())
                    source.delete()
                }
                ACTION_DELETE_DEBUG -> File(context.filesDir, DEBUG_ITEM_ID_FILE).takeIf(File::isFile)
                    ?.readText()?.let(java.util.UUID::fromString)?.let(queue::delete)
                ACTION_CLEAR -> queue.items().forEach { queue.delete(it.id) }
                else -> error("Unknown debug action: ${intent.action}")
            }
            TransferForegroundService.sync(context, queue.items().isNotEmpty())
        } finally {
            queue.close()
        }
    }

    companion object {
        const val ACTION_PAIR = "com.betterhv.note.sender.debug.PAIR"
        const val ACTION_TEXT = "com.betterhv.note.sender.debug.TEXT"
        const val ACTION_IMAGE = "com.betterhv.note.sender.debug.IMAGE"
        const val ACTION_DELETE_DEBUG = "com.betterhv.note.sender.debug.DELETE_IMAGE"
        const val ACTION_CLEAR = "com.betterhv.note.sender.debug.CLEAR"
        const val EXTRA_CODE = "code"
        const val EXTRA_TEXT = "text"
        const val EXTRA_IMAGE_BYTES = "imageBytes"
        const val EXTRA_MOVE_TO_FRONT = "moveToFront"
        const val DEBUG_ITEM_ID_FILE = "debug-item-id.txt"
    }
}
