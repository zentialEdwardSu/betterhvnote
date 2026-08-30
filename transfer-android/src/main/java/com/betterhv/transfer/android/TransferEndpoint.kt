package com.betterhv.transfer.android

import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.ContentCounts
import com.betterhv.transfer.core.PairedDevice
import com.betterhv.transfer.core.RemotePayload
import com.betterhv.transfer.core.TransferLease
import com.betterhv.transfer.core.TransferObservable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SenderEndpoint : AutoCloseable, TransferObservable {
    fun start()
    fun stop()
    override fun close() = stop()
}

interface ReceiverEndpoint : AutoCloseable, TransferObservable {
    suspend fun discover(timeoutMillis: Long = 20_000L): List<DiscoveredSender>
    suspend fun requestNext(kind: ContentKind, stagingDirectory: File): ReceivedLease?
    override fun close()
}

data class DiscoveredSender(
    val bluetoothAddress: String,
    val deviceId: String,
    val name: String,
    val imageCount: Int,
    val textCount: Int,
    val pdfCount: Int = 0
) {
    /** Version-1 advertisements carry the first five SHA-256 bytes, not the full device ID. */
    val identityHash: String get() = deviceId
}

interface ReceivedLease : TransferLease {
    val payload: RemotePayload

    /** Network-backed lease operations must never be awaited by an Android UI thread. */
    suspend fun heartbeatAndAwait() = withContext(Dispatchers.IO) { heartbeat() }
    suspend fun commitAndAwait() = withContext(Dispatchers.IO) { commit() }
    suspend fun releaseAndAwait() = withContext(Dispatchers.IO) { release() }
}

interface SenderContentProvider {
    fun counts(): ContentCounts
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
    val pairedClients: List<PairedDevice>
    val pairedDevice: PairedDevice? get() = pairedClients.maxByOrNull(PairedDevice::lastUsedAt)
    fun pairedClient(deviceId: String): PairedDevice? = pairedClients.firstOrNull { it.id == deviceId }
    fun begin(localDeviceName: String): PairingOffer
    fun accept(offer: PairingOffer, remoteDeviceName: String): PairingConfirmation
    fun confirm(confirmation: PairingConfirmation): PairedDevice
    fun unpair(deviceId: String? = null)
    fun rename(deviceId: String, name: String): PairedDevice
    fun markLastUsed(deviceId: String): PairedDevice
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

data class BleIdentity(
    val protocolVersion: Int,
    val imageCount: Int,
    val textCount: Int,
    val pdfCount: Int,
    val deviceId: String,
    val deviceName: String
)
