package com.betterhv.note.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRendererSamplingTest {
  @Test fun hundredMegapixelImageTargeting1000PxUsesSampleSize8() {
    assertEquals(8, ExportImageSampling.inSampleSize(10_000, 10_000, 1000, 1000))
  }

  @Test fun imageWithinRequestedBoundsUsesSampleSize1() {
    assertEquals(1, ExportImageSampling.inSampleSize(800, 600, 1000, 1000))
    assertEquals(2, ExportImageSampling.inSampleSize(2000, 2000, 1000, 1000))
  }

  @Test fun maxDimensionCapPreventsOversizedDecodeEvenWhenRequestedSizeIsLarge() {
    assertEquals(4, ExportImageSampling.inSampleSize(10_000, 10_000, 6000, 6000, maxDimension = 4096))
    assertEquals(4, ExportImageSampling.inSampleSize(8000, 2000, 8000, 2000, maxDimension = 2048))
  }

  @Test fun zeroAndNegativeDimensionsAreHandledSafely() {
    assertEquals(1, ExportImageSampling.inSampleSize(0, 0, 1000, 1000))
    assertEquals(1, ExportImageSampling.inSampleSize(-100, 500, 1000, 1000))

    listOf(0 to 0, 0 to 500, -10 to -20).forEach { (reqW, reqH) ->
      val sample = ExportImageSampling.inSampleSize(100, 100, reqW, reqH)
      assertTrue("sample must be >= 1, got $sample", sample >= 1)
      assertEquals("sample must be power of two, got $sample", 1, Integer.bitCount(sample))
    }
  }
}
