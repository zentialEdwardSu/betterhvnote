package com.betterhv.transfer.android

import android.content.Context
import com.betterhv.transfer.core.HighBandwidthEndpoint
import com.betterhv.transfer.core.NOTELINK_LAN_PORT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface HostedGroupState {
  data object Stopped : HostedGroupState
  data class Preparing(val attempt: Int) : HostedGroupState
  data class Ready(val endpoint: HighBandwidthEndpoint) : HostedGroupState
  data class Unavailable(val message: String, val retryInMillis: Long) : HostedGroupState
}

internal interface HostedGroupDriver : AutoCloseable {
  var pairingKey: ByteArray?
  val session: StateFlow<WifiDirectSession?>
  suspend fun ensureHostedGroup(): WifiDirectSession
  fun requestCloseGroup()
}

/** Owns one Android Wi-Fi Direct group for the enclosing foreground-service lifetime. */
class HighBandwidthSessionManager internal constructor(
  private val scope: CoroutineScope,
  private val controller: HostedGroupDriver,
) : AutoCloseable {
  constructor(
    context: Context,
    scope: CoroutineScope,
    pairingKey: ByteArray? = null,
  ) : this(scope, WifiDirectController(context, pairingKey))

  private val mutableState = MutableStateFlow<HostedGroupState>(HostedGroupState.Stopped)
  val state: StateFlow<HostedGroupState> = mutableState.asStateFlow()
  private var job: Job? = null

  /** Starts hosting for [pairingKey]'s peer; a running group for another key is rebuilt. */
  @Synchronized fun start(pairingKey: ByteArray?) {
    if (job?.isActive == true && controller.pairingKey.contentEqualsNullable(pairingKey)) return
    job?.cancel()
    if (!controller.pairingKey.contentEqualsNullable(pairingKey)) controller.requestCloseGroup()
    controller.pairingKey = pairingKey
    mutableState.value = HostedGroupState.Preparing(1)
    start()
  }

  /** Waits until the group is ready; null on timeout. */
  suspend fun awaitReady(timeoutMillis: Long): HighBandwidthEndpoint? =
    kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
      state.first { it is HostedGroupState.Ready }.let { (it as HostedGroupState.Ready).endpoint }
    }

  @Synchronized fun start() {
    if (job?.isActive == true) return
    mutableState.value = HostedGroupState.Preparing(1)
    job = scope.launch {
      var attempt = 0
      while (isActive) {
        mutableState.value = HostedGroupState.Preparing(attempt + 1)
        try {
          val session = controller.ensureHostedGroup()
          val endpoint = HighBandwidthEndpoint(
            deviceAddress = requireNotNull(session.localDeviceAddress) { "Wi-Fi Direct device address unavailable" },
            ownerIp = session.groupOwnerAddress,
            networkName = requireNotNull(session.networkName) { "Wi-Fi Direct network name unavailable" },
            passphrase = requireNotNull(session.passphrase) { "Wi-Fi Direct passphrase unavailable" },
            port = NOTELINK_LAN_PORT,
          )
          mutableState.value = HostedGroupState.Ready(endpoint)
          while (isActive && controller.session.value != null) delay(1_000L)
          if (isActive) mutableState.value = HostedGroupState.Unavailable("Wi-Fi Direct group disconnected", 1_000L)
          attempt = 0
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Throwable) {
          val backoff = BACKOFF_MILLIS[attempt.coerceAtMost(BACKOFF_MILLIS.lastIndex)]
          mutableState.value = HostedGroupState.Unavailable(error.message ?: "Wi-Fi Direct unavailable", backoff)
          delay(backoff)
          attempt++
        }
      }
    }
  }

  fun endpoint(): HighBandwidthEndpoint? = (state.value as? HostedGroupState.Ready)?.endpoint

  /** Called by the service when platform callbacks report that its hosted group disappeared. */
  @Synchronized fun rebuild() {
    job?.cancel()
    job = null
    start()
  }

  /** Stops hosting; the next [start] creates a fresh group. */
  @Synchronized fun stop() {
    job?.cancel()
    job = null
    controller.requestCloseGroup()
    mutableState.value = HostedGroupState.Stopped
  }

  @Synchronized override fun close() {
    job?.cancel()
    job = null
    controller.requestCloseGroup()
    controller.close()
    mutableState.value = HostedGroupState.Stopped
  }

  private companion object {
    val BACKOFF_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 30_000L)
  }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
  this == null -> other == null
  other == null -> false
  else -> contentEquals(other)
}
