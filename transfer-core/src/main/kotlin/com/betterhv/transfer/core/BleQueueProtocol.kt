package com.betterhv.transfer.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

sealed interface BleCommand {
    data object Counts : BleCommand
    data class Lease(val kind: ContentKind, val destinationDeviceId: String) : BleCommand
    data class TextChunk(val itemId: UUID, val offset: Int) : BleCommand
    data class Heartbeat(val itemId: UUID) : BleCommand
    data class Commit(val itemId: UUID) : BleCommand
    data class Release(val itemId: UUID) : BleCommand
    data class PrepareFileTransfer(
        val itemId: UUID,
        val selectedMode: TransferMode,
        val receiverEndpoint: NetworkEndpoint,
        val sessionNonce: ByteArray
    ) : BleCommand {
        init { require(sessionNonce.size == TransferNetworkSecurity.SESSION_NONCE_BYTES) }
        override fun equals(other: Any?): Boolean = other is PrepareFileTransfer && itemId == other.itemId &&
            selectedMode == other.selectedMode && receiverEndpoint == other.receiverEndpoint &&
            sessionNonce.contentEquals(other.sessionNonce)
        override fun hashCode(): Int = 31 * itemId.hashCode() + sessionNonce.contentHashCode()
    }
    data class Capabilities(val localCapabilities: DeviceCapabilities) : BleCommand
    data class PushOffer(val offer: ExportTransferOffer) : BleCommand
    data class PushStatus(val artifactId: UUID) : BleCommand
    data class PushCancel(val artifactId: UUID) : BleCommand
}

sealed interface BleResponse {
    data class Counts(val images: Int, val texts: Int, val pdfs: Int = 0) : BleResponse
    data class Offer(val item: QueueItem) : BleResponse
    data object Empty : BleResponse
    data class TextChunk(val itemId: UUID, val offset: Int, val total: Int, val bytes: ByteArray) : BleResponse {
        override fun equals(other: Any?) = other is TextChunk && itemId == other.itemId && offset == other.offset &&
            total == other.total && bytes.contentEquals(other.bytes)
        override fun hashCode() = 31 * itemId.hashCode() + bytes.contentHashCode()
    }
    data object Ok : BleResponse
    data class Error(val message: String) : BleResponse
    data object Pending : BleResponse
    data class Prepared(val itemId: UUID, val mode: TransferMode, val receiverEndpoint: NetworkEndpoint) : BleResponse
    data class Capabilities(val negotiation: CapabilityNegotiation) : BleResponse
    data class PushComplete(val artifactId: UUID) : BleResponse
    data class AlreadyReceived(val artifactId: UUID) : BleResponse
    data class Failure(val failure: TransferFailure) : BleResponse
}

class UnsupportedBleProtocolException(val receivedVersion: Int) : IllegalArgumentException(
    "Unsupported BLE protocol version $receivedVersion; version ${BleQueueProtocol.VERSION} is required"
)

object BleQueueProtocol {
    const val VERSION = 3
    const val TEXT_CHUNK_BYTES = 160
    const val CAPABILITY_EXPORT_PUSH = 1
    private const val MAX_STRING = 512

    fun encode(command: BleCommand): ByteArray = bytes { out ->
        out.writeByte(VERSION)
        when (command) {
            BleCommand.Counts -> out.writeByte(1)
            is BleCommand.Lease -> { out.writeByte(2); out.writeByte(command.kind.ordinal); out.safeUtf(command.destinationDeviceId) }
            is BleCommand.TextChunk -> { out.writeByte(3); out.uuid(command.itemId); out.writeInt(command.offset) }
            is BleCommand.Heartbeat -> { out.writeByte(4); out.uuid(command.itemId) }
            is BleCommand.Commit -> { out.writeByte(5); out.uuid(command.itemId) }
            is BleCommand.Release -> { out.writeByte(6); out.uuid(command.itemId) }
            is BleCommand.PrepareFileTransfer -> {
                out.writeByte(7); out.uuid(command.itemId); out.writeByte(command.selectedMode.ordinal)
                out.endpoint(command.receiverEndpoint); out.write(command.sessionNonce)
            }
            is BleCommand.Capabilities -> { out.writeByte(11); out.capabilities(command.localCapabilities) }
            is BleCommand.PushOffer -> {
                out.writeByte(12); out.uuid(command.offer.artifactId); out.safeUtf(command.offer.displayName)
                out.safeUtf(command.offer.mimeType); out.writeLong(command.offer.byteLength); out.write(command.offer.sha256)
            }
            is BleCommand.PushStatus -> { out.writeByte(13); out.uuid(command.artifactId) }
            is BleCommand.PushCancel -> { out.writeByte(14); out.uuid(command.artifactId) }
        }
    }

