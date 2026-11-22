package com.betterhv.note.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ThumbnailRevisionGateTest {
  @Test
  fun staleGenerationCannotReplaceNewerRequest() {
    val id = UUID.randomUUID()
    val gate = ThumbnailRevisionGate()
    val old = ThumbnailKey(id, 4L)
    val current = ThumbnailKey(id, 5L)
    assertTrue(gate.expect(old))
    assertTrue(gate.expect(current))
    assertFalse(gate.isCurrent(old))
    assertTrue(gate.isCurrent(current))
    assertFalse(gate.expect(old))
    assertTrue(gate.isCurrent(current))
  }

  @Test
  fun backgroundRevisionReplacesSameContentGeneration() {
    val id = UUID.randomUUID()
    val gate = ThumbnailRevisionGate()
    val old = ThumbnailKey(id, 5L, "template-a")
    val current = ThumbnailKey(id, 5L, "template-b")
    assertTrue(gate.expect(old))
    assertTrue(gate.expect(current))
    assertFalse(gate.isCurrent(old))
    assertTrue(gate.isCurrent(current))
  }

  @Test
  fun removeClearsGateAndBackgroundInvalidationEvictsSameContentRevision() {
    val pageId = UUID.randomUUID()
    val otherPageId = UUID.randomUUID()
    val gate = ThumbnailRevisionGate()
    val prior = ThumbnailKey(pageId, 5L, "template-a")
    assertTrue(gate.expect(prior))
    assertTrue(gate.isCurrent(prior))

    gate.remove(pageId)
    assertFalse(gate.isCurrent(prior))
    val resetKey = ThumbnailKey(pageId, 1L, "template-a")
    assertTrue(gate.expect(resetKey))
    assertTrue(gate.isCurrent(resetKey))

    val keep = ThumbnailKey(pageId, 5L, "template-b")
    val sameContentOldBg = ThumbnailKey(pageId, 5L, "template-a")
    val oldContentSameBg = ThumbnailKey(pageId, 4L, "template-b")
    val otherPageKey = ThumbnailKey(otherPageId, 5L, "template-a")
    val evicted = staleThumbnailKeys(
      listOf(keep, sameContentOldBg, oldContentSameBg, otherPageKey),
      keep,
    )
    assertEquals(listOf(sameContentOldBg, oldContentSameBg), evicted)
  }
}
