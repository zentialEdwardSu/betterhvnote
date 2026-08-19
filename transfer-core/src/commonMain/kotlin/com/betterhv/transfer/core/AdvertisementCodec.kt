package com.betterhv.transfer.core

data class NoteLinkAdvertisement(
    val protocolVersion: Int,
    val imageCount: Int,
    val textCount: Int,
    val identityHash: ByteArray,
    val deviceName: String
) {
    override fun equals(other: Any?): Boolean = other is NoteLinkAdvertisement &&
        protocolVersion == other.protocolVersion && imageCount == other.imageCount &&
        textCount == other.textCount && identityHash.contentEquals(other.identityHash) &&
        deviceName == other.deviceName

    override fun hashCode(): Int = 31 * identityHash.contentHashCode() + deviceName.hashCode()
}

object NoteLinkAdvertisementCodec {
    const val MANUFACTURER_ID = 0x0B17
    const val IDENTITY_HASH_BYTES = 5
    const val MAX_NAME_BYTES = 16
    private const val HEADER_BYTES = 3 + IDENTITY_HASH_BYTES

    fun encode(identityHash: ByteArray, deviceName: String, imageCount: Int, textCount: Int): ByteArray {
        require(identityHash.size >= IDENTITY_HASH_BYTES) { "NoteLink identity hash is too short" }
        val name = deviceName.encodeToByteArray().copyOf(MAX_NAME_BYTES)
        val nameLength = deviceName.encodeToByteArray().size.coerceAtMost(MAX_NAME_BYTES)
        return ByteArray(HEADER_BYTES + nameLength).also { output ->
            output[0] = NoteLinkIdentityCodec.PROTOCOL_VERSION.toByte()
            output[1] = imageCount.coerceIn(0, 255).toByte()
            output[2] = textCount.coerceIn(0, 255).toByte()
            identityHash.copyInto(output, 3, 0, IDENTITY_HASH_BYTES)
            name.copyInto(output, HEADER_BYTES, 0, nameLength)
        }
    }

    fun decode(bytes: ByteArray): NoteLinkAdvertisement {
        require(bytes.size >= HEADER_BYTES) { "Truncated NoteLink advertisement" }
        val version = bytes[0].toInt() and 0xff
        require(version == NoteLinkIdentityCodec.PROTOCOL_VERSION) {
            "Unsupported NoteLink advertisement version $version"
        }
        return NoteLinkAdvertisement(
            protocolVersion = version,
            imageCount = bytes[1].toInt() and 0xff,
            textCount = bytes[2].toInt() and 0xff,
            identityHash = bytes.copyOfRange(3, HEADER_BYTES),
            deviceName = bytes.copyOfRange(HEADER_BYTES, bytes.size).decodeToString().trim().ifBlank { "NoteLink" }
        )
    }
}
