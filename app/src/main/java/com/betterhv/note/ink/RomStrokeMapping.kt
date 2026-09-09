package com.betterhv.note.ink

/** Pure conversion at the HvPenDraw callback boundary. */
object RomStrokeMapping {
  fun pointScalar(penType: PenType, vendorWidthPx: Float, configuredWidthPx: Int): Float =
    when (penType) {
      PenType.NormalPen -> vendorWidthPx / configuredWidthPx.coerceAtLeast(1)
      PenType.Pencil, PenType.Marker -> 1f
    }

  fun effectivePageWidth(configuredWidthPx: Int, viewportScale: Float): Float {
    require(configuredWidthPx > 0)
    require(viewportScale.isFinite() && viewportScale > 0f)
    return configuredWidthPx / viewportScale
  }
}
