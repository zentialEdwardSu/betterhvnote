package com.betterhv.note.ink

import kotlin.math.sqrt

/** Axis-aligned bounds in page coordinates. Framework-free stand-in for a Rect. */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  val width: Float get() = right - left
  val height: Float get() = bottom - top

  /** Grow on all sides -- used for dirty-rect margins (§28). */
  fun inflate(margin: Float): Bounds = Bounds(left - margin, top - margin, right + margin, bottom + margin)

  fun intersects(other: Bounds): Boolean = left <= other.right && other.left <= right &&
    top <= other.bottom && other.top <= bottom

  fun union(other: Bounds): Bounds = Bounds(
    minOf(left, other.left),
    minOf(top, other.top),
    maxOf(right, other.right),
    maxOf(bottom, other.bottom),
  )

  companion object {
    fun of(points: List<InkPoint>): Bounds {
      if (points.isEmpty()) return Bounds(0f, 0f, 0f, 0f)
      var l = Float.MAX_VALUE
      var t = Float.MAX_VALUE
      var r = -Float.MAX_VALUE
      var b = -Float.MAX_VALUE
      for (p in points) {
        if (p.x < l) l = p.x
        if (p.y < t) t = p.y
        if (p.x > r) r = p.x
        if (p.y > b) b = p.y
      }
      return Bounds(l, t, r, b)
    }
  }
}

/**
 * Outline of a variable-width stroke. Spec §23.
 *
 * Stored as flat float arrays (x0,y0,x1,y1,...) rather than point objects
 * because this is regenerated on every live-ink update; at 10k+ strokes per
 * page (§4.2) the per-object allocation overhead of a List<PointF> is exactly
 * what §72 warns against.
 *
 * The two sides are kept separate so the renderer can walk `left` forward and
 * `right` backward to form one closed polygon.
 */
class StrokeOutline(val left: FloatArray, val right: FloatArray, val bounds: Bounds) {
  val pointCount: Int get() = left.size / 2
  val isEmpty: Boolean get() = pointCount == 0
}

/**
 * Builds the stroke outline from a centerline.
 *
 * For each point: tangent Ti = Pi+1 - Pi-1 (central difference, one-sided at the
 * ends), normal Ni = (-Ty, Tx)/|T|, half-width wi/2 from the pressure curve, so
 * Li = Pi + (wi/2)Ni and Ri = Pi - (wi/2)Ni.
 *
 * Two cases the naive formula does not cover:
 *  - Degenerate tangent (a duplicate/stationary sample) would divide by zero.
 *    We carry the previous valid normal forward instead, which keeps the ribbon
 *    continuous rather than collapsing it to a spike.
 *  - A single point (a dot -- tap without movement) has no tangent at all. We
 *    emit a small square quad so a deliberate dot still renders; a zero-area
 *    outline would silently swallow it.
 */
object StrokeGeometry {

  fun build(points: List<InkPoint>, style: PenStyle): StrokeOutline = buildRange(points, 0, style)

