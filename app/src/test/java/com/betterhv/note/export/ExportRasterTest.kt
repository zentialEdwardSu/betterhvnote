package com.betterhv.note.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportRasterTest {
  @Test fun ordinaryPageUsesTwoTimesScale() {
    assertEquals(2f, ExportRaster.scaleFor(600f, 800f), EPS)
    assertEquals(1200 to 1600, ExportRaster.pixelSize(600f, 800f))
  }

  @Test fun pixelBudgetCapsLargePages() {
    val width = 4000f
    val height = 6000f
    val (pixelWidth, pixelHeight) = ExportRaster.pixelSize(width, height)
    assertTrue(ExportRaster.scaleFor(width, height) < 2f)
    assertTrue(pixelWidth.toLong() * pixelHeight <= 8_100_000L)
  }

  @Test fun sideBudgetCapsElongatedPages() {
    val (pixelWidth, pixelHeight) = ExportRaster.pixelSize(100f, 10000f)
    assertTrue(pixelHeight <= 8192)
    assertTrue(pixelWidth >= 1)
  }

  @Test fun degeneratePagesAreRejected() {
    listOf(0f to 100f, 100f to 0f, -1f to 100f, Float.NaN to 100f).forEach { (width, height) ->
      runCatching { ExportRaster.scaleFor(width, height) }
        .onSuccess { error("Expected rejection for $width x $height") }
    }
  }

  @Test fun tinyPagesKeepAtLeastOnePixel() {
    assertEquals(1 to 1, ExportRaster.pixelSize(0.1f, 0.1f))
  }

  private companion object {
    const val EPS = 0.0001f
  }
}
