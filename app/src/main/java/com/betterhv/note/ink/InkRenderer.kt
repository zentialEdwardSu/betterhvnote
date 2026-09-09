package com.betterhv.note.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect

/**
 * Draws vector strokes with Android's Canvas (Skia CPU), replacing Phase 0's
 * path through the vendor's native rasterizer.
 *
 * Why the change: the old path called HWPenEngine.drawPencil32() into an opaque
 * .so, then scanned the ENTIRE bitmap with per-pixel setPixel() to blit the
 * result -- O(width x height) per stroke regardless of how small the stroke was,
 * with no vector record kept afterwards. This draws the stroke outline directly
 * as a filled path and touches only the affected region.
 *
 * This is the one place in the ink pipeline that is allowed to know about
 * android.graphics: the model and geometry stay framework-free so they can be
 * unit tested and reused, and rendering consumes them (spec §93).
 */
class InkRenderer {

  // Reused across calls; allocating a Paint or Path per stroke would churn the
  // heap during continuous writing.
  private val paint = Paint().apply {
    isAntiAlias = true
    style = Paint.Style.FILL
  }
  private val path = Path()

  /** Draw a finished stroke. */
  fun drawStroke(canvas: Canvas, stroke: Stroke) {
    drawOutline(canvas, stroke.outline, displayColor(stroke.style))
  }

  /** Draw an in-progress stroke from its live outline. */
  fun drawOutline(canvas: Canvas, outline: StrokeOutline, color: Int) {
    if (outline.isEmpty) return
    buildPath(outline)
    paint.color = color
    canvas.drawPath(path, paint)
  }

  /** Build the compound swept-disc coverage shared by all three brushes. */
  private fun buildPath(outline: StrokeOutline) {
    path.rewind()
    for (offset in outline.bodies.indices step 8) {
      path.moveTo(outline.bodies[offset], outline.bodies[offset + 1])
      path.lineTo(outline.bodies[offset + 2], outline.bodies[offset + 3])
      path.lineTo(outline.bodies[offset + 4], outline.bodies[offset + 5])
      path.lineTo(outline.bodies[offset + 6], outline.bodies[offset + 7])
      path.close()
    }
    for (offset in outline.discs.indices step 3) {
      val radius = outline.discs[offset + 2]
      if (radius > 0f) {
        path.addCircle(outline.discs[offset], outline.discs[offset + 1], radius, Path.Direction.CW)
      }
    }
  }

  private fun displayColor(style: PenStyle): Int = when (style.penType) {
    PenType.Pencil -> colorWithOpacity(style.color, PENCIL_OPACITY)
    PenType.NormalPen, PenType.Marker -> style.color
  }

  private fun colorWithOpacity(color: Int, opacity: Float): Int {
    val sourceAlpha = (color ushr 24) and 0xFF
    val alpha = (sourceAlpha * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
    return (color and 0x00FFFFFF) or (alpha shl 24)
  }

  companion object {
    /**
     * [bounds] already encloses the full swept-disc shape, so only antialiasing
     * padding is required here.
     */
    fun dirtyRect(bounds: Bounds): Rect {
      val inflated = bounds.inflate(ANTIALIAS_MARGIN)
      return Rect(
        kotlin.math.floor(inflated.left).toInt(),
        kotlin.math.floor(inflated.top).toInt(),
        kotlin.math.ceil(inflated.right).toInt(),
        kotlin.math.ceil(inflated.bottom).toInt(),
      )
    }

    private const val ANTIALIAS_MARGIN = 2.0f
    private const val PENCIL_OPACITY = 0.8f
  }
}
