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
    private val pencilPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    /** Draw a finished stroke. */
    fun drawStroke(canvas: Canvas, stroke: Stroke) {
        when (stroke.style.penType) {
            PenType.Pencil -> drawPencil(canvas, stroke)
            PenType.Marker -> drawMarker(canvas, stroke)
            PenType.NormalPen -> drawOutline(canvas, stroke.outline, stroke.style.color)
        }
    }

    /** Draw an in-progress stroke from its live outline. */
    fun drawOutline(canvas: Canvas, outline: StrokeOutline, color: Int) {
        if (outline.isEmpty) return
        buildPath(outline)
        paint.color = color
        canvas.drawPath(path, paint)
    }

    /**
     * Walk the left edge forward and the right edge backward to close the
     * ribbon into a single fillable polygon.
     */
    private fun buildPath(outline: StrokeOutline) {
        path.rewind()
        val left = outline.left
        val right = outline.right
        val n = outline.pointCount

        path.moveTo(left[0], left[1])
        for (i in 1 until n) {
            path.lineTo(left[i * 2], left[i * 2 + 1])
        }
        for (i in n - 1 downTo 0) {
            path.lineTo(right[i * 2], right[i * 2 + 1])
        }
        path.close()
    }

    /**
     * Marker has a pressure-independent width, so a stroked centerline is both
     * simpler and more robust than closing a filled ribbon. In particular it
     * gives the same round caps as the ROM overlay and avoids the crossed-edge
     * spikes that a very wide ribbon can develop near a short final segment.
     */
    private fun drawMarker(canvas: Canvas, stroke: Stroke) {
        val points = stroke.points
        if (points.isEmpty()) return

        markerPaint.color = stroke.style.color
        markerPaint.strokeWidth = stroke.style.widthAt(1f)
        if (points.size == 1) {
            canvas.drawPoint(points[0].x, points[0].y, markerPaint)
            return
        }

        path.rewind()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }
        canvas.drawPath(path, markerPaint)
    }

    /**
     * Pencil is intentionally rendered from its centerline instead of as one opaque ribbon.
     * Pressure controls both segment width and opacity, approximating hvNote's light graphite
     * output while keeping the vector record and redraw deterministic.
     */
    private fun drawPencil(canvas: Canvas, stroke: Stroke) {
        val points = stroke.points
        if (points.isEmpty()) return
        if (points.size == 1) {
            val pressure = points[0].pressure.coerceIn(0f, 1f)
            pencilPaint.strokeWidth = stroke.style.widthAt(pressure)
            pencilPaint.color = colorWithOpacity(stroke.style.color, pencilOpacity(pressure))
            canvas.drawPoint(points[0].x, points[0].y, pencilPaint)
            return
        }

        for (i in 1 until points.size) {
            val from = points[i - 1]
            val to = points[i]
            val pressure = ((from.pressure + to.pressure) * 0.5f).coerceIn(0f, 1f)
            pencilPaint.strokeWidth = stroke.style.widthAt(pressure)
            pencilPaint.color = colorWithOpacity(stroke.style.color, pencilOpacity(pressure))
            canvas.drawLine(from.x, from.y, to.x, to.y, pencilPaint)
        }
    }

    private fun pencilOpacity(pressure: Float): Float = 0.35f + 0.45f * pressure

    private fun colorWithOpacity(color: Int, opacity: Float): Int {
        val sourceAlpha = (color ushr 24) and 0xFF
        val alpha = (sourceAlpha * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    companion object {
        /**
         * Dirty rect for a stroke's outline. Spec §28: pad by half the max pen
         * width plus a margin for anti-aliasing and the panel's own update
         * granularity, otherwise the edge of a stroke can be left un-repainted.
         */
        fun dirtyRect(bounds: Bounds, style: PenStyle): Rect {
            val margin = style.maxWidth * 0.5f + ANTIALIAS_MARGIN
            val inflated = bounds.inflate(margin)
            return Rect(
                kotlin.math.floor(inflated.left).toInt(),
                kotlin.math.floor(inflated.top).toInt(),
                kotlin.math.ceil(inflated.right).toInt(),
                kotlin.math.ceil(inflated.bottom).toInt()
            )
        }

        private const val ANTIALIAS_MARGIN = 2.0f
    }
}
