package com.betterhv.note.sender

import com.betterhv.note.sender.share.ShareDedupeTracker
import com.betterhv.note.sender.share.ShareImageBounds
import com.betterhv.note.sender.share.ShareRejectReason
import com.betterhv.note.sender.share.copyStreamWithSha256
import com.betterhv.transfer.core.TransferLimits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class ShareIntakeLogicTest {
  @Test
  fun imageBoundsDistinguishesUnreadableFromTooManyPixels() {
    assertEquals(ShareRejectReason.UNREADABLE, ShareImageBounds.validate(0, 0))
    assertEquals(ShareRejectReason.UNREADABLE, ShareImageBounds.validate(-1, 100))
    assertEquals(ShareRejectReason.UNREADABLE, ShareImageBounds.validate(100, 0))

    assertNull(ShareImageBounds.validate(10_000, 10_000))
    assertEquals(
      ShareRejectReason.TOO_MANY_PIXELS,
      ShareImageBounds.validate(10_001, 10_000),
    )
    assertTrue(10_000L * 10_000L <= TransferLimits.MAX_IMAGE_PIXELS)
  }

  @Test
  fun dedupeTrackerBlocksInFlightAndWindowDuplicates() {
    var now = 1_000L
    val tracker = ShareDedupeTracker(clock = { now }, dedupeWindowMillis = 2_000L)

    assertTrue(tracker.tryAcquire("fp-1"))
    // Concurrent duplicate while fp-1 is still copying must be rejected before complete().
    assertFalse(tracker.tryAcquire("fp-1"))
    // Different fingerprint can proceed concurrently.
    assertTrue(tracker.tryAcquire("fp-2"))
    tracker.release("fp-2")

    tracker.complete("fp-1")
    tracker.release("fp-1")

    // Within dedupe window after completion.
    now = 2_500L
    assertFalse(tracker.tryAcquire("fp-1"))

    // After dedupe window expires.
    now = 3_500L
    assertTrue(tracker.tryAcquire("fp-1"))
    tracker.release("fp-1")
  }

  @Test
  fun singlePassCopyComputesSha256AndEnforcesLimit() {
    val header = byteArrayOf(1, 2, 3, 4)
    val rest = byteArrayOf(5, 6, 7, 8, 9)
    val full = header + rest
    val expectedSha = MessageDigest.getInstance("SHA-256").digest(full)

    val temp = File.createTempFile("share-copy-test", ".bin")
    try {
      val copied = copyStreamWithSha256(ByteArrayInputStream(rest), header, temp, 64L)
      assertNotNull(copied)
      assertEquals(full.size.toLong(), copied!!.totalBytes)
      assertArrayEquals(expectedSha, copied.sha256)
      assertArrayEquals(full, temp.readBytes())

      val oversized = copyStreamWithSha256(ByteArrayInputStream(rest), header, temp, 6L)
      assertNull(oversized)
    } finally {
      temp.delete()
    }
  }
}
