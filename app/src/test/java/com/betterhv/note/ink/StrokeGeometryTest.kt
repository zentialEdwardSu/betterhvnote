package com.betterhv.note.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class StrokeGeometryTest {

    private val style = PenStyle(baseWidth = 4.0f, pressureCurve = PressureCurve(0.25f, 0.7f))

    @Test
    fun `outline has one pair of boundary points per centerline point`() {
        val points = (0..5).map { point(it.toFloat() * 10f, 0f) }
        val outline = StrokeGeometry.build(points, style)
        assertEquals(points.size, outline.pointCount)
        assertEquals(points.size * 2, outline.left.size)
        assertEquals(points.size * 2, outline.right.size)
    }

    @Test
    fun `horizontal stroke offsets perpendicular in y`() {
        val points = listOf(point(0f, 100f), point(10f, 100f), point(20f, 100f))
        val outline = StrokeGeometry.build(points, style)

        // Middle point: normal must be vertical, so x is unchanged and the two
        // sides straddle the centerline.
        val lx = outline.left[2]
        val ly = outline.left[3]
        val rx = outline.right[2]
        val ry = outline.right[3]

        assertEquals(10f, lx, EPS)
        assertEquals(10f, rx, EPS)
        assertTrue("sides did not straddle the centerline", (ly - 100f) * (ry - 100f) < 0f)
    }

    @Test
    fun `boundary separation equals the pressure-derived width`() {
        val pressure = 0.6f
        val points = listOf(
            point(0f, 0f, pressure),
            point(10f, 0f, pressure),
            point(20f, 0f, pressure)
        )
        val outline = StrokeGeometry.build(points, style)

        val separation = hypot(
            outline.left[2] - outline.right[2],
            outline.left[3] - outline.right[3]
        )
        assertEquals(style.widthAt(pressure), separation, EPS)
    }

    @Test
    fun `higher pressure produces a wider ribbon`() {
        fun separationAt(pressure: Float): Float {
            val points = listOf(
                point(0f, 0f, pressure),
                point(10f, 0f, pressure),
                point(20f, 0f, pressure)
            )
            val outline = StrokeGeometry.build(points, style)
            return hypot(
                outline.left[2] - outline.right[2],
                outline.left[3] - outline.right[3]
            )
        }
        assertTrue(separationAt(0.9f) > separationAt(0.2f))
    }

    @Test
    fun `duplicate samples do not produce NaN`() {
        // A zero-length tangent would divide by zero; the previous normal is
        // carried forward instead.
        val points = listOf(
            point(0f, 0f),
            point(10f, 0f),
            point(10f, 0f),
            point(10f, 0f),
            point(20f, 0f)
        )
        val outline = StrokeGeometry.build(points, style)
        for (v in outline.left) assertFalse("NaN in left boundary", v.isNaN())
        for (v in outline.right) assertFalse("NaN in right boundary", v.isNaN())
    }

    @Test
    fun `stroke of identical points does not produce NaN`() {
        val points = List(4) { point(7f, 7f) }
        val outline = StrokeGeometry.build(points, style)
        for (v in outline.left) assertFalse(v.isNaN())
        for (v in outline.right) assertFalse(v.isNaN())
    }

    @Test
    fun `single point renders as a dot with non-zero area`() {
        val outline = StrokeGeometry.build(listOf(point(5f, 5f, 1.0f)), style)
        assertTrue(outline.pointCount > 0)
        assertTrue("dot collapsed to zero width", outline.bounds.width > 0f)
        assertTrue("dot collapsed to zero height", outline.bounds.height > 0f)
    }

    @Test
    fun `marker bounds include round cap extension past endpoints`() {
        val markerStyle = PenStyle(
            baseWidth = 20f,
            pressureCurve = PressureCurve(a = 1f, gamma = 1f),
            penType = PenType.Marker
        )
        val stroke = Stroke(
            points = listOf(point(10f, 30f), point(50f, 30f)),
            style = markerStyle
        )

        assertEquals(0f, stroke.bounds.left, EPS)
        assertEquals(60f, stroke.bounds.right, EPS)
        assertEquals(20f, stroke.bounds.top, EPS)
        assertEquals(40f, stroke.bounds.bottom, EPS)
    }

    @Test
    fun `empty input yields an empty outline`() {
        val outline = StrokeGeometry.build(emptyList(), style)
        assertTrue(outline.isEmpty)
    }

    @Test
    fun `bounds enclose every boundary point`() {
        val points = (0..10).map { point(it.toFloat() * 5f, kotlin.math.sin(it.toFloat()) * 20f) }
        val outline = StrokeGeometry.build(points, style)
        val b = outline.bounds
        for (i in 0 until outline.pointCount) {
            assertTrue(outline.left[i * 2] >= b.left - EPS)
            assertTrue(outline.left[i * 2] <= b.right + EPS)
            assertTrue(outline.left[i * 2 + 1] >= b.top - EPS)
            assertTrue(outline.left[i * 2 + 1] <= b.bottom + EPS)
            assertTrue(outline.right[i * 2] >= b.left - EPS)
            assertTrue(outline.right[i * 2] <= b.right + EPS)
        }
    }

    @Test
    fun `bounds inflate grows symmetrically`() {
        val b = Bounds(10f, 20f, 30f, 40f).inflate(5f)
        assertEquals(5f, b.left, EPS)
        assertEquals(15f, b.top, EPS)
        assertEquals(35f, b.right, EPS)
        assertEquals(45f, b.bottom, EPS)
    }

    @Test
    fun `diagonal stroke normal is perpendicular to the tangent`() {
        val points = listOf(point(0f, 0f), point(10f, 10f), point(20f, 20f))
        val outline = StrokeGeometry.build(points, style)

        // Vector across the ribbon at the middle point, dotted with the tangent
        // (1,1), should be ~0 if the offset really is perpendicular.
        val acrossX = outline.left[2] - outline.right[2]
        val acrossY = outline.left[3] - outline.right[3]
        val dot = acrossX * 1f + acrossY * 1f
        assertTrue("offset not perpendicular to tangent (dot=$dot)", abs(dot) < 1e-3f)
    }

    @Test
    fun `buildRange matches the tail slice of a full build at an interior seam`() {
        // A curved stroke so tangents genuinely vary point-to-point: this is
        // where the old subList approach diverged, since the seam point got a
        // one-sided tangent instead of its true central-difference one.
        val points = (0..12).map { point(it.toFloat() * 6f, kotlin.math.sin(it * 0.5f) * 25f) }
        val full = StrokeGeometry.build(points, style)

        val from = 5
        val range = StrokeGeometry.buildRange(points, from, style)

        // The range must reproduce the full build's boundary points from `from`
        // onward, vertex-for-vertex -- that identity is the seam guarantee.
        assertEquals(points.size - from, range.pointCount)
        for (i in from until points.size) {
            val r = (i - from) * 2
            val f = i * 2
            assertEquals("left x at $i", full.left[f], range.left[r], EPS)
            assertEquals("left y at $i", full.left[f + 1], range.left[r + 1], EPS)
            assertEquals("right x at $i", full.right[f], range.right[r], EPS)
            assertEquals("right y at $i", full.right[f + 1], range.right[r + 1], EPS)
        }
    }

    @Test
    fun `buildRange from zero equals a full build`() {
        val points = (0..8).map { point(it.toFloat() * 7f, kotlin.math.cos(it * 0.4f) * 15f) }
        val full = StrokeGeometry.build(points, style)
        val range = StrokeGeometry.buildRange(points, 0, style)

        assertEquals(full.pointCount, range.pointCount)
        for (i in full.left.indices) {
            assertEquals(full.left[i], range.left[i], EPS)
            assertEquals(full.right[i], range.right[i], EPS)
        }
    }

    @Test
    fun `buildRange seam point matches even adjacent to the tail end`() {
        // fromIndex at the last point: the emitted point is the stroke's end,
        // which uses a one-sided tangent in BOTH full and range builds, so they
        // must still agree.
        val points = (0..6).map { point(it.toFloat() * 8f, it.toFloat() * it.toFloat()) }
        val full = StrokeGeometry.build(points, style)
        val last = points.size - 1
        val range = StrokeGeometry.buildRange(points, last, style)

        assertEquals(1, range.pointCount)
        assertEquals(full.left[last * 2], range.left[0], EPS)
        assertEquals(full.left[last * 2 + 1], range.left[1], EPS)
        assertEquals(full.right[last * 2], range.right[0], EPS)
        assertEquals(full.right[last * 2 + 1], range.right[1], EPS)
    }

    @Test
    fun `buildRange clamps an out-of-range fromIndex without error`() {
        val points = (0..4).map { point(it.toFloat() * 10f, 0f) }
        val range = StrokeGeometry.buildRange(points, 99, style)
        // Clamped to the last point -> a single emitted vertex, no crash/NaN.
        assertEquals(1, range.pointCount)
        for (v in range.left) assertFalse(v.isNaN())
        for (v in range.right) assertFalse(v.isNaN())
    }

    private fun point(x: Float, y: Float, pressure: Float = 0.5f) =
        InkPoint(x = x, y = y, pressure = pressure, timestamp = 1_000L)

    private companion object {
        const val EPS = 1e-3f
    }
}
