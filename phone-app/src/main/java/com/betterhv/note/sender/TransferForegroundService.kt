package com.betterhv.note.sender

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.betterhv.transfer.android.AndroidPairingController
import com.betterhv.transfer.android.AndroidSenderEndpoint

class TransferForegroundService : Service() {
    private lateinit var queue: PhoneQueueRepository
    private lateinit var pairing: AndroidPairingController
    private lateinit var inbox: ExportInboxRepository
    private lateinit var processor: PhoneCommandProcessor
    private var endpoint: AndroidSenderEndpoint? = null

    override fun onCreate() {
        super.onCreate()
        queue = PhoneQueueRepository(this)
        pairing = AndroidPairingController(this)
        inbox = ExportInboxRepository(this)
        processor = PhoneCommandProcessor(this, queue, inbox, pairing, ::refreshOrStop)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            setReceiveEnabled(this, false)
            stopSelf(); return START_NOT_STICKY
        }
        if (!shouldRun()) {
            stopSelf(); return START_NOT_STICKY
        }
        showNotification()
        if (intent?.action == ACTION_SETTINGS_CHANGED) {
            endpoint?.close()
            endpoint = null
        }
        if (endpoint == null) {
            endpoint = AndroidSenderEndpoint(
                this, pairing.localDeviceId, NoteLinkSettings(this).displayName,
                queue, { _, bytes -> processor.handle(bytes) }
            ).also(AndroidSenderEndpoint::start)
        } else endpoint?.refreshAdvertisement()
        return START_STICKY
    }

    override fun onDestroy() {
        endpoint?.close(); endpoint = null
        processor.close(); inbox.close(); queue.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refreshOrStop() {
        if (!shouldRun()) stopSelf() else {
            showNotification()
            endpoint?.refreshAdvertisement()
        }
    }

    private fun showNotification() {
        val count = queue.items().size
        val displayName = NoteLinkSettings(this).displayName
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pause = PendingIntent.getService(
            this, 2, Intent(this, TransferForegroundService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("$displayName 已准备接收")
            .setContentText(if (count > 0) "$count 项待发送，同时等待 Note 导出" else "等待来自 Note 的导出")
            .setContentIntent(open).setOngoing(true)
            .addAction(0, "暂停等待", pause).build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Note 传输", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun shouldRun(): Boolean = queue.items().isNotEmpty() ||
        (pairing.pairedDevice != null && isReceiveEnabled(this))

    companion object {
        private const val CHANNEL = "betterhv-transfer"
        private const val NOTIFICATION_ID = 201
        private const val ACTION_PAUSE = "com.betterhv.note.sender.PAUSE"
        private const val ACTION_SETTINGS_CHANGED = "com.betterhv.note.sender.SETTINGS_CHANGED"

        fun sync(context: Context, hasItems: Boolean) {
            val intent = Intent(context, TransferForegroundService::class.java)
            val paired = AndroidPairingController(context).pairedDevice != null
            if (hasItems || (paired && isReceiveEnabled(context))) ContextCompat.startForegroundService(context, intent)
            else context.stopService(intent)
        }

        fun setReceiveEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RECEIVE, enabled).apply()
        }

        fun settingsChanged(context: Context, hasItems: Boolean) {
            val paired = AndroidPairingController(context).pairedDevice != null
            if (hasItems || (paired && isReceiveEnabled(context))) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TransferForegroundService::class.java).setAction(ACTION_SETTINGS_CHANGED)
                )
            }
        }

        fun isReceiveEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RECEIVE, true)

        private const val PREFS = "notelink_receive"
        private const val KEY_RECEIVE = "enabled"
    }
}
