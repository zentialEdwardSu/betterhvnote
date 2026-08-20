package com.betterhv.transfer.android

import android.content.Context
import com.betterhv.transfer.core.HighBandwidthEndpoint
import com.betterhv.transfer.core.SharedFileTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface HostedGroupState {
    data object Stopped : HostedGroupState
    data class Preparing(val attempt: Int) : HostedGroupState
    data class Ready(val endpoint: HighBandwidthEndpoint) : HostedGroupState
    data class Unavailable(val message: String, val retryInMillis: Long) : HostedGroupState
}

/** Owns one Android Wi-Fi Direct group for the enclosing foreground-service lifetime. */
class HighBandwidthSessionManager(context: Context, private val scope: CoroutineScope) : AutoCloseable {
    private val controller = WifiDirectController(context)
    private val mutableState = MutableStateFlow<HostedGroupState>(HostedGroupState.Stopped)
    val state: StateFlow<HostedGroupState> = mutableState.asStateFlow()
    private var job: Job? = null

    @Synchronized fun start() {
        if (job?.isActive == true) return
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
                        port = SharedFileTransfer.PORT
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
