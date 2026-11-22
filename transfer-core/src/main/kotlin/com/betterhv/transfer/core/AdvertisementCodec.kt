package com.betterhv.transfer.core

data class NoteLinkAdvertisement(
  val protocolVersion: Int,
  val imageCount: Int,
  val textCount: Int,
  val pdfCount: Int,
  val identityHash: ByteArray,
  val deviceName: String,
  val flags: Int = 0,
) {
  val pairing: Boolean get() = flags and NoteLinkAdvertisementCodec.FLAG_PAIRING != 0

  override fun equals(other: Any?): Boolean = other is NoteLinkAdvertisement &&
    protocolVersion == other.protocolVersion && imageCount == other.imageCount &&
    textCount == other.textCount && pdfCount == other.pdfCount && identityHash.contentEquals(other.identityHash) &&
    deviceName == other.deviceName && flags == other.flags

  override fun hashCode(): Int = 31 * identityHash.contentHashCode() + deviceName.hashCode()
}

/**
 * Connectable advertisement payload (manufacturer data on Android, service data on Windows).
 *
 * v4 layout: `u8 version=4 | u8 flags | u8 images | u8 texts | u8 pdfs | u8 identity_hash[5] | name`.
 * `flags` uses the `DiscoveryFlag` bits of `discovery.proto`.
 */
object NoteLinkAdvertisementCodec {
  const val MANUFACTURER_ID = 0x0B17
  const val VERSION = 4
  const val IDENTITY_HASH_BYTES = 5
  const val MAX_NAME_BYTES = 16
  const val FLAG_PAIRING = 1
  const val FLAG_HAS_ITEMS = 2
  private const val HEADER_BYTES = 5 + IDENTITY_HASH_BYTES

  fun encode(
    identityHash: ByteArray,
    deviceName: String,
    imageCount: Int,
    textCount: Int,
    pdfCount: Int = 0,
    flags: Int = 0,
  ): ByteArray {
    require(identityHash.size >= IDENTITY_HASH_BYTES) { "NoteLink identity hash is too short" }
    val encodedName = deviceName.encodeToByteArray()
    val nameLength = encodedName.size.coerceAtMost(MAX_NAME_BYTES)
    val hasItems = imageCount > 0 || textCount > 0 || pdfCount > 0
    return ByteArray(HEADER_BYTES + nameLength).also { output ->
      output[0] = VERSION.toByte()
      output[1] = (flags or if (hasItems) FLAG_HAS_ITEMS else 0).toByte()
      output[2] = imageCount.coerceIn(0, 255).toByte()
      output[3] = textCount.coerceIn(0, 255).toByte()
      output[4] = pdfCount.coerceIn(0, 255).toByte()
      identityHash.copyInto(output, 5, 0, IDENTITY_HASH_BYTES)
      encodedName.copyInto(output, HEADER_BYTES, 0, nameLength)
    }
  }

  fun decode(bytes: ByteArray): NoteLinkAdvertisement {
    require(bytes.size >= HEADER_BYTES) { "Truncated NoteLink advertisement" }
    val version = bytes[0].toInt() and 0xff
    require(version == VERSION) { "Unsupported NoteLink advertisement version $version" }
    return NoteLinkAdvertisement(
      protocolVersion = version,
      imageCount = bytes[2].toInt() and 0xff,
      textCount = bytes[3].toInt() and 0xff,
      pdfCount = bytes[4].toInt() and 0xff,
      identityHash = bytes.copyOfRange(5, HEADER_BYTES),
      deviceName = bytes.copyOfRange(HEADER_BYTES, bytes.size).decodeToString().trim().ifBlank { "NoteLink" },
      flags = bytes[1].toInt() and 0xff,
    )
  }
}