  /**
   * Build the outline for just the points from [fromIndex] to the end, but
   * compute every point's tangent from its TRUE neighbors in the full [all]
   * list -- `all[i-1]`/`all[i+1]`, one-sided only at the actual stroke ends.
   *
   * This is what makes incremental (tail) rendering seam-consistent. The
   * tangent at index i is a function of i and its neighbors in `all` ALONE:
   * it does not depend on [fromIndex]. So a point emitted here is offset in
   * exactly the same direction, by exactly the same width, as it would be by
   * a full-stroke [build] over the same `all` -- no width/curvature jump
   * where one tail segment meets the next. The old approach passed a fresh
   * `subList` to [build], which forced the sublist's first point onto a
   * one-sided tangent even when it was an interior stroke point, and that
   * mismatch is the visible seam/thickness kink (see plan doc §4a).
   *
   * [build] is just this with fromIndex 0.
   */
  fun buildRange(all: List<InkPoint>, fromIndex: Int, style: PenStyle): StrokeOutline {
    if (all.isEmpty()) {
      return StrokeOutline(FloatArray(0), FloatArray(0), Bounds(0f, 0f, 0f, 0f))
    }
    if (all.size == 1) return buildDot(all[0], style)

    val n = all.size
    val start = fromIndex.coerceIn(0, n - 1)
    val emitted = n - start
    val left = FloatArray(emitted * 2)
    val right = FloatArray(emitted * 2)

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE

    // Reused across iterations so a degenerate tangent can inherit it. When
    // start > 0 we still seed this from the point just before `start` so an
    // immediate degenerate tangent at the seam reuses a real prior normal
    // rather than the arbitrary fallback, keeping continuity with the
    // already-drawn segment.
    var normalX = 0.0f
    var normalY = 0.0f
    var hasNormal = false
    if (start > 0) {
      val seed = seedNormal(all, start - 1)
      if (seed != null) {
        normalX = seed[0]
        normalY = seed[1]
        hasNormal = true
      }
    }

    for (i in start until n) {
      val p = all[i]

      val tx: Float
      val ty: Float
      when (i) {
        0 -> {
          tx = all[1].x - p.x
          ty = all[1].y - p.y
        }

        n - 1 -> {
          tx = p.x - all[n - 2].x
          ty = p.y - all[n - 2].y
        }

        else -> {
          tx = all[i + 1].x - all[i - 1].x
          ty = all[i + 1].y - all[i - 1].y
        }
      }

      val length = sqrt(tx * tx + ty * ty)
      if (length > 1e-6f) {
        normalX = -ty / length
        normalY = tx / length
        hasNormal = true
      } else if (!hasNormal) {
        // First point is degenerate and there is no previous normal to
        // reuse; pick an arbitrary but consistent orientation.
        normalX = 0.0f
        normalY = 1.0f
        hasNormal = true
      }

      val halfWidth = style.widthAt(p.pressure.coerceIn(0.0f, 1.0f)) * 0.5f
      val offsetX = normalX * halfWidth
      val offsetY = normalY * halfWidth

      val lx = p.x + offsetX
      val ly = p.y + offsetY
      val rx = p.x - offsetX
      val ry = p.y - offsetY

      val idx = (i - start) * 2
      left[idx] = lx
      left[idx + 1] = ly
      right[idx] = rx
      right[idx + 1] = ry

      if (lx < minX) minX = lx
      if (rx < minX) minX = rx
      if (ly < minY) minY = ly
      if (ry < minY) minY = ry
      if (lx > maxX) maxX = lx
      if (rx > maxX) maxX = rx
      if (ly > maxY) maxY = ly
      if (ry > maxY) maxY = ry
    }

    return StrokeOutline(left, right, Bounds(minX, minY, maxX, maxY))
  }

  /**
   * The unit normal at index [i] using full-list neighbors, or null if the
   * tangent there is degenerate. Used only to seed the carried-normal state
   * when a tail render starts partway into the stroke.
   */
  private fun seedNormal(all: List<InkPoint>, i: Int): FloatArray? {
    val n = all.size
    val tx: Float
    val ty: Float
    when (i) {
      0 -> {
        tx = all[1].x - all[0].x
        ty = all[1].y - all[0].y
      }

      n - 1 -> {
        tx = all[n - 1].x - all[n - 2].x
        ty = all[n - 1].y - all[n - 2].y
      }

      else -> {
        tx = all[i + 1].x - all[i - 1].x
        ty = all[i + 1].y - all[i - 1].y
      }
    }
    val length = sqrt(tx * tx + ty * ty)
    if (length <= 1e-6f) return null
    return floatArrayOf(-ty / length, tx / length)
  }

  private fun buildDot(point: InkPoint, style: PenStyle): StrokeOutline {
    val halfWidth = style.widthAt(point.pressure.coerceIn(0.0f, 1.0f)) * 0.5f
    val left = floatArrayOf(
      point.x - halfWidth,
      point.y - halfWidth,
      point.x + halfWidth,
      point.y - halfWidth,
    )
    val right = floatArrayOf(
      point.x - halfWidth,
      point.y + halfWidth,
      point.x + halfWidth,
      point.y + halfWidth,
    )
    val bounds = Bounds(
      point.x - halfWidth,
      point.y - halfWidth,
      point.x + halfWidth,
      point.y + halfWidth,
    )
    return StrokeOutline(left, right, bounds)
  }
}