    fun decodeCommand(bytes: ByteArray): BleCommand = input(bytes) { value ->
        requireVersion(value.readUnsignedByte())
        when (value.readUnsignedByte()) {
            1 -> BleCommand.Counts
            2 -> BleCommand.Lease(ContentKind.entries[value.readUnsignedByte()], value.safeUtf())
            3 -> BleCommand.TextChunk(value.uuid(), value.readInt())
            4 -> BleCommand.Heartbeat(value.uuid())
            5 -> BleCommand.Commit(value.uuid())
            6 -> BleCommand.Release(value.uuid())
            7 -> BleCommand.PrepareFileTransfer(
                value.uuid(), TransferMode.entries[value.readUnsignedByte()], value.endpoint(),
                ByteArray(TransferNetworkSecurity.SESSION_NONCE_BYTES).also(value::readFully)
            )
            11 -> BleCommand.Capabilities(value.capabilities())
            12 -> BleCommand.PushOffer(ExportTransferOffer(
                value.uuid(), value.safeUtf(), value.safeUtf(), value.readLong(), ByteArray(32).also(value::readFully)
            ))
            13 -> BleCommand.PushStatus(value.uuid())
            14 -> BleCommand.PushCancel(value.uuid())
            else -> error("Unknown BLE v3 command")
        }
    }

    fun encode(response: BleResponse): ByteArray = bytes { out ->
        out.writeByte(VERSION)
        when (response) {
            is BleResponse.Counts -> {
                out.writeByte(1); out.writeShort(response.images); out.writeShort(response.texts)
                out.writeShort(response.pdfs)
            }
            is BleResponse.Offer -> {
                out.writeByte(2); val item = response.item; out.uuid(item.id); out.writeByte(item.kind.ordinal)
                out.safeUtf(item.mimeType); out.writeLong(item.byteLength); out.write(item.sha256); out.safeUtf(item.displayName.orEmpty())
            }
            BleResponse.Empty -> out.writeByte(3)
            is BleResponse.TextChunk -> {
                out.writeByte(4); out.uuid(response.itemId); out.writeInt(response.offset)
                out.writeInt(response.total); out.writeShort(response.bytes.size); out.write(response.bytes)
            }
            BleResponse.Ok -> out.writeByte(5)
            is BleResponse.Error -> { out.writeByte(6); out.safeUtf(response.message.take(MAX_STRING)) }
            BleResponse.Pending -> out.writeByte(7)
            is BleResponse.Prepared -> {
                out.writeByte(8); out.uuid(response.itemId); out.writeByte(response.mode.ordinal); out.endpoint(response.receiverEndpoint)
            }
            is BleResponse.Capabilities -> {
                out.writeByte(9); out.capabilities(response.negotiation.remote); out.writeByte(response.negotiation.ssidMatch.ordinal)
            }
            is BleResponse.PushComplete -> { out.writeByte(10); out.uuid(response.artifactId) }
            is BleResponse.AlreadyReceived -> { out.writeByte(11); out.uuid(response.artifactId) }
            is BleResponse.Failure -> {
                out.writeByte(12); out.writeByte(response.failure.code.ordinal); out.safeUtf(response.failure.message.take(MAX_STRING))
                out.writeBoolean(response.failure.recoverable); out.writeByte(response.failure.causeMode?.ordinal?.plus(1) ?: 0)
            }
        }
    }

