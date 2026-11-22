package com.betterhv.note.sender.share

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.betterhv.note.sender.TransferForegroundService
import com.betterhv.note.sender.data.PhoneQueueRepository
import com.betterhv.transfer.core.i18n.noteLinkText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Gives sharesheet imports a short, explicit way to remove the complete imported batch. */
object ShareUndoNotifier {
  private const val CHANNEL = "betterhv-share-imports"
  internal const val NOTIFICATION_ID = 202
  internal const val EXTRA_IDS = "queue_item_ids"

  fun show(context: Context, ids: List<UUID>) {
    if (ids.isEmpty()) return
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
      NotificationChannel(CHANNEL, noteLinkText("分享入队", "Shared items"), NotificationManager.IMPORTANCE_DEFAULT),
    )
    val undo = Intent(context, ShareUndoReceiver::class.java)
      .putStringArrayListExtra(EXTRA_IDS, ArrayList(ids.map(UUID::toString)))
    val pendingUndo = PendingIntent.getBroadcast(
      context,
      NOTIFICATION_ID,
      undo,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_upload_done)
      .setContentTitle(noteLinkText("已加入 NoteLink", "Added to NoteLink"))
      .setContentText(noteLinkText("已将 ${ids.size} 项内容加入发送队列", "Added ${ids.size} items to the send queue"))
      .setAutoCancel(true)
      .addAction(0, noteLinkText("撤销", "Undo"), pendingUndo)
      .build()
    val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    if (canNotify) {
      NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
  }
}

class ShareUndoReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val ids = intent.getStringArrayListExtra(ShareUndoNotifier.EXTRA_IDS).orEmpty()
    val pendingResult = goAsync()
    val appContext = context.applicationContext
    CoroutineScope(Dispatchers.IO).launch {
      try {
        PhoneQueueRepository(appContext).use { queue ->
          ids.mapNotNull { value -> runCatching { UUID.fromString(value) }.getOrNull() }
            .forEach(queue::delete)
          TransferForegroundService.sync(appContext, queue.items().isNotEmpty())
        }
        NotificationManagerCompat.from(appContext).cancel(ShareUndoNotifier.NOTIFICATION_ID)
        withContext(Dispatchers.Main) {
          Toast.makeText(appContext, noteLinkText("已撤销本次分享", "Share undone"), Toast.LENGTH_SHORT).show()
        }
      } finally {
        pendingResult.finish()
      }
    }
  }
}
