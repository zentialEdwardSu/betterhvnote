package com.betterhv.transfer.android

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.TransferState
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    private val mutableState = MutableStateFlow<TransferState>(TransferState.Idle)
    override val state: StateFlow<TransferState> = mutableState
    private val peripheral = BleSenderPeripheral(
        context, deviceId, deviceName, provider::counts, commandHandler
    )

    override fun start() {
        provider.releaseExpired()
        runCatching { peripheral.start() }
            .onSuccess { mutableState.value = TransferState.Connected(deviceId, deviceName) }
            .onFailure { mutableState.value = TransferState.Error(it.message ?: "BLE advertising failed") }
    }

    override fun stop() {
        peripheral.close()
        mutableState.value = TransferState.Idle
    }

    fun refreshAdvertisement() = peripheral.refreshAdvertisement()
}

class AndroidReceiverEndpoint(
    context: Context,
    private val fetcher: suspend (ContentKind, File) -> ReceivedLease?
) : ReceiverEndpoint {
    private val scanner = BleReceiverScanner(context)
    private val mutableState = MutableStateFlow<TransferState>(TransferState.Idle)
    override val state: StateFlow<TransferState> = mutableState

    override suspend fun discover(timeoutMillis: Long): List<DiscoveredSender> {
        mutableState.value = TransferState.Scanning
        return runCatching { scanner.discover(timeoutMillis) }
            .onSuccess { mutableState.value = TransferState.Idle }
            .onFailure { mutableState.value = TransferState.Error(it.message ?: "BLE scan failed") }
            .getOrThrow()
    }

    override suspend fun requestNext(kind: ContentKind, stagingDirectory: File): ReceivedLease? =
        fetcher(kind, stagingDirectory)

    override fun close() { mutableState.value = TransferState.Idle }
}
