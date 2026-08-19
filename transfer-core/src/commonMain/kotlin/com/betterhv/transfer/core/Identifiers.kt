package com.betterhv.transfer.core

@JvmInline
value class DeviceId(val value: String) {
    init { require(value.isNotBlank()) { "Device ID cannot be blank" } }
    val isLegacy: Boolean get() = value == LEGACY_PHONE || value == LEGACY_NOTE
    override fun toString(): String = value

    companion object {
        const val LEGACY_PHONE = "betterhv-phone"
        const val LEGACY_NOTE = "betterhv-note"
    }
}

/** UUID-compatible 128-bit transfer identifier without a java.util.UUID dependency. */
data class TransferId(val mostSignificantBits: Long, val leastSignificantBits: Long) {
    fun toByteArray(): ByteArray = ByteArray(16).also { output ->
        putLong(output, 0, mostSignificantBits)
        putLong(output, 8, leastSignificantBits)
    }

    override fun toString(): String {
        val bytes = toByteArray()
        val hex = bytes.joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            HEX[value ushr 4].toString() + HEX[value and 0x0f]
        }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    companion object {
        private const val HEX = "0123456789abcdef"

        fun fromByteArray(bytes: ByteArray): TransferId {
            require(bytes.size == 16) { "Transfer ID must contain 16 bytes" }
            return TransferId(readLong(bytes, 0), readLong(bytes, 8))
        }

        fun parse(value: String): TransferId {
            require(value.length == 36 && value[8] == '-' && value[13] == '-' && value[18] == '-' && value[23] == '-') {
                "Invalid transfer UUID"
            }
            val compact = value.filterNot { it == '-' }
            require(compact.length == 32)
            val bytes = ByteArray(16) { index ->
                val high = compact[index * 2].hexValue()
                val low = compact[index * 2 + 1].hexValue()
                ((high shl 4) or low).toByte()
            }
            return fromByteArray(bytes)
        }

        private fun Char.hexValue(): Int = when (this) {
            in '0'..'9' -> this - '0'
            in 'a'..'f' -> this - 'a' + 10
            in 'A'..'F' -> this - 'A' + 10
            else -> throw IllegalArgumentException("Invalid hexadecimal UUID")
        }

        private fun putLong(target: ByteArray, offset: Int, value: Long) {
            for (index in 0 until 8) target[offset + index] = (value ushr (56 - index * 8)).toByte()
        }

        private fun readLong(source: ByteArray, offset: Int): Long {
            var value = 0L
            for (index in 0 until 8) value = (value shl 8) or (source[offset + index].toLong() and 0xff)
            return value
        }
    }
}

fun interface TransferIdGenerator { fun next(): TransferId }
fun interface PlatformClock { fun nowMillis(): Long }
interface KeyProtector {
    fun protect(value: ByteArray): ByteArray
    fun unprotect(value: ByteArray): ByteArray
}

interface TransferFile {
    val path: String
    val length: Long
    fun read(offset: Long, maximumBytes: Int): ByteArray
    fun append(bytes: ByteArray)
    fun replaceWith(source: TransferFile)
    fun delete(): Boolean
}

interface TransferSocket : AutoCloseable {
    fun read(maximumBytes: Int): ByteArray
    fun write(bytes: ByteArray)
}

interface TransferSocketFactory {
    fun connect(host: String, port: Int, timeoutMillis: Int): TransferSocket
    fun accept(port: Int, timeoutMillis: Int): TransferSocket
}