    fun decodeResponse(bytes: ByteArray): BleResponse = input(bytes) { value ->
        requireVersion(value.readUnsignedByte())
        when (value.readUnsignedByte()) {
            1 -> BleResponse.Counts(
                value.readUnsignedShort(), value.readUnsignedShort(), value.readUnsignedShort()
            )
            2 -> BleResponse.Offer(QueueItem(
                value.uuid(), null, ContentKind.entries[value.readUnsignedByte()], value.safeUtf(), value.readLong(),
                ByteArray(32).also(value::readFully), 0, 0, QueueState.LEASED, value.safeUtf()
            ))
            3 -> BleResponse.Empty
            4 -> BleResponse.TextChunk(
                value.uuid(), value.readInt(), value.readInt(), ByteArray(value.readUnsignedShort()).also(value::readFully)
            )
            5 -> BleResponse.Ok
            6 -> BleResponse.Error(value.safeUtf())
            7 -> BleResponse.Pending
            8 -> BleResponse.Prepared(value.uuid(), TransferMode.entries[value.readUnsignedByte()], value.endpoint())
            9 -> BleResponse.Capabilities(CapabilityNegotiation(value.capabilities(), SsidMatch.entries[value.readUnsignedByte()]))
            10 -> BleResponse.PushComplete(value.uuid())
            11 -> BleResponse.AlreadyReceived(value.uuid())
            12 -> {
                val code = TransferErrorCode.entries[value.readUnsignedByte()]
                val message = value.safeUtf()
                val recoverable = value.readBoolean()
                val mode = value.readUnsignedByte().let { if (it == 0) null else TransferMode.entries[it - 1] }
                BleResponse.Failure(TransferFailure(code, message, recoverable, mode))
            }
            else -> error("Unknown BLE v3 response")
        }
    }

    private fun requireVersion(version: Int) {
        if (version != VERSION) throw UnsupportedBleProtocolException(version)
    }

    private fun DataOutputStream.capabilities(value: DeviceCapabilities) {
        writeByte(value.protocolVersion)
        writeInt(TransferModes.requireValid(value.modes))
        writeBoolean(value.lanEndpoint != null); value.lanEndpoint?.let { endpoint(it) }
        writeBoolean(value.ssidFingerprint != null); value.ssidFingerprint?.let(::write)
        writeBoolean(value.wifiDirectEndpoint != null)
        value.wifiDirectEndpoint?.let {
            safeUtf(it.deviceAddress); safeUtf(it.ownerIp); safeUtf(it.networkName); safeUtf(it.passphrase); writeShort(it.port)
        }
        writeInt(value.extensions)
    }

    private fun DataInputStream.capabilities(): DeviceCapabilities {
        val version = readUnsignedByte()
        val modes = TransferModes.requireValid(readInt())
        val lan = if (readBoolean()) endpoint() else null
        val fingerprint = if (readBoolean()) ByteArray(DeviceCapabilities.SSID_FINGERPRINT_BYTES).also(::readFully) else null
        val wifi = if (readBoolean()) HighBandwidthEndpoint(
            safeUtf(), safeUtf(), safeUtf(), safeUtf(), readUnsignedShort()
        ) else null
        return DeviceCapabilities(version, modes, lan, fingerprint, wifi, readInt())
    }

    private fun DataOutputStream.endpoint(value: NetworkEndpoint) {
        val safe = value.validated()
        safeUtf(safe.host); writeShort(safe.port)
    }

    private fun DataInputStream.endpoint(): NetworkEndpoint = NetworkEndpoint(safeUtf(), readUnsignedShort()).validated()
    private fun bytes(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().let { output ->
        DataOutputStream(output).use(block); output.toByteArray()
    }
    private fun <T> input(bytes: ByteArray, block: (DataInputStream) -> T): T =
        DataInputStream(ByteArrayInputStream(bytes)).use(block)
    private fun DataOutputStream.uuid(value: UUID) { writeLong(value.mostSignificantBits); writeLong(value.leastSignificantBits) }
    private fun DataInputStream.uuid() = UUID(readLong(), readLong())
    private fun DataOutputStream.safeUtf(value: String) { require(value.encodeToByteArray().size <= MAX_STRING); writeUTF(value) }
    private fun DataInputStream.safeUtf() = readUTF().also { require(it.encodeToByteArray().size <= MAX_STRING) }
}
