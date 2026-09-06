package com.betterhv.note.doc

/**
 * Shared geometry for hit-testing. Point-segment distance is ported verbatim
 * from PenDrawView's original whole-stroke eraser check; point-in-polygon is
 * new, for lasso selection (spec §36-37).
 */
object HitTest {

  fun pointSegmentDistanceSquared(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val abx = bx - ax
    val aby = by - ay
    val lengthSquared = abx * abx + aby * aby
    val t = if (lengthSquared < 1e-6f) {
      0.0f
    } else {
      (((px - ax) * abx + (py - ay) * aby) / lengthSquared).coerceIn(0.0f, 1.0f)
    }
    val dx = px - (ax + abx * t)
    val dy = py - (ay + aby * t)
    return dx * dx + dy * dy
  }

  /** Whether any segment of [points] (a stroke's local-space points) comes within [radius] of [lx], [ly]. */
  fun strokeTouchesLocal(points: List<com.betterhv.note.ink.InkPoint>, lx: Float, ly: Float, radius: Float): Boolean {
    if (points.isEmpty()) return false
    if (points.size == 1) {
      val dx = points[0].x - lx
      val dy = points[0].y - ly
      return dx * dx + dy * dy <= radius * radius
    }
    val r2 = radius * radius
    for (i in 0 until points.size - 1) {
      if (pointSegmentDistanceSquared(lx, ly, points[i].x, points[i].y, points[i + 1].x, points[i + 1].y) <= r2) {
        return true
      }
    }
    return false
  }

  /**
   * Standard even-odd ray casting test. [polygon] is a flat (x0,y0,x1,y1,...)
   * array; not required to be explicitly closed (the edge from the last point
   * back to the first is implied).
   */
  fun pointInPolygon(px: Float, py: Float, polygon: FloatArray): Boolean {
    val n = polygon.size / 2
    if (n < 3) return false
    var inside = false
    var j = n - 1
    for (i in 0 until n) {
      val xi = polygon[i * 2]
      val yi = polygon[i * 2 + 1]
      val xj = polygon[j * 2]
      val yj = polygon[j * 2 + 1]
      if ((yi > py) != (yj > py)) {
        val intersectX = xi + (py - yi) / (yj - yi) * (xj - xi)
        if (px < intersectX) inside = !inside
      }
      j = i
    }
    return inside
  }

  /** Whether segment AB crosses or touches any edge of [polygon]. */
  fun segmentIntersectsPolygon(ax: Float, ay: Float, bx: Float, by: Float, polygon: FloatArray): Boolean {
    val n = polygon.size / 2
    if (n < 3) return false
    var j = n - 1
    for (i in 0 until n) {
      if (segmentsIntersect(
          ax,
          ay,
          bx,
          by,
          polygon[j * 2],
          polygon[j * 2 + 1],
          polygon[i * 2],
          polygon[i * 2 + 1],
        )
      ) {
        return true
      }
      j = i
    }
    return false
  }

  private fun segmentsIntersect(
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    cx: Float,
    cy: Float,
    dx: Float,
    dy: Float,
  ): Boolean {
    val abC = cross(ax, ay, bx, by, cx, cy)
    val abD = cross(ax, ay, bx, by, dx, dy)
    val cdA = cross(cx, cy, dx, dy, ax, ay)
    val cdB = cross(cx, cy, dx, dy, bx, by)

    if (((abC > EPSILON && abD < -EPSILON) || (abC < -EPSILON && abD > EPSILON)) &&
      ((cdA > EPSILON && cdB < -EPSILON) || (cdA < -EPSILON && cdB > EPSILON))
    ) {
      return true
    }
    return (kotlin.math.abs(abC) <= EPSILON && pointOnSegment(cx, cy, ax, ay, bx, by)) ||
      (kotlin.math.abs(abD) <= EPSILON && pointOnSegment(dx, dy, ax, ay, bx, by)) ||
      (kotlin.math.abs(cdA) <= EPSILON && pointOnSegment(ax, ay, cx, cy, dx, dy)) ||
      (kotlin.math.abs(cdB) <= EPSILON && pointOnSegment(bx, by, cx, cy, dx, dy))
  }

  private fun cross(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float =
    (bx - ax) * (py - ay) - (by - ay) * (px - ax)

  private fun pointOnSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Boolean =
    px >= minOf(ax, bx) - EPSILON && px <= maxOf(ax, bx) + EPSILON &&
      py >= minOf(ay, by) - EPSILON && py <= maxOf(ay, by) + EPSILON

  private const val EPSILON = 1e-4f
}
