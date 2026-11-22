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
import androidx.core.content.edit
import com.betterhv.note.sender.data.ExportInboxRepository
import com.betterhv.note.sender.data.NoteLinkSettings
import com.betterhv.note.sender.data.PhoneQueueRepository
import com.betterhv.note.sender.service.NoteLinkTransferRuntime
import com.betterhv.note.sender.service.PhoneCommandProcessor
import com.betterhv.note.sender.service.PhonePairingMode
import com.betterhv.transfer.android.AndroidNetworkInfoProvider
import com.betterhv.transfer.android.AndroidPairingController
import com.betterhv.transfer.android.AndroidSenderEndpoint
import com.betterhv.transfer.android.ControlPeripheralListener
import com.betterhv.transfer.android.HighBandwidthSessionManager
import com.betterhv.transfer.android.HostedGroupState
import com.betterhv.transfer.core.NOTELINK_LAN_PORT
import com.betterhv.transfer.core.TransferPhase
import com.betterhv.transfer.core.i18n.noteLinkText
import com.betterhv.transfer.core.v4.notelink.NoteLinkServer
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
  private var server: NoteLinkServer? = null
  private var networkWatch: AutoCloseable? = null
  // Android drops incoming broadcast datagrams in Wi-Fi power save unless a multicast lock is
  // held; without it the Note's LAN discovery probe never reaches this phone.
  private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
  @Volatile private var foregroundStarted = false

  override fun onCreate() {
    super.onCreate()
    pairing = AndroidPairingController.shared(this)
    val settings = NoteLinkSettings(this)
    queue = PhoneQueueRepository(
      this,
      sendOrder = { settings.sendOrder },
      deviceName = { id ->
        id?.let { deviceId -> pairing.pairedClients.firstOrNull { it.id == deviceId }?.name }
      },
    )
    inbox = ExportInboxRepository(this)
    // The Wi-Fi Direct group is hosted only on request from a Note that could not reach the LAN.
    highBandwidth = HighBandwidthSessionManager(this, serviceScope)
    processor = PhoneCommandProcessor(this, queue, inbox, pairing, highBandwidth, ::refreshOrStop)
    serviceScope.launch {
      PhonePairingMode.responder.collect { endpoint?.setPairingMode(it != null) }
    }
    networkWatch = AndroidNetworkInfoProvider(this).watch { server?.publishCapabilities() }
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
      if (!foregroundStarted) runCatching { showNotification() }
      stopSelf()
      return START_NOT_STICKY
    }
    if (!shouldRun()) {
      if (!foregroundStarted) runCatching { showNotification() }
      stopSelf()
      return START_NOT_STICKY
    }
    showNotification()
    if (intent?.action == ACTION_SETTINGS_CHANGED) stopTransport()
    if (endpoint == null) startTransport()
    return START_STICKY
  }

  private fun startTransport() {
    multicastLock = runCatching {
      getSystemService(android.net.wifi.WifiManager::class.java)
        ?.createMulticastLock("NoteLinkDiscovery")
        ?.apply {
          setReferenceCounted(false)
          acquire()
        }
    }.getOrNull()
    val created = NoteLinkServer(processor, sendBle = { peer, record -> endpoint?.notifyControl(peer, record) })
    runCatching { created.start() }.onFailure {
      Log.e(TAG, "LAN listener could not bind port $NOTELINK_LAN_PORT; only BLE is available", it)
    }
    processor.server = created
    server = created
    endpoint = AndroidSenderEndpoint(
      this,
      pairing.localDeviceId,
      NoteLinkSettings(this).displayName,
      queue,
      object : ControlPeripheralListener {
        override fun onRecord(peerKey: String, record: ByteArray) = created.onBleRecord(peerKey, record)
        override fun onMtu(peerKey: String, mtu: Int) = created.onBleMtu(peerKey, mtu)
        override fun onDisconnected(peerKey: String) = created.onBleClosed(peerKey)
      },
    ).also { peripheral ->
      peripheral.start()
      peripheral.setPairingMode(PhonePairingMode.responder.value != null)
    }
  }

  private fun stopTransport() {
    runCatching { multicastLock?.release() }
    multicastLock = null
    endpoint?.close()
    endpoint = null
    server?.close()
    server = null
    processor.server = null
  }

  override fun onDestroy() {
    foregroundStarted = false
    networkWatch?.close()
    stopTransport()
    NoteLinkTransferRuntime.detach(processor)
    processor.close()
    highBandwidth.close()
    serviceScope.cancel()
    inbox.close()
    queue.close()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun refreshOrStop() {
    if (!shouldRun()) {
      stopSelf()
    } else {
      showNotification()
      endpoint?.refreshAdvertisement()
      server?.publishQueueChanged()
    }
  }

  @Synchronized
  private fun showNotification() {
    val count = queue.itemFlow.value.size
    val displayName = NoteLinkSettings(this).displayName
    val open = PendingIntent.getActivity(
      this,
      1,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val pause = PendingIntent.getService(
      this,
      2,
      Intent(this, TransferForegroundService::class.java).setAction(ACTION_PAUSE),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val cancel = PendingIntent.getService(
      this,
      3,
      Intent(this, TransferForegroundService::class.java).setAction(ACTION_CANCEL_TRANSFER),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val transfer = processor.snapshot.value
    val group = highBandwidth.state.value
    val phaseText = when (transfer.phase) {
      TransferPhase.IDLE -> null

      TransferPhase.TRANSFERRING -> "${
        transfer.mode?.name ?: noteLinkText(
          "网络",
          "Network",
        )
      } · ${transfer.bytesTransferred}/${transfer.totalBytes} ${
        noteLinkText(
          "字节",
          "bytes",
        )
      } · ${transfer.bytesPerSecond} B/s"

      TransferPhase.FAILED -> transfer.lastFailure?.let {
        "${it.code}: ${it.message}"
      } ?: "Transfer failed"

      else -> transfer.phase.name.replace('_', ' ')
    }
    val groupText = when (group) {
      is HostedGroupState.Ready -> noteLinkText("Wi-Fi Direct 已准备", "Wi-Fi Direct ready")
      is HostedGroupState.Preparing -> noteLinkText(
        "正在准备 Wi-Fi Direct",
        "Preparing Wi-Fi Direct"
      )

      is HostedGroupState.Unavailable -> "Wi-Fi Direct unavailable"
      HostedGroupState.Stopped -> noteLinkText("Wi-Fi Direct 已停止", "Wi-Fi Direct stopped")
    }
    val diagnostic = buildList {
      add(phaseText ?: noteLinkText("等待传输", "Waiting for transfer"))
      add(
        "SSID ${transfer.ssidMatch} · ${
          noteLinkText(
            "本端",
            "local",
          )
        } modes=${transfer.localModes} · ${
          noteLinkText(
            "对端",
            "remote"
          )
        } modes=${transfer.remoteModes}",
      )
      transfer.endpoint?.let { add("${it.host}:${it.port}") }
      add("Wi-Fi Direct $groupText · attempt=${transfer.attempt} · fallback=${transfer.fallbackCount}")
      transfer.etaMillis?.let {
        add(
          "${
            noteLinkText(
              "平均",
              "Average"
            )
          } ${transfer.averageBytesPerSecond} B/s · ETA ${it / 1000}s"
        )
      }
      transfer.lastFailure?.let { add("${it.code} · recoverable=${it.recoverable}") }
    }.joinToString("\n")
    val builder = NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setContentTitle(noteLinkText("$displayName 已准备接收", "$displayName is ready"))
      .setContentText(
        phaseText ?: if (count > 0) {
          noteLinkText(
            "$count 项待发送 · $groupText",
            "$count items pending · $groupText",
          )
        } else {
          noteLinkText("等待 Note 导出 · $groupText", "Waiting for Note exports · $groupText")
        },
      )
      .setStyle(NotificationCompat.BigTextStyle().bigText(diagnostic))
      .setContentIntent(open).setOngoing(true)
      .addAction(0, noteLinkText("暂停等待", "Pause"), pause)
    if (transfer.canCancel) builder.addAction(
      0,
      noteLinkText("取消传输", "Cancel transfer"),
      cancel
    )
    val notification = builder.build()
    if (!foregroundStarted) {
      ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
      )
      foregroundStarted = true
    } else {
      getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }
  }

  private fun createChannel() {
    getSystemService(NotificationManager::class.java).createNotificationChannel(
      NotificationChannel(
        CHANNEL,
        noteLinkText("Note 传输", "Note transfers"),
        NotificationManager.IMPORTANCE_LOW
      ),
    )
  }

  private fun shouldRun(): Boolean = queue.items().isNotEmpty() || PhonePairingMode.responder.value != null ||
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
      val paired = AndroidPairingController.shared(context).pairedDevice != null
      if (hasItems || PhonePairingMode.responder.value != null || (paired && isReceiveEnabled(context))) {
        startForegroundSafely(context, intent)
      } else {
        context.stopService(intent)
      }
    }

    fun setReceiveEnabled(context: Context, enabled: Boolean) {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit { putBoolean(KEY_RECEIVE, enabled) }
    }

    fun settingsChanged(context: Context, hasItems: Boolean) {
      val paired = AndroidPairingController.shared(context).pairedDevice != null
      if (hasItems || (paired && isReceiveEnabled(context))) {
        startForegroundSafely(
          context,
          Intent(context, TransferForegroundService::class.java).setAction(ACTION_SETTINGS_CHANGED),
        )
      }
    }

    private fun startForegroundSafely(context: Context, intent: Intent) {
      try {
        ContextCompat.startForegroundService(context, intent)
      } catch (e: SecurityException) {
        Log.w(TAG, "Missing permission to start transfer service", e)
      } catch (e: IllegalStateException) {
        Log.w(TAG, "Cannot start transfer foreground service in current state", e)
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
