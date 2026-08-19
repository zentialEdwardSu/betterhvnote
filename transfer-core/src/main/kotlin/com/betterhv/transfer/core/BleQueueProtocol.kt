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
    data class WifiSend(val itemId: UUID, val ownerDeviceAddress: String, val ownerDeviceName: String, val ownerIp: String) : BleCommand
    data class WifiHost(val itemId: UUID) : BleCommand
    data class WifiHostStatus(val itemId: UUID) : BleCommand
    data class WifiSendTo(val itemId: UUID, val receiverIp: String) : BleCommand
    data object Capabilities : BleCommand
    data class PushOffer(val offer: ExportTransferOffer) : BleCommand
    data class PushStatus(val artifactId: UUID) : BleCommand
    data class PushCancel(val artifactId: UUID) : BleCommand
}

sealed interface BleResponse {
    data class Counts(val images: Int, val texts: Int) : BleResponse
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
    data class WifiOwnerInfo(
        val deviceAddress: String, val deviceName: String, val ownerIp: String,
        val networkName: String, val passphrase: String
    ) : BleResponse
    data class Capabilities(val flags: Int) : BleResponse
    data class PushComplete(val artifactId: UUID) : BleResponse
    data class AlreadyReceived(val artifactId: UUID) : BleResponse
}

object BleQueueProtocol {
    const val VERSION = 1
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
            is BleCommand.WifiSend -> {
                out.writeByte(7); out.uuid(command.itemId); out.safeUtf(command.ownerDeviceAddress)
                out.safeUtf(command.ownerDeviceName); out.safeUtf(command.ownerIp)
            }
            is BleCommand.WifiHost -> { out.writeByte(8); out.uuid(command.itemId) }
            is BleCommand.WifiHostStatus -> { out.writeByte(9); out.uuid(command.itemId) }
            is BleCommand.WifiSendTo -> { out.writeByte(10); out.uuid(command.itemId); out.safeUtf(command.receiverIp) }
            BleCommand.Capabilities -> out.writeByte(11)
            is BleCommand.PushOffer -> {
                out.writeByte(12); out.uuid(command.offer.artifactId); out.safeUtf(command.offer.displayName)
                out.safeUtf(command.offer.mimeType); out.writeLong(command.offer.byteLength); out.write(command.offer.sha256)
            }
            is BleCommand.PushStatus -> { out.writeByte(13); out.uuid(command.artifactId) }
            is BleCommand.PushCancel -> { out.writeByte(14); out.uuid(command.artifactId) }
        }
    }

    fun decodeCommand(bytes: ByteArray): BleCommand = input(bytes) { value ->
        require(value.readUnsignedByte() == VERSION) { "Unsupported BLE command version" }
        when (value.readUnsignedByte()) {
            1 -> BleCommand.Counts
            2 -> BleCommand.Lease(ContentKind.entries[value.readUnsignedByte()], value.safeUtf())
            3 -> BleCommand.TextChunk(value.uuid(), value.readInt())
            4 -> BleCommand.Heartbeat(value.uuid())
            5 -> BleCommand.Commit(value.uuid())
            6 -> BleCommand.Release(value.uuid())
            7 -> BleCommand.WifiSend(value.uuid(), value.safeUtf(), value.safeUtf(), value.safeUtf())
            8 -> BleCommand.WifiHost(value.uuid())
            9 -> BleCommand.WifiHostStatus(value.uuid())
            10 -> BleCommand.WifiSendTo(value.uuid(), value.safeUtf())
            11 -> BleCommand.Capabilities
            12 -> BleCommand.PushOffer(ExportTransferOffer(
                value.uuid(), value.safeUtf(), value.safeUtf(), value.readLong(), ByteArray(32).also(value::readFully)
            ))
            13 -> BleCommand.PushStatus(value.uuid())
            14 -> BleCommand.PushCancel(value.uuid())
            else -> error("Unknown BLE command")
        }
    }

    fun encode(response: BleResponse): ByteArray = bytes { out ->
        out.writeByte(VERSION)
        when (response) {
            is BleResponse.Counts -> { out.writeByte(1); out.writeShort(response.images); out.writeShort(response.texts) }
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
            is BleResponse.WifiOwnerInfo -> {
                out.writeByte(8); out.safeUtf(response.deviceAddress); out.safeUtf(response.deviceName)
                out.safeUtf(response.ownerIp); out.safeUtf(response.networkName); out.safeUtf(response.passphrase)
            }
            is BleResponse.Capabilities -> { out.writeByte(9); out.writeInt(response.flags) }
            is BleResponse.PushComplete -> { out.writeByte(10); out.uuid(response.artifactId) }
            is BleResponse.AlreadyReceived -> { out.writeByte(11); out.uuid(response.artifactId) }
        }
    }

    fun decodeResponse(bytes: ByteArray): BleResponse = input(bytes) { value ->
        require(value.readUnsignedByte() == VERSION) { "Unsupported BLE response version" }
        when (value.readUnsignedByte()) {
            1 -> BleResponse.Counts(value.readUnsignedShort(), value.readUnsignedShort())
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
            8 -> BleResponse.WifiOwnerInfo(value.safeUtf(), value.safeUtf(), value.safeUtf(), value.safeUtf(), value.safeUtf())
            9 -> BleResponse.Capabilities(value.readInt())
            10 -> BleResponse.PushComplete(value.uuid())
            11 -> BleResponse.AlreadyReceived(value.uuid())
            else -> error("Unknown BLE response")
        }
    }

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
