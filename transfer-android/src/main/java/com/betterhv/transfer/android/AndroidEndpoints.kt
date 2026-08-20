package com.betterhv.transfer.android

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.TransferErrorCode
import com.betterhv.transfer.core.TransferEvent
import com.betterhv.transfer.core.TransferFailure
import com.betterhv.transfer.core.TransferPhase
import com.betterhv.transfer.core.TransferSnapshot
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    commandHandler: (BluetoothDevice, ByteArray) -> ByteArray
) : SenderEndpoint {
    private val mutableSnapshot = MutableStateFlow(TransferSnapshot())
    private val mutableEvents = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 16)
    override val snapshot: StateFlow<TransferSnapshot> = mutableSnapshot.asStateFlow()
    override val events: SharedFlow<TransferEvent> = mutableEvents.asSharedFlow()
    private val peripheral = BleSenderPeripheral(
        context, deviceId, deviceName, provider::counts, commandHandler
    )

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
        mutableSnapshot.value = TransferSnapshot(deviceId = deviceId, phase = TransferPhase.FAILED, lastFailure = failure, canRetry = true)
        mutableEvents.tryEmit(TransferEvent.Failed(null, failure))
    }
}

class AndroidReceiverEndpoint(
    context: Context,
    private val fetcher: suspend (ContentKind, File) -> ReceivedLease?
) : ReceiverEndpoint {
    private val scanner = BleReceiverScanner(context)
    private val mutableSnapshot = MutableStateFlow(TransferSnapshot())
    private val mutableEvents = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 16)
    override val snapshot: StateFlow<TransferSnapshot> = mutableSnapshot.asStateFlow()
    override val events: SharedFlow<TransferEvent> = mutableEvents.asSharedFlow()

    override suspend fun discover(timeoutMillis: Long): List<DiscoveredSender> {
        mutableSnapshot.value = TransferSnapshot(phase = TransferPhase.DISCOVERING, canCancel = true)
        return runCatching { scanner.discover(timeoutMillis) }
            .onSuccess { mutableSnapshot.value = TransferSnapshot() }
            .onFailure {
                val failure = TransferFailure(TransferErrorCode.INTERNAL, it.message ?: "BLE scan failed", true)
                mutableSnapshot.value = TransferSnapshot(phase = TransferPhase.FAILED, lastFailure = failure, canRetry = true)
                mutableEvents.tryEmit(TransferEvent.Failed(null, failure))
            }
            .getOrThrow()
    }

    override suspend fun requestNext(kind: ContentKind, stagingDirectory: File): ReceivedLease? =
        fetcher(kind, stagingDirectory)

    override fun cancel() { mutableSnapshot.value = TransferSnapshot() }
    override fun close() = cancel()
}
