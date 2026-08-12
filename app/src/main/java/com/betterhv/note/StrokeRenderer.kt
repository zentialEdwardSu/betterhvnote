package com.betterhv.note

import android.graphics.Bitmap
import android.os.Build
import hanvon.aebr.penengine.HWPenEngine
import java.util.Arrays

/**
 * Distilled port of PslOp.drawPoints(..., mode=2, ...) from hvNote 7.16 —
 * the E-ink stroke commit path. The ROM renders live ink; on pen-up we
 * persist the final points into our bitmap via the native pen engine.
 *
 * Input `raw` is the HvPenDrawListener callback array:
 *   raw[0] = point count N, then N triples (x, y, pressure).
 */
object StrokeRenderer {

    /** density arg: DENSITY_3576 on API 34 (0x22), else DENSITY_3566. Matches PslOp. */
    private fun density(): Int =
        if (Build.VERSION.SDK_INT == 0x22) HWPenEngine.DENSITY_3576 else HWPenEngine.DENSITY_3566

    /**
     * Renders one stroke into [bmp] using HWPenEngine.drawPencil32 (pen mode) or
     * pixel-clearing (eraser mode), then blits the result onto the bitmap.
     * Mode: 0=PEN (drawPencil32), 1=ERASER_TRACE (TBD), 2=ERASER_PIXEL (clear rect).
     */
    fun commitStroke(bmp: Bitmap, raw: FloatArray, color: Int, mode: Int): Boolean {
        val n = raw[0].toInt()
        if (n <= 1) return false

        return when (mode) {
            0 -> commitStrokePen(bmp, raw, color)
            2 -> commitStrokeEraserPixel(bmp, raw)
            else -> {
                // Mode 1 (ERASER_TRACE) deferred to Phase 2
                false
            }
        }
    }

    private fun commitStrokePen(bmp: Bitmap, raw: FloatArray, color: Int): Boolean {
        val n = raw[0].toInt()
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        Arrays.fill(pixels, 0)

        val pts = FloatArray((n - 1) * 3)
        var i = 1
        while (i < n) {
            val dst = (i - 1) * 3
            val src = i * 3
            pts[dst] = raw[src + 1]
            pts[dst + 1] = raw[src + 2]
            pts[dst + 2] = raw[src + 3] / 2.0f
            i++
        }

        val mode = IntArray(4)
        HWPenEngine.drawPencil32(w, h, density(), color, pixels, 0, pts, mode)

        // Blit non-zero pixels into bitmap
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = pixels[y * w + x]
                if (p != 0) bmp.setPixel(x, y, p)
                x++
            }
            y++
        }
        return true
    }

    private fun commitStrokeEraserPixel(bmp: Bitmap, raw: FloatArray): Boolean {
        val n = raw[0].toInt()
        val eraserWidth = 12
        var i = 1
        while (i < n) {
            val x = raw[i * 3 + 1].toInt()
            val y = raw[i * 3 + 2].toInt()
            // Clear rect of eraserWidth × eraserWidth centered on (x, y)
            val x0 = (x - eraserWidth / 2).coerceIn(0, bmp.width - 1)
            val y0 = (y - eraserWidth / 2).coerceIn(0, bmp.height - 1)
            val x1 = (x + eraserWidth / 2).coerceIn(0, bmp.width - 1)
            val y1 = (y + eraserWidth / 2).coerceIn(0, bmp.height - 1)
            var ey = y0
            while (ey <= y1) {
                var ex = x0
                while (ex <= x1) {
                    bmp.setPixel(ex, ey, android.graphics.Color.TRANSPARENT)
                    ex++
                }
                ey++
            }
            i++
        }
        return true
    }
}
