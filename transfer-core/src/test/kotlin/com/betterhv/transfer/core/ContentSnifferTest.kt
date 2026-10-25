package com.betterhv.transfer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ContentSnifferTest {
  @Test fun `magic bytes win over a generic declared type`() {
    val resolved = requireNotNull(ContentSniffer.sniff("application/octet-stream", "capture", PNG_HEADER))

    assertEquals(ContentKind.IMAGE, resolved.kind)
    assertEquals("image/png", resolved.mimeType)
    assertEquals(SniffSource.MAGIC, resolved.source)
  }

  @Test fun `magic bytes win over a contradicting extension and declared type`() {
    val resolved = requireNotNull(ContentSniffer.sniff("image/png", "page.png", PDF_HEADER))

    assertEquals(ContentKind.PDF, resolved.kind)
    assertEquals("application/pdf", resolved.mimeType)
    assertEquals(SniffSource.MAGIC, resolved.source)
  }

  @Test fun `extension is used when the header is unavailable`() {
    val resolved = requireNotNull(ContentSniffer.sniff("image/png", "SCAN.PDF", ByteArray(0)))

    assertEquals(ContentKind.PDF, resolved.kind)
    assertEquals(SniffSource.EXTENSION, resolved.source)
  }

  @Test fun `declared image type is used when bytes and name are unknown`() {
    val resolved = requireNotNull(ContentSniffer.sniff("image/webp; charset=binary", "screenshot", ByteArray(2)))

    assertEquals(ContentKind.IMAGE, resolved.kind)
    assertEquals("image/webp", resolved.mimeType)
    assertEquals(SniffSource.DECLARED_MIME, resolved.source)
  }

  @Test fun `generic and unknown types are rejected`() {
    assertNull(ContentSniffer.sniff("image/*", "screenshot", ByteArray(0)))
    assertNull(ContentSniffer.sniff("*/*", "archive", ByteArray(0)))
    assertNull(ContentSniffer.sniff("application/zip", "archive.zip", byteArrayOf(1, 2, 3)))
    assertNull(ContentSniffer.sniff(null, "archive", ByteArray(0)))
  }

  @Test fun `recognizes every supported container from its magic bytes`() {
    assertEquals("image/jpeg", ContentSniffer.sniff(null, null, JPEG_HEADER)?.mimeType)
    assertEquals("image/gif", ContentSniffer.sniff(null, null, GIF_HEADER)?.mimeType)
    assertEquals("image/webp", ContentSniffer.sniff(null, null, WEBP_HEADER)?.mimeType)
    assertEquals("image/bmp", ContentSniffer.sniff(null, null, BMP_HEADER)?.mimeType)
    assertEquals("application/pdf", ContentSniffer.sniff(null, null, PDF_HEADER)?.mimeType)
  }

  @Test fun `short bmp like header is not trusted`() {
    assertNull(ContentSniffer.sniff("application/octet-stream", null, "BM".encodeToByteArray()))
  }

  @Test fun `normalizes image subtypes and rejects wildcards`() {
    assertEquals("image/png", ContentSniffer.normalizeImageMime("IMAGE/PNG; charset=binary"))
    assertEquals("image/jpeg", ContentSniffer.normalizeImageMime("image/jpg"))
    assertNull(ContentSniffer.normalizeImageMime("image/*"))
    assertNull(ContentSniffer.normalizeImageMime("text/plain"))
  }

  @Test fun `random input never throws and stays self consistent`() {
    val random = Random(20240517)
    val mimeTypes = listOf(
      null,
      "",
      "application/octet-stream",
      "image/png",
      "image/*",
      "application/pdf",
      "text/plain",
    )
    val names = listOf(null, "", "a.png", "b.jpg", "c.pdf", "d.webp", "no-extension", ".hidden")
    repeat(RANDOM_CASES) {
      val header = ByteArray(random.nextInt(0, 24)).also { bytes -> random.nextBytes(bytes) }
      val declared = mimeTypes[random.nextInt(mimeTypes.size)]
      val name = names[random.nextInt(names.size)]
      val first = ContentSniffer.sniff(declared, name, header)
      val second = ContentSniffer.sniff(declared, name, header)
      assertEquals(first, second)
      if (first != null) {
        when (first.kind) {
          ContentKind.PDF -> assertEquals("application/pdf", first.mimeType)
          else -> assertTrue(first.mimeType.startsWith("image/"))
        }
      }
    }
  }

  private companion object {
    const val RANDOM_CASES = 500

    val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    val GIF_HEADER = "GIF89a".encodeToByteArray()
    val WEBP_HEADER = "RIFF".encodeToByteArray() + ByteArray(4) + "WEBP".encodeToByteArray()
    val BMP_HEADER = "BM".encodeToByteArray() + ByteArray(12)
    val PDF_HEADER = "%PDF-1.7\n".encodeToByteArray()
  }
}
