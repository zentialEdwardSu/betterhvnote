package com.betterhv.transfer.android

import android.content.Context
import com.betterhv.transfer.core.TransferErrorCode
import com.betterhv.transfer.core.TransferEvent
import com.betterhv.transfer.core.TransferFailure
import com.betterhv.transfer.core.TransferPhase
import com.betterhv.transfer.core.TransferSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android endpoint shells keep lifecycle and radio code out of app UI. The wire command
 * handler is injected so the phone can transact its persistent outbox atomically.
 */
class AndroidSenderEndpoint(
  context: Context,
  private val deviceId: String,
  private val deviceName: String,
  private val provider: SenderContentProvider,
  listener: ControlPeripheralListener,
) : SenderEndpoint {
  private val mutableSnapshot = MutableStateFlow(TransferSnapshot())
  private val mutableEvents = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 16)
  override val snapshot: StateFlow<TransferSnapshot> = mutableSnapshot.asStateFlow()
  override val events: SharedFlow<TransferEvent> = mutableEvents.asSharedFlow()
  private val peripheral = BleSenderPeripheral(
    context,
    deviceId,
    deviceName,
    provider::counts,
    listener,
  )

  /** Sends one control record to [peerKey]; suspends until the notification was delivered. */
  suspend fun notifyControl(peerKey: String, record: ByteArray) = peripheral.notify(peerKey, record)

  fun setPairingMode(enabled: Boolean) = peripheral.setPairingMode(enabled)

  override fun start() {
    provider.releaseExpired()
    runCatching { peripheral.start() }
      .onSuccess { mutableSnapshot.value = TransferSnapshot(deviceId = deviceId, phase = TransferPhase.IDLE) }
      .onFailure { fail(it) }
  }

  override fun stop() {
    peripheral.close()
    mutableSnapshot.value = TransferSnapshot()
  }

  fun refreshAdvertisement() = peripheral.refreshAdvertisement()
  override fun cancel() = stop()

  private fun fail(error: Throwable) {
    val failure = TransferFailure(TransferErrorCode.INTERNAL, error.message ?: "BLE advertising failed", true)
    mutableSnapshot.value = TransferSnapshot(
      deviceId = deviceId,
      phase = TransferPhase.FAILED,
      lastFailure = failure,
      canRetry = true,
    )
    mutableEvents.tryEmit(TransferEvent.Failed(null, failure))
  }
}
