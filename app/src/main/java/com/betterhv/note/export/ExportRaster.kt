package com.betterhv.note.export

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bounds the raster memory of [ExportRenderMode.FAST].
 *
 * A notebook page's logical unit is 1/96 inch. Rendering at 2x keeps handwriting
 * crisp without decoding a full A4 scan at its native resolution. The pixel and
 * side caps keep one page's ARGB_8888 bitmap plus the PDF encoder's copy inside
 * a bounded budget on the e-ink tablet.
 */
object ExportRaster {
  const val TARGET_SCALE = 2f
  const val MAX_PIXELS = 8_000_000.0
  const val MAX_SIDE = 8192f

  fun scaleFor(width: Float, height: Float): Float {
    require(width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
      "Invalid page dimensions for raster export"
    }
    val pixels = width.toDouble() * height.toDouble()
    val pixelCap = sqrt(MAX_PIXELS / pixels).toFloat()
    val sideCap = MAX_SIDE / maxOf(width, height)
    return min(TARGET_SCALE, min(pixelCap, sideCap))
  }

  fun pixelSize(width: Float, height: Float): Pair<Int, Int> {
    val scale = scaleFor(width, height)
    return (width * scale).roundToInt().coerceAtLeast(1) to
      (height * scale).roundToInt().coerceAtLeast(1)
  }
}
