package com.betterhv.transfer.core

/**
 * Platform-independent framing for the encrypted NoteLink control channel.
 *
 * GATT characteristic writes/notifications are transport packets, not
 * messages. This codec keeps the message format identical on Android and
 * Windows and caps every packet well below the most restrictive practical
 * Windows BLE payload limit.
 */
data class BleTransportFrame(
    val messageId: Int,
    val index: Int,
    val count: Int,
    val totalLength: Int,
    val checksum: Int,
    val first: Boolean,
    val last: Boolean,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean = other is BleTransportFrame &&
        messageId == other.messageId && index == other.index && count == other.count &&
        totalLength == other.totalLength && checksum == other.checksum &&
        first == other.first && last == other.last && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * messageId + index * 17 + payload.contentHashCode()
}

object BleTransportFrameCodec {
    const val VERSION = 1
    const val HEADER_BYTES = 18
    const val DEFAULT_FRAME_BYTES = 160
    const val MAX_MESSAGE_BYTES = 65_535
    private const val MAGIC_0: Byte = 0x4e
    private const val MAGIC_1: Byte = 0x4c
    private const val FLAG_FIRST = 1
    private const val FLAG_LAST = 2

    fun fragment(message: ByteArray, messageId: Int, maxFrameBytes: Int = DEFAULT_FRAME_BYTES): List<ByteArray> {
        require(message.size <= MAX_MESSAGE_BYTES) { "BLE control message is too large" }
        require(maxFrameBytes >= HEADER_BYTES) { "BLE frame budget is smaller than its header" }
        val payloadBytes = (maxFrameBytes - HEADER_BYTES).coerceAtLeast(1)
        val count = ((message.size + payloadBytes - 1) / payloadBytes).coerceAtLeast(1)
        require(count <= 0xffff) { "BLE control message has too many fragments" }
        val checksum = crc32(message)
        return List(count) { index ->
            val start = (index * payloadBytes).coerceAtMost(message.size)
            val end = (start + payloadBytes).coerceAtMost(message.size)
            encode(BleTransportFrame(
                messageId = messageId,
                index = index,
                count = count,
                totalLength = message.size,
                checksum = checksum,
                first = index == 0,
                last = index == count - 1,
                payload = message.copyOfRange(start, end)
            ))
        }
    }

    fun encode(frame: BleTransportFrame): ByteArray {
        require(frame.index in 0 until frame.count && frame.count in 1..0xffff)
        require(frame.totalLength in 0..MAX_MESSAGE_BYTES)
        require(frame.payload.size <= 0xffff)
        val flags = (if (frame.first) FLAG_FIRST else 0) or (if (frame.last) FLAG_LAST else 0)
        return ByteArray(HEADER_BYTES + frame.payload.size).also { output ->
            output[0] = MAGIC_0; output[1] = MAGIC_1; output[2] = VERSION.toByte(); output[3] = flags.toByte()
            putInt(output, 4, frame.messageId)
            putShort(output, 8, frame.index)
            putShort(output, 10, frame.count)
            putShort(output, 12, frame.totalLength)
            putInt(output, 14, frame.checksum)
            frame.payload.copyInto(output, HEADER_BYTES)
        }
    }

    fun decode(bytes: ByteArray): BleTransportFrame {
        require(bytes.size >= HEADER_BYTES) { "Truncated BLE transport frame" }
        require(bytes[0] == MAGIC_0 && bytes[1] == MAGIC_1) { "Not a NoteLink transport frame" }
        require((bytes[2].toInt() and 0xff) == VERSION) { "Unsupported BLE transport frame version" }
        val flags = bytes[3].toInt() and 0xff
        require(flags and (FLAG_FIRST or FLAG_LAST).inv() == 0) { "Invalid BLE transport frame flags" }
        val frame = BleTransportFrame(
            messageId = readInt(bytes, 4),
            index = readShort(bytes, 8),
            count = readShort(bytes, 10),
            totalLength = readShort(bytes, 12),
            checksum = readInt(bytes, 14),
            first = flags and FLAG_FIRST != 0,
            last = flags and FLAG_LAST != 0,
            payload = bytes.copyOfRange(HEADER_BYTES, bytes.size)
        )
        require(frame.count > 0 && frame.index < frame.count) { "Invalid BLE transport frame sequence" }
        require(frame.first == (frame.index == 0)) { "Invalid BLE transport first flag" }
        require(frame.last == (frame.index == frame.count - 1)) { "Invalid BLE transport last flag" }
        return frame
    }

    fun isFrame(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == MAGIC_0 && bytes[1] == MAGIC_1

    internal fun crc32(bytes: ByteArray): Int {
        var crc = -1
        bytes.forEach { value ->
            crc = crc xor (value.toInt() and 0xff)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor -306674912 else crc ushr 1 }
        }
        return crc.inv()
    }

    private fun putShort(output: ByteArray, offset: Int, value: Int) {
        output[offset] = (value ushr 8).toByte(); output[offset + 1] = value.toByte()
    }

    private fun putInt(output: ByteArray, offset: Int, value: Int) {
        output[offset] = (value ushr 24).toByte(); output[offset + 1] = (value ushr 16).toByte()
        output[offset + 2] = (value ushr 8).toByte(); output[offset + 3] = value.toByte()
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
}

/** Sequential reassembly is intentional: GATT preserves writes per connection. */
class BleTransportReassembler {
    private var messageId: Int? = null
    private var expectedIndex = 0
    private var expectedCount = 0
    private var expectedLength = 0
    private var expectedChecksum = 0
    private var output = ByteArray(0)

    fun add(encodedFrame: ByteArray): ByteArray? {
        val frame = BleTransportFrameCodec.decode(encodedFrame)
        if (frame.first) {
            // A new first frame supersedes an abandoned partial message on the same connection.
            reset()
            require(frame.first) { "BLE transport message did not start with the first frame" }
            messageId = frame.messageId
            expectedCount = frame.count
            expectedLength = frame.totalLength
            expectedChecksum = frame.checksum
            output = ByteArray(0)
        } else {
            require(expectedIndex > 0) { "BLE transport message did not start with the first frame" }
        }
        require(frame.messageId == messageId) { "Interleaved BLE transport messages" }
        require(frame.index == expectedIndex && frame.count == expectedCount) { "Missing BLE transport fragment" }
        require(frame.totalLength == expectedLength && frame.checksum == expectedChecksum) {
            "BLE transport message metadata changed"
        }
        require(output.size + frame.payload.size <= expectedLength) { "BLE transport message exceeded its declared size" }
        output += frame.payload
        expectedIndex++
        if (!frame.last) return null
        require(expectedIndex == expectedCount && output.size == expectedLength) { "Incomplete BLE transport message" }
        require(BleTransportFrameCodec.crc32(output) == expectedChecksum) { "BLE transport checksum mismatch" }
        val complete = output
        reset()
        return complete
    }

    fun reset() {
        messageId = null; expectedIndex = 0; expectedCount = 0; expectedLength = 0
        expectedChecksum = 0; output = ByteArray(0)
    }
}
