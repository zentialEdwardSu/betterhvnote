package com.betterhv.transfer.core

/** The signal that resolved a piece of shared content. */
enum class SniffSource { MAGIC, EXTENSION, DECLARED_MIME }

/** A supported payload resolved from its bytes, file name and declared MIME type. */
data class SniffedContent(val kind: ContentKind, val mimeType: String, val source: SniffSource)

/**
 * Resolves shared or imported content from three independent signals.
 *
 * Precedence is bytes, then file name, then the provider-declared MIME type. Android providers and
 * chat apps routinely report `application/octet-stream`, a null type, or a type that contradicts
 * the payload, while the leading bytes are authoritative. Keeping this in transfer-core gives the
 * Android and Windows senders identical answers for the same file.
 */
@Suppress("MagicNumber")
object ContentSniffer {
  const val HEADER_BYTES = 16

  private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
  private val GIF87_SIGNATURE = "GIF87a".encodeToByteArray()
  private val GIF89_SIGNATURE = "GIF89a".encodeToByteArray()
  private val RIFF_SIGNATURE = "RIFF".encodeToByteArray()
  private val WEBP_SIGNATURE = "WEBP".encodeToByteArray()
  private val PDF_SIGNATURE = "%PDF-".encodeToByteArray()

  private val IMAGE_MIME_BY_EXTENSION = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "webp" to "image/webp",
    "gif" to "image/gif",
    "bmp" to "image/bmp",
  )

  private const val IMAGE_PREFIX = "image/"
  private const val PDF_MIME = "application/pdf"
  private const val WILDCARD = "*"
  private const val WEBP_OFFSET = 8
  private const val WEBP_HEADER_BYTES = 12
  private const val BMP_HEADER_BYTES = 14
  private val BMP_RESERVED_BYTES = 6..9

  fun sniff(declaredMime: String?, fileName: String?, header: ByteArray): SniffedContent? =
    fromMagic(header) ?: fromExtension(fileName) ?: fromDeclaredMime(declaredMime)

  /**
   * Normalizes a concrete image MIME type and rejects wildcards and non-images: `image/` followed
   * by a star, a blank subtype, or a non-`image/` prefix all return null. Parameters such as
   * `; charset=` are dropped and the legacy `image/jpg` alias is folded.
   */
  fun normalizeImageMime(mime: String): String? {
    val normalized = mime.substringBefore(';').trim().lowercase()
    if (!normalized.startsWith(IMAGE_PREFIX)) return null
    val subtype = normalized.removePrefix(IMAGE_PREFIX)
    if (subtype.isBlank() || subtype == WILDCARD || WILDCARD in subtype) return null
    return if (subtype == "jpg") "image/jpeg" else IMAGE_PREFIX + subtype
  }

  private fun fromMagic(header: ByteArray): SniffedContent? = when {
    header.hasPrefix(PNG_SIGNATURE, offset = 0) -> image("image/png")
    header.hasPrefix(JPEG_SIGNATURE, offset = 0) -> image("image/jpeg")
    header.hasPrefix(GIF87_SIGNATURE, offset = 0) || header.hasPrefix(GIF89_SIGNATURE, offset = 0) ->
      image("image/gif")
    header.isWebP() -> image("image/webp")
    header.isBmp() -> image("image/bmp")
    header.hasPrefix(PDF_SIGNATURE, offset = 0) -> SniffedContent(ContentKind.PDF, PDF_MIME, SniffSource.MAGIC)
    else -> null
  }

  private fun fromExtension(fileName: String?): SniffedContent? {
    val extension = fileName?.substringAfterLast('.', "")?.trim()?.lowercase().orEmpty()
    if (extension == "pdf") return SniffedContent(ContentKind.PDF, PDF_MIME, SniffSource.EXTENSION)
    val mimeType = IMAGE_MIME_BY_EXTENSION[extension] ?: return null
    return SniffedContent(ContentKind.IMAGE, mimeType, SniffSource.EXTENSION)
  }

  private fun fromDeclaredMime(declaredMime: String?): SniffedContent? {
    val normalized = declaredMime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (normalized == PDF_MIME) return SniffedContent(ContentKind.PDF, PDF_MIME, SniffSource.DECLARED_MIME)
    val mimeType = normalizeImageMime(normalized) ?: return null
    return SniffedContent(ContentKind.IMAGE, mimeType, SniffSource.DECLARED_MIME)
  }

  private fun image(mimeType: String) = SniffedContent(ContentKind.IMAGE, mimeType, SniffSource.MAGIC)

  private fun ByteArray.hasPrefix(prefix: ByteArray, offset: Int): Boolean {
    if (size < offset + prefix.size) return false
    for (index in prefix.indices) {
      if (this[offset + index] != prefix[index]) return false
    }
    return true
  }

  private fun ByteArray.isWebP(): Boolean =
    size >= WEBP_HEADER_BYTES &&
      hasPrefix(RIFF_SIGNATURE, offset = 0) &&
      hasPrefix(WEBP_SIGNATURE, offset = WEBP_OFFSET)

  private fun ByteArray.isBmp(): Boolean =
    size >= BMP_HEADER_BYTES &&
      this[0] == 'B'.code.toByte() &&
      this[1] == 'M'.code.toByte() &&
      BMP_RESERVED_BYTES.all { this[it] == 0.toByte() }
}
