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
    private const val VERSION: Byte = 1
    private const val HEADER_SIZE = 1 + 8 + 12
    private const val ALLOWED_CLOCK_SKEW = 120_000L

    fun seal(key: ByteArray, plaintext: ByteArray, now: Long = System.currentTimeMillis()): ByteArray {
        val nonce = TransferCrypto.randomBytes(12)
        val header = ByteBuffer.allocate(HEADER_SIZE).put(VERSION).putLong(now).put(nonce).array()
        return header + TransferCrypto.encrypt(key, nonce, plaintext, header)
    }

    fun open(
        key: ByteArray,
        envelope: ByteArray,
        replayCache: BleReplayCache,
        now: Long = System.currentTimeMillis()
    ): ByteArray {
        require(envelope.size >= HEADER_SIZE + 16) { "Truncated BLE control envelope" }
        val header = envelope.copyOfRange(0, HEADER_SIZE)
        val input = ByteBuffer.wrap(header)
        require(input.get() == VERSION) { "Unsupported BLE envelope version" }
        val sentAt = input.long
        require(kotlin.math.abs(now - sentAt) <= ALLOWED_CLOCK_SKEW) { "Expired BLE control envelope" }
        val nonce = ByteArray(12).also(input::get)
        replayCache.accept(nonce)
        return TransferCrypto.decrypt(key, nonce, envelope.copyOfRange(HEADER_SIZE, envelope.size), header)
    }
}
