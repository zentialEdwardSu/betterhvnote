package com.betterhv.note.ink

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Path simplification applied once at pen-up (spec §22). Never on the input hot
 * path -- this runs after the stroke is finished, where a few milliseconds are
 * affordable.
 */
interface StrokeSimplifier {
    fun simplify(points: List<InkPoint>): List<InkPoint>
}

/** Keeps everything. Useful for tests and for measuring what simplification costs. */
class NoopSimplifier : StrokeSimplifier {
    override fun simplify(points: List<InkPoint>): List<InkPoint> = points
}

/**
 * Ramer-Douglas-Peucker, extended to respect pressure.
 *
 * Plain RDP only measures perpendicular distance from the chord, so it happily
 * discards a point that sits on a straight line even if the pen was pressing
 * much harder there -- which would flatten the stroke's width variation. Spec
 * §22 requires shape, pressure variation, and timing all survive, so a point is
 * retained when EITHER its positional deviation or its pressure deviation from
 * the linear interpolation across the chord exceeds tolerance.
 *
 * Timestamps ride along on whichever points are kept; endpoints are always
 * preserved, so stroke start/end times are exact.
 *
 * Implemented iteratively with an explicit stack: a pathological stroke can
 * carry tens of thousands of points and recursive RDP would risk blowing the
 * stack on those.
 */
class RamerDouglasPeuckerSimplifier(
    private val positionTolerance: Float = 0.6f,
    private val pressureTolerance: Float = 0.06f
) : StrokeSimplifier {

    override fun simplify(points: List<InkPoint>): List<InkPoint> {
        val n = points.size
        if (n <= 2) return points

        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true

        // Segments pending evaluation, as flat (first, last) pairs. A plain int
        // stack avoids allocating a holder object per subdivision.
        val stack = ArrayList<Int>()
        stack.add(0)
        stack.add(n - 1)

        while (stack.isNotEmpty()) {
            val last = stack.removeAt(stack.size - 1)
            val first = stack.removeAt(stack.size - 1)
            if (last <= first + 1) continue

            val start = points[first]
            val end = points[last]

            val dx = end.x - start.x
            val dy = end.y - start.y
            val chordLength = sqrt(dx * dx + dy * dy)
            val pressureSpan = end.pressure - start.pressure

            var worstIndex = -1
            var worstScore = 0.0f

            for (i in (first + 1) until last) {
                val p = points[i]

                val positionalDeviation = if (chordLength < 1e-6f) {
                    // Degenerate chord (start == end, e.g. a closed loop): fall back
                    // to straight-line distance from the shared endpoint.
                    val ex = p.x - start.x
                    val ey = p.y - start.y
                    sqrt(ex * ex + ey * ey)
                } else {
                    // Perpendicular distance via the 2D cross product.
                    abs(dx * (start.y - p.y) - (start.x - p.x) * dy) / chordLength
                }

                // Where this point falls along the chord, so pressure can be
                // compared against the value a linear interpolation would give.
                val t = if (chordLength < 1e-6f) {
                    0.0f
                } else {
                    (((p.x - start.x) * dx + (p.y - start.y) * dy) /
                        (chordLength * chordLength)).coerceIn(0.0f, 1.0f)
                }
                val expectedPressure = start.pressure + pressureSpan * t
                val pressureDeviation = abs(p.pressure - expectedPressure)

                // Normalize both deviations against their own tolerance so they
                // are comparable, then take whichever is more violated.
                val score = maxOf(
                    positionalDeviation / positionTolerance,
                    pressureDeviation / pressureTolerance
                )

                if (score > worstScore) {
                    worstScore = score
                    worstIndex = i
                }
            }

            if (worstIndex >= 0 && worstScore > 1.0f) {
                keep[worstIndex] = true
                stack.add(first)
                stack.add(worstIndex)
                stack.add(worstIndex)
                stack.add(last)
            }
        }

        val result = ArrayList<InkPoint>(n)
        for (i in 0 until n) {
            if (keep[i]) result.add(points[i])
        }
        return result
    }
}
