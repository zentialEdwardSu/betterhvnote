package com.betterhv.transfer.android

import com.betterhv.transfer.core.TransferCrypto
import java.nio.ByteBuffer
import java.util.LinkedHashMap

class BleReplayCache(private val capacity: Int = 256) {
    private val values = object : LinkedHashMap<String, Unit>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > capacity
    }
    @Synchronized fun accept(nonce: ByteArray) {
        val key = nonce.joinToString("") { "%02x".format(it) }
        require(values.put(key, Unit) == null) { "Replayed BLE control envelope" }
    }
}

/** Stateless per-command protection; the timestamp bounds replay across process restarts. */
object BleSecureEnvelope {
    private const val VERSION_1: Byte = 1
    private const val VERSION_2: Byte = 2
    private const val V1_HEADER_SIZE = 1 + 8 + 12
    private const val AUTH_TAG_SIZE = 16
    private const val UNSIGNED_SHORT_MASK = 0xffff
    private const val MAX_DEVICE_ID_BYTES = 512
    private const val ALLOWED_CLOCK_SKEW = 120_000L

    fun seal(key: ByteArray, plaintext: ByteArray, now: Long = System.currentTimeMillis()): ByteArray {
        val nonce = TransferCrypto.randomBytes(12)
        val header = ByteBuffer.allocate(V1_HEADER_SIZE).put(VERSION_1).putLong(now).put(nonce).array()
        return header + TransferCrypto.encrypt(key, nonce, plaintext, header)
    }

    fun seal(
        key: ByteArray,
        senderDeviceId: String,
        plaintext: ByteArray,
        now: Long = System.currentTimeMillis()
    ): ByteArray {
        val sender = senderDeviceId.encodeToByteArray()
        require(sender.isNotEmpty() && sender.size <= MAX_DEVICE_ID_BYTES) { "Invalid sender device ID" }
        val nonce = TransferCrypto.randomBytes(12)
        val header = ByteBuffer.allocate(V1_HEADER_SIZE + 2 + sender.size)
            .put(VERSION_2).putLong(now).put(nonce).putShort(sender.size.toShort()).put(sender).array()
        return header + TransferCrypto.encrypt(key, nonce, plaintext, header)
    }

    fun senderDeviceId(envelope: ByteArray): String? {
        if (envelope.size < V1_HEADER_SIZE + AUTH_TAG_SIZE || envelope[0] != VERSION_2) return null
        val input = ByteBuffer.wrap(envelope)
        input.position(V1_HEADER_SIZE)
        val size = input.short.toInt() and UNSIGNED_SHORT_MASK
        require(size in 1..MAX_DEVICE_ID_BYTES && envelope.size >= V1_HEADER_SIZE + 2 + size + AUTH_TAG_SIZE) {
            "Invalid sender device ID"
        }
        return ByteArray(size).also(input::get).decodeToString()
    }

    fun open(
        key: ByteArray,
        envelope: ByteArray,
        replayCache: BleReplayCache,
        now: Long = System.currentTimeMillis()
    ): ByteArray {
        require(envelope.size >= V1_HEADER_SIZE + AUTH_TAG_SIZE) { "Truncated BLE control envelope" }
        val version = envelope[0]
        val headerSize = when (version) {
            VERSION_1 -> V1_HEADER_SIZE
            VERSION_2 -> V1_HEADER_SIZE + 2 + requireNotNull(senderDeviceId(envelope)).encodeToByteArray().size
            else -> error("Unsupported BLE envelope version")
        }
        val header = envelope.copyOfRange(0, headerSize)
        val input = ByteBuffer.wrap(header)
        input.get()
        val sentAt = input.long
        require(kotlin.math.abs(now - sentAt) <= ALLOWED_CLOCK_SKEW) { "Expired BLE control envelope" }
        val nonce = ByteArray(12).also(input::get)
        val plaintext = TransferCrypto.decrypt(key, nonce, envelope.copyOfRange(headerSize, envelope.size), header)
        replayCache.accept(nonce)
        return plaintext
    }
}
