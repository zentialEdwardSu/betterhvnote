package com.betterhv.transfer.core

data class NoteLinkIdentity(
    val protocolVersion: Int,
    val imageCount: Int,
    val textCount: Int,
    val deviceId: DeviceId,
    val deviceName: String
)

object NoteLinkIdentityCodec {
    const val PROTOCOL_VERSION = 2
    private const val FIELD_BYTES = 48
    private const val TOTAL_BYTES = 4 + FIELD_BYTES + FIELD_BYTES

    fun encode(deviceId: DeviceId, deviceName: String, imageCount: Int, textCount: Int): ByteArray {
        val id = deviceId.value.encodeToByteArray()
        val name = deviceName.encodeToByteArray()
        require(id.size in 1..FIELD_BYTES) { "Invalid NoteLink device ID" }
        require(name.size <= FIELD_BYTES) { "NoteLink name exceeds 48 UTF-8 bytes" }
        return ByteArray(TOTAL_BYTES).also { output ->
            output[0] = PROTOCOL_VERSION.toByte()
            output[1] = imageCount.coerceIn(0, 255).toByte()
            output[2] = textCount.coerceIn(0, 255).toByte()
            output[3] = id.size.toByte()
            id.copyInto(output, 4)
            name.copyInto(output, 4 + FIELD_BYTES)
        }
    }

    fun decode(bytes: ByteArray): NoteLinkIdentity {
        require(bytes.size >= TOTAL_BYTES) { "Truncated NoteLink identity" }
        val version = bytes[0].toInt() and 0xff
        require(version == PROTOCOL_VERSION) { "Unsupported NoteLink identity version $version" }
        val idLength = bytes[3].toInt() and 0xff
        require(idLength in 1..FIELD_BYTES) { "Invalid NoteLink device ID length" }
        val id = bytes.copyOfRange(4, 4 + idLength).decodeToString()
        val nameField = bytes.copyOfRange(4 + FIELD_BYTES, TOTAL_BYTES)
        val nameLength = nameField.indexOf(0).let { if (it < 0) nameField.size else it }
        return NoteLinkIdentity(
            version,
            bytes[1].toInt() and 0xff,
            bytes[2].toInt() and 0xff,
            DeviceId(id),
            nameField.copyOf(nameLength).decodeToString().trim().ifBlank { "NoteLink" }
        )
    }
}

sealed interface ClientSelection {
    data object NoneOnline : ClientSelection
    data class Selected(val clientId: DeviceId) : ClientSelection
    data class Choose(val clients: List<DeviceId>, val preselected: DeviceId?) : ClientSelection
}

fun selectTransferClient(
    pairedClientIds: List<DeviceId>,
    onlineClientIds: Set<DeviceId>,
    lastUsedClientId: DeviceId?
): ClientSelection {
    val eligible = pairedClientIds.distinct().filter(onlineClientIds::contains)
    return when (eligible.size) {
        0 -> ClientSelection.NoneOnline
        1 -> ClientSelection.Selected(eligible.single())
        else -> ClientSelection.Choose(eligible, lastUsedClientId?.takeIf(eligible::contains) ?: eligible.first())
    }
}
