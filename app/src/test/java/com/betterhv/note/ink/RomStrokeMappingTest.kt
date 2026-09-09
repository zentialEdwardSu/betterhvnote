package com.betterhv.note.ink

import org.junit.Assert.assertEquals
import org.junit.Test

class RomStrokeMappingTest {
  @Test fun normalPenUsesExactConfiguredIntegerWidth() {
    assertEquals(0.5f, RomStrokeMapping.pointScalar(PenType.NormalPen, 5.5f, 11), 0.0001f)
  }

  @Test fun fixedBrushesIgnoreVendorWidth() {
    assertEquals(1f, RomStrokeMapping.pointScalar(PenType.Pencil, Float.NaN, 11), 0f)
    assertEquals(1f, RomStrokeMapping.pointScalar(PenType.Marker, 99f, 11), 0f)
  }

  @Test fun effectivePageWidthCancelsViewportScale() {
    val configured = 24
    val scale = 2f
    val style = PenStyle(baseWidth = RomStrokeMapping.effectivePageWidth(configured, scale))
    val scalar = RomStrokeMapping.pointScalar(PenType.NormalPen, 9f, configured)
    assertEquals(9f / scale, style.widthAt(InkPoint(0f, 0f, scalar, 0L)), 0.0001f)
  }

  @Test fun proportionalRomWidthsRestoreTheSamePageWidthAtDifferentZooms() {
    fun resolvedPageWidth(configuredWidthPx: Int, vendorWidthPx: Float, scale: Float): Float {
      val style = PenStyle(baseWidth = RomStrokeMapping.effectivePageWidth(configuredWidthPx, scale))
      val scalar = RomStrokeMapping.pointScalar(PenType.NormalPen, vendorWidthPx, configuredWidthPx)
      return style.widthAt(InkPoint(0f, 0f, scalar, 0L))
    }

    assertEquals(5.5f, resolvedPageWidth(configuredWidthPx = 11, vendorWidthPx = 5.5f, scale = 1f), 0.0001f)
    assertEquals(5.5f, resolvedPageWidth(configuredWidthPx = 23, vendorWidthPx = 11f, scale = 2f), 0.0001f)
  }

  @Test fun outOfRangeRatioIsNotClippedAtTheInputBoundary() {
    assertEquals(1.25f, RomStrokeMapping.pointScalar(PenType.NormalPen, 5f, 4), 0.0001f)
  }
}
