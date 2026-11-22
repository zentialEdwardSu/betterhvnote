package com.betterhv.note.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateBitmapCacheTest {
  private class FakeBitmap(val sizeKb: Int) {
    var valid: Boolean = true
    var recycleCount: Int = 0
  }

  private fun newCache(maxSizeKb: Int = 24 * 1024): TemplateBitmapCache<FakeBitmap> =
    TemplateBitmapCache(
      maxSizeKb,
      { it.sizeKb },
      { it.valid },
      { bitmap ->
        bitmap.recycleCount++
        bitmap.valid = false
      },
    )

  @Test fun oversizedEntryIsEphemeralAndDoesNotEvictOrRecycleOnAcquire() {
    val cache = newCache()
    val small = FakeBitmap(10 * 1024)
    val smallEntry = requireNotNull(cache.acquire("small") { small })
    assertFalse(smallEntry.ephemeral)
    cache.release(smallEntry)
    assertEquals(0, small.recycleCount)

    val huge = FakeBitmap(30 * 1024)
    val hugeEntry = requireNotNull(cache.acquire("huge") { huge })
    assertTrue(hugeEntry.ephemeral)
    assertSame(huge, hugeEntry.value)
    assertEquals(0, huge.recycleCount)
    assertTrue(huge.valid)
    assertEquals(0, small.recycleCount)
    assertTrue(small.valid)

    var smallRecreated = false
    val cachedSmall = requireNotNull(
      cache.acquire("small") {
        smallRecreated = true
        FakeBitmap(10 * 1024)
      },
    )
    assertFalse(smallRecreated)
    assertSame(small, cachedSmall.value)

    var hugeCreateCount = 0
    val hugeAgain = requireNotNull(
      cache.acquire("huge") {
        hugeCreateCount++
        FakeBitmap(30 * 1024)
      },
    )
    assertEquals(1, hugeCreateCount)
    assertNotSame(huge, hugeAgain.value)

    cache.release(hugeEntry)
    assertEquals(1, huge.recycleCount)
    assertFalse(huge.valid)

    cache.release(hugeEntry)
    assertEquals(1, huge.recycleCount)
  }

  @Test fun entriesWithinBudgetHitCacheAndEvictLruWhenExceedingBudget() {
    val cache = newCache()
    val first = FakeBitmap(10 * 1024)
    val second = FakeBitmap(10 * 1024)
    val third = FakeBitmap(10 * 1024)

    val firstEntry = requireNotNull(cache.acquire("a") { first })
    val secondEntry = requireNotNull(cache.acquire("b") { second })
    assertFalse(firstEntry.ephemeral)
    assertFalse(secondEntry.ephemeral)

    var firstHitRecreated = false
    val firstHit = requireNotNull(
      cache.acquire("a") {
        firstHitRecreated = true
        FakeBitmap(10 * 1024)
      },
    )
    assertFalse(firstHitRecreated)
    assertSame(first, firstHit.value)

    val thirdEntry = requireNotNull(cache.acquire("c") { third })
    assertFalse(thirdEntry.ephemeral)

    assertEquals(1, second.recycleCount)
    assertFalse(second.valid)
    assertEquals(0, first.recycleCount)
    assertEquals(0, third.recycleCount)

    var secondRecreated = false
    val secondReacquired = requireNotNull(
      cache.acquire("b") {
        secondRecreated = true
        FakeBitmap(10 * 1024)
      },
    )
    assertTrue(secondRecreated)
    assertNotSame(second, secondReacquired.value)
  }

  @Test fun exactBudgetEntryIsCachedAndClearRecyclesAllCachedEntries() {
    val cache = newCache()
    val exact = FakeBitmap(24 * 1024)
    val exactEntry = requireNotNull(cache.acquire("exact") { exact })
    assertFalse(exactEntry.ephemeral)

    val overByOne = FakeBitmap(24 * 1024 + 1)
    val overEntry = requireNotNull(cache.acquire("over") { overByOne })
    assertTrue(overEntry.ephemeral)
    assertEquals(0, exact.recycleCount)

    cache.clear()
    assertEquals(1, exact.recycleCount)
    assertFalse(exact.valid)

    var recreated = false
    assertNotNull(
      cache.acquire("exact") {
        recreated = true
        FakeBitmap(24 * 1024)
      },
    )
    assertTrue(recreated)
    cache.release(overEntry)
    assertEquals(1, overByOne.recycleCount)
  }

  @Test fun invalidCachedEntryIsRecreatedAndNullCreateReturnsNull() {
    val cache = newCache()
    val stale = FakeBitmap(1024)
    cache.acquire("stale") { stale }
    stale.valid = false

    val fresh = FakeBitmap(1024)
    val acquired = requireNotNull(cache.acquire("stale") { fresh })
    assertSame(fresh, acquired.value)
    assertNull(cache.acquire("missing") { null })
  }

  @Test fun tinyEntriesAreEphemeralUnderZeroBudgetAndCountAsOneKb() {
    val zeroBudgetCache = newCache(maxSizeKb = 0)
    val ephemeralTiny = FakeBitmap(0)
    val ephemeralEntry = requireNotNull(zeroBudgetCache.acquire("tiny") { ephemeralTiny })
    assertTrue(ephemeralEntry.ephemeral)
    zeroBudgetCache.release(ephemeralEntry)
    assertEquals(1, ephemeralTiny.recycleCount)
    assertFalse(ephemeralTiny.valid)

    val twoKbCache = newCache(maxSizeKb = 2)
    val first = FakeBitmap(0)
    val second = FakeBitmap(0)
    val third = FakeBitmap(0)
    assertFalse(requireNotNull(twoKbCache.acquire("a") { first }).ephemeral)
    assertFalse(requireNotNull(twoKbCache.acquire("b") { second }).ephemeral)
    assertEquals(0, first.recycleCount)
    assertEquals(0, second.recycleCount)

    assertFalse(requireNotNull(twoKbCache.acquire("c") { third }).ephemeral)
    assertEquals(1, first.recycleCount)
    assertFalse(first.valid)
    assertEquals(0, second.recycleCount)
    assertEquals(0, third.recycleCount)
  }
}
