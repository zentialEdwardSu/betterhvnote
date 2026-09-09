package com.betterhv.note.ink

import kotlin.math.pow

/**
 * Normalized pressure response curve. Spec §14:
 *
 *     f(p) = a + (1 - a) * p^gamma        p in [0,1], f(p) in [a,1]
 *
 * Retained as version-7 style metadata. The unified rendering path does not
 * apply it to the ROM width ratio; geometry uses the per-point scalar directly
 * through [PenStyle.widthAt].
 *
 * `a` keeps a floor under the width so light strokes do not vanish entirely.
 */
data class PressureCurve(
  val a: Float = 0.25f,
  val gamma: Float = 0.7f,
) {
  init {
    require(a in 0.0f..1.0f) { "a must be in [0,1], got $a" }
    require(gamma > 0.0f) { "gamma must be positive, got $gamma" }
  }

  fun factor(pressure: Float): Float {
    require(pressure in 0.0f..1.0f) { "pressure must be in [0,1], got $pressure" }
    return a + (1.0f - a) * pressure.pow(gamma)
  }
}

enum class PenType {
  NormalPen,
  Pencil,
  Marker,
}

/**
 * Stroke appearance. Spec §13. `color` is a plain packed ARGB int rather than
 * an android.graphics.Color reference so this package stays framework-free and
 * unit-testable; the document model keeps full RGBA and the display layer is
 * responsible for mapping it onto whatever the panel can actually show (§69).
 */
data class PenStyle(
  val baseWidth: Float = 2.5f,
  val color: Int = COLOR_BLACK,
  val pressureCurve: PressureCurve = PressureCurve(),
  val penType: PenType = PenType.NormalPen,
) {
  init {
    require(baseWidth > 0.0f) { "baseWidth must be positive, got $baseWidth" }
  }

  /** The sole width resolver used by geometry, rendering, export, and hit testing. */
  fun widthAt(point: InkPoint): Float = when (penType) {
    PenType.NormalPen -> if (point.pressure.isFinite()) baseWidth * point.pressure.coerceAtLeast(0f) else 0f
    PenType.Pencil, PenType.Marker -> baseWidth
  }

  companion object {
    const val COLOR_BLACK: Int = 0xFF000000.toInt()
  }
}
