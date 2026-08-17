package com.betterhv.transfer.android

import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.PairedDevice
import com.betterhv.transfer.core.QueueItem
import com.betterhv.transfer.core.RemotePayload
import com.betterhv.transfer.core.TransferLease
import com.betterhv.transfer.core.TransferState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

interface SenderEndpoint : AutoCloseable {
    val state: StateFlow<TransferState>
    fun start()
    fun stop()
    override fun close() = stop()
}

interface ReceiverEndpoint : AutoCloseable {
    val state: StateFlow<TransferState>
    suspend fun discover(timeoutMillis: Long = 20_000L): List<DiscoveredSender>
    suspend fun requestNext(kind: ContentKind, stagingDirectory: File): ReceivedLease?
    override fun close()
}

data class DiscoveredSender(
    val bluetoothAddress: String,
    val deviceId: String,
    val name: String,
    val imageCount: Int,
    val textCount: Int
)

interface ReceivedLease : TransferLease {
    val payload: RemotePayload
}

interface SenderContentProvider {
    fun counts(): Pair<Int, Int>
    fun leaseNext(kind: ContentKind, destinationDeviceId: String): SenderLease?
    fun releaseExpired(now: Long = System.currentTimeMillis()): Int
}

interface SenderLease : TransferLease {
    val payloadFile: File?
    val text: String?
}

data class TransferReceiptKey(val sourceDeviceId: String, val itemId: UUID)

interface ReceiptStore {
    fun find(key: TransferReceiptKey): UUID?
    fun record(key: TransferReceiptKey, objectId: UUID)
}

interface PairingController {
    val localDeviceId: String
    val pairedDevice: PairedDevice?
    fun begin(localDeviceName: String): PairingOffer
    fun accept(offer: PairingOffer, remoteDeviceName: String): PairingConfirmation
    fun confirm(confirmation: PairingConfirmation): PairedDevice
    fun unpair()
    fun sharedKey(deviceId: String): ByteArray?
}

data class PairingOffer(val deviceId: String, val deviceName: String, val publicKey: ByteArray, val nonce: ByteArray)

data class PairingConfirmation(
    val localDeviceId: String,
    val remoteDeviceId: String,
    val remoteDeviceName: String,
    val verificationCode: String,
    internal val sharedKey: ByteArray
)
