package com.betterhv.note.sender

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.UUID

/** Gives sharesheet imports a short, explicit way to remove the complete imported batch. */
object ShareUndoNotifier {
    private const val CHANNEL = "betterhv-share-imports"
    internal const val NOTIFICATION_ID = 202
    internal const val EXTRA_IDS = "queue_item_ids"

    fun show(context: Context, ids: List<UUID>) {
        if (ids.isEmpty()) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "分享入队", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val undo = Intent(context, ShareUndoReceiver::class.java)
            .putStringArrayListExtra(EXTRA_IDS, ArrayList(ids.map(UUID::toString)))
        val pendingUndo = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID, undo,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("已加入 NoteLink")
            .setContentText("已将 ${ids.size} 项内容加入发送队列")
            .setAutoCancel(true)
            .addAction(0, "撤销", pendingUndo)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}

class ShareUndoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ids = intent.getStringArrayListExtra(ShareUndoNotifier.EXTRA_IDS).orEmpty()
        PhoneQueueRepository(context).use { queue ->
            ids.mapNotNull { value -> runCatching { UUID.fromString(value) }.getOrNull() }
                .forEach(queue::delete)
            TransferForegroundService.sync(context, queue.items().isNotEmpty())
        }
        NotificationManagerCompat.from(context).cancel(ShareUndoNotifier.NOTIFICATION_ID)
        Toast.makeText(context, "已撤销本次分享", Toast.LENGTH_SHORT).show()
    }
}
