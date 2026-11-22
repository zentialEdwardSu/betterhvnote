package com.betterhv.note.sender

import com.betterhv.note.sender.share.PlannedShare
import com.betterhv.note.sender.share.ShareLimits
import com.betterhv.note.sender.share.SharePlanner
import com.betterhv.note.sender.share.ShareRejectReason
import com.betterhv.note.sender.share.ShareRejection
import com.betterhv.note.sender.share.SharedPayload
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.TransferLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePlanTest {
  private val limits = ShareLimits(imageBytes = 16)

  @Test
  fun `magic bytes override a generic provider type and a missing name`() {
    val plan = SharePlanner.plan(
      listOf(payload(name = "capture", mime = "application/octet-stream", header = PNG_HEADER)),
      limits,
    )

    val accepted = plan.accepted.single()
    assertEquals(ContentKind.IMAGE, accepted.kind)
    assertEquals("image/png", accepted.mimeType)
    assertTrue(plan.rejected.isEmpty())
  }

  @Test
  fun `rejects unsupported and empty payloads with a reason`() {
    val plan = SharePlanner.plan(
      listOf(
        payload(name = "archive.zip", mime = "application/zip", header = byteArrayOf(1, 2, 3)),
        payload(name = "empty.png", mime = "image/png", header = ByteArray(0), byteLength = 0),
      ),
      limits,
    )

    assertTrue(plan.accepted.isEmpty())
    assertEquals(
      listOf(ShareRejectReason.UNSUPPORTED_TYPE, ShareRejectReason.EMPTY),
      plan.rejected.map(ShareRejection::reason),
    )
    assertEquals("application/zip", plan.rejected.first().detectedType)
  }

  @Test
  fun `rejects oversized items and reports the limit`() {
    val plan = SharePlanner.plan(
      listOf(
        payload(
          name = "page.pdf",
          mime = "application/pdf",
          header = PDF_HEADER,
          byteLength = TransferLimits.MAX_PDF_BYTES + 1,
        ),
      ),
      limits,
    )

    val rejection = plan.rejected.single()
    assertEquals(ShareRejectReason.TOO_LARGE, rejection.reason)
    assertEquals("512 MiB", rejection.limitDescription)
  }

  @Test
  fun `keeps the rest of a batch when one item is rejected`() {
    val plan = SharePlanner.plan(
      listOf(
        payload(name = "first.png", mime = "image/png", header = PNG_HEADER),
        payload(name = "middle.zip", mime = "application/zip", header = byteArrayOf(1, 2, 3)),
        payload(name = "last.pdf", mime = null, header = PDF_HEADER),
      ),
      limits,
    )

    assertEquals(listOf(ContentKind.IMAGE, ContentKind.PDF), plan.accepted.map(PlannedShare::kind))
    assertEquals(1, plan.rejected.size)
    assertEquals("middle.zip", plan.rejected.single().displayName)
  }

  @Test
  fun `an unknown name with a concrete declared image type is accepted`() {
    val plan = SharePlanner.plan(
      listOf(payload(name = null, mime = "image/webp", header = ByteArray(2))),
      limits,
    )

    assertEquals("image/webp", plan.accepted.single().mimeType)
  }

  private fun payload(
    name: String?,
    mime: String?,
    header: ByteArray,
    byteLength: Long = header.size.toLong(),
  ) = SharedPayload(name, mime, byteLength, header)

  private companion object {
    val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val PDF_HEADER = "%PDF-1.7".encodeToByteArray()
  }
}
