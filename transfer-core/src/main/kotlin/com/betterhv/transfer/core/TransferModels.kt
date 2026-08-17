package com.betterhv.transfer.core

import java.io.File
import java.util.UUID

enum class ContentKind { IMAGE, TEXT }

data class ExportTransferOffer(
    val artifactId: UUID,
    val displayName: String,
    val mimeType: String,
    val byteLength: Long,
    val sha256: ByteArray
) {
    init {
        require(displayName.isNotBlank())
        require(displayName.encodeToByteArray().size <= TransferLimits.MAX_EXPORT_NAME_BYTES)
        require(mimeType == "application/pdf" || mimeType == "image/png")
        require(byteLength in 0..TransferLimits.MAX_EXPORT_BYTES)
        require(sha256.size == QueueItem.SHA256_BYTES)
    }

    override fun equals(other: Any?): Boolean = other is ExportTransferOffer &&
        artifactId == other.artifactId && displayName == other.displayName && mimeType == other.mimeType &&
        byteLength == other.byteLength && sha256.contentEquals(other.sha256)

    override fun hashCode(): Int = 31 * artifactId.hashCode() + sha256.contentHashCode()
}

enum class QueueState { PENDING, LEASED, TRANSFERRING, AWAITING_COMMIT, FAILED }

data class QueueItem(
    val id: UUID,
    val destinationDeviceId: String?,
    val kind: ContentKind,
    val mimeType: String,
    val byteLength: Long,
    val sha256: ByteArray,
    val createdAt: Long,
    val position: Long,
    val state: QueueState = QueueState.PENDING,
    val displayName: String? = null,
    val leaseExpiresAt: Long? = null,
    val failureReason: String? = null
) {
    init {
        require(byteLength >= 0) { "byteLength must be non-negative" }
        require(sha256.size == SHA256_BYTES) { "sha256 must contain 32 bytes" }
    }

    override fun equals(other: Any?): Boolean = other is QueueItem &&
        id == other.id && destinationDeviceId == other.destinationDeviceId &&
        kind == other.kind && mimeType == other.mimeType && byteLength == other.byteLength &&
        sha256.contentEquals(other.sha256) && createdAt == other.createdAt &&
        position == other.position && state == other.state && displayName == other.displayName &&
        leaseExpiresAt == other.leaseExpiresAt && failureReason == other.failureReason

    override fun hashCode(): Int = 31 * id.hashCode() + sha256.contentHashCode()

    companion object { const val SHA256_BYTES = 32 }
}

sealed interface RemotePayload {
    val item: QueueItem

    data class Image(override val item: QueueItem, val stagedFile: File) : RemotePayload
    data class Text(override val item: QueueItem, val text: String) : RemotePayload
}

sealed interface TransferState {
    data object Idle : TransferState
    data object Scanning : TransferState
    data class Pairing(val deviceName: String, val verificationCode: String) : TransferState
    data class Connected(val deviceId: String, val deviceName: String) : TransferState
    data class Transferring(val itemId: UUID, val bytesTransferred: Long, val totalBytes: Long) : TransferState
    data class AwaitingPlacement(val itemId: UUID) : TransferState
    data class Error(val message: String, val recoverable: Boolean = true) : TransferState
}

data class PairedDevice(val id: String, val name: String, val pairedAt: Long)

data class TransferOffer(val item: QueueItem, val inlinePayload: ByteArray? = null)

object TransferLimits {
    const val MAX_IMAGE_BYTES: Long = 64L * 1024L * 1024L
    const val MAX_IMAGE_PIXELS: Long = 100_000_000L
    const val MAX_TEXT_BYTES: Int = 256 * 1024
    const val INLINE_TEXT_BYTES: Int = 32 * 1024
    const val HEARTBEAT_MILLIS: Long = 15_000L
    const val LEASE_TIMEOUT_MILLIS: Long = 60_000L
    const val MAX_EXPORT_BYTES: Long = 512L * 1024L * 1024L
    const val MAX_EXPORT_NAME_BYTES: Int = 96
}
