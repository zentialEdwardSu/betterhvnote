package com.betterhv.note.ink

import kotlin.math.pow

/**
 * Normalized pressure response curve. Spec §14:
 *
 *     f(p) = a + (1 - a) * p^gamma        p in [0,1], f(p) in [a,1]
 *
 * This is the shape function only; the absolute width comes from
 * [PenStyle.baseWidth] (w = w0 * s(type) * f(p)). [renderedWidthScale] models
 * the visual response of the vendor brush represented by that nominal width;
 * the pressure curve remains a separate, swappable component -- the spec
 * explicitly forbids baking this formula into the renderer.
 *
 * `a` keeps a floor under the width so light strokes do not vanish entirely.
 */
data class PressureCurve(val a: Float = 0.25f, val gamma: Float = 0.7f) {
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

  /**
   * Visual scale for the selected brush engine. On N10Pro the ROM's normal
   * (graffiti) pen renders a nominal width at about one third of its numeric
   * value. Marker and pencil use their nominal width directly.
   */
  val renderedWidthScale: Float
    get() = when (penType) {
      PenType.NormalPen -> NORMAL_PEN_RENDERED_WIDTH_SCALE
      PenType.Pencil, PenType.Marker -> 1.0f
    }

  /** Spec §14 with the brush-specific visual calibration. */
  fun widthAt(pressure: Float): Float = baseWidth * renderedWidthScale * pressureCurve.factor(pressure)

  /** Upper bound on width, used for dirty-rect margins (§28). */
  val maxWidth: Float get() = baseWidth * renderedWidthScale

  companion object {
    const val COLOR_BLACK: Int = 0xFF000000.toInt()

    /** Calibrated from minimum/maximum normal-pen live-vs-materialized comparisons. */
    private const val NORMAL_PEN_RENDERED_WIDTH_SCALE = 0.34f
  }
}
