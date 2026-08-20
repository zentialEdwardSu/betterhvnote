package com.betterhv.note.sender

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.betterhv.transfer.android.AndroidPairingController
import com.betterhv.transfer.android.AndroidSenderEndpoint
import com.betterhv.transfer.android.HighBandwidthSessionManager
import com.betterhv.transfer.android.HostedGroupState
import com.betterhv.transfer.core.TransferPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TransferForegroundService : Service() {
    private lateinit var queue: PhoneQueueRepository
    private lateinit var pairing: AndroidPairingController
    private lateinit var inbox: ExportInboxRepository
    private lateinit var processor: PhoneCommandProcessor
    private lateinit var highBandwidth: HighBandwidthSessionManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var endpoint: AndroidSenderEndpoint? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        queue = PhoneQueueRepository(this)
        pairing = AndroidPairingController(this)
        inbox = ExportInboxRepository(this)
        highBandwidth = HighBandwidthSessionManager(this, serviceScope).also { it.start() }
        processor = PhoneCommandProcessor(this, queue, inbox, pairing, highBandwidth, ::refreshOrStop)
        NoteLinkTransferRuntime.attach(serviceScope, processor)
        serviceScope.launch {
            processor.snapshot.collect { if (foregroundStarted) showNotification() }
        }
        serviceScope.launch {
            highBandwidth.state.collect {
                Log.i(TAG, "wifiDirectGroup=${it.toDiagnosticString()}")
                if (foregroundStarted) showNotification()
            }
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_TRANSFER) {
            processor.cancel()
            showNotification()
            return START_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            setReceiveEnabled(this, false)
            stopSelf(); return START_NOT_STICKY
        }
        if (!shouldRun()) {
            stopSelf(); return START_NOT_STICKY
        }
        showNotification()
        foregroundStarted = true
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
        foregroundStarted = false
        endpoint?.close(); endpoint = null
        NoteLinkTransferRuntime.detach(processor)
        processor.close(); highBandwidth.close(); serviceScope.cancel(); inbox.close(); queue.close()
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
        val cancel = PendingIntent.getService(
            this, 3, Intent(this, TransferForegroundService::class.java).setAction(ACTION_CANCEL_TRANSFER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val transfer = processor.snapshot.value
        val group = highBandwidth.state.value
        val phaseText = when (transfer.phase) {
            TransferPhase.IDLE -> null
            TransferPhase.TRANSFERRING -> "${transfer.mode?.name ?: "网络"} · ${transfer.bytesTransferred}/${transfer.totalBytes} 字节 · ${transfer.bytesPerSecond} B/s"
            TransferPhase.FAILED -> transfer.lastFailure?.let { "${it.code}: ${it.message}" } ?: "传输失败"
            else -> transfer.phase.name.replace('_', ' ')
        }
        val groupText = when (group) {
            is HostedGroupState.Ready -> "Wi-Fi Direct 已准备"
            is HostedGroupState.Preparing -> "正在准备 Wi-Fi Direct"
            is HostedGroupState.Unavailable -> "Wi-Fi Direct 暂不可用"
            HostedGroupState.Stopped -> "Wi-Fi Direct 已停止"
        }
        val diagnostic = buildList {
            add(phaseText ?: "等待传输")
            add("SSID ${transfer.ssidMatch} · 本端 modes=${transfer.localModes} · 对端 modes=${transfer.remoteModes}")
            transfer.endpoint?.let { add("${it.host}:${it.port}") }
            add("Wi-Fi Direct $groupText · attempt=${transfer.attempt} · fallback=${transfer.fallbackCount}")
            transfer.etaMillis?.let { add("平均 ${transfer.averageBytesPerSecond} B/s · ETA ${it / 1000}s") }
            transfer.lastFailure?.let { add("${it.code} · recoverable=${it.recoverable}") }
        }.joinToString("\n")
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("$displayName 已准备接收")
            .setContentText(phaseText ?: if (count > 0) "$count 项待发送 · $groupText" else "等待 Note 导出 · $groupText")
            .setStyle(NotificationCompat.BigTextStyle().bigText(diagnostic))
            .setContentIntent(open).setOngoing(true)
            .addAction(0, "暂停等待", pause)
        if (transfer.canCancel) builder.addAction(0, "取消传输", cancel)
        val notification = builder.build()
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
        private const val TAG = "NoteLinkTransfer"
        private const val NOTIFICATION_ID = 201
        private const val ACTION_PAUSE = "com.betterhv.note.sender.PAUSE"
        private const val ACTION_CANCEL_TRANSFER = "com.betterhv.note.sender.CANCEL_TRANSFER"
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

private fun HostedGroupState.toDiagnosticString(): String = when (this) {
    is HostedGroupState.Ready -> "ready owner=${endpoint.ownerIp}:${endpoint.port}"
    is HostedGroupState.Preparing -> "preparing attempt=$attempt"
    is HostedGroupState.Unavailable -> "unavailable retryMs=$retryInMillis message=$message"
    HostedGroupState.Stopped -> "stopped"
}
