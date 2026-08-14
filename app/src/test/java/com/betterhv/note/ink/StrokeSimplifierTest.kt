package com.betterhv.note.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeSimplifierTest {

    private val simplifier = RamerDouglasPeuckerSimplifier(
        positionTolerance = 0.5f,
        pressureTolerance = 0.05f
    )

    @Test
    fun `endpoints are always preserved`() {
        val points = (0..20).map { point(it.toFloat(), 0f) }
        val result = simplifier.simplify(points)
        assertEquals(points.first(), result.first())
        assertEquals(points.last(), result.last())
    }

    @Test
    fun `collinear run collapses to its endpoints`() {
        val points = (0..20).map { point(it.toFloat(), 0f) }
        val result = simplifier.simplify(points)
        assertEquals(2, result.size)
    }

    @Test
    fun `corner is retained`() {
        // An L shape: the vertex carries the whole shape and must survive.
        val points = mutableListOf<InkPoint>()
        for (i in 0..10) points.add(point(i.toFloat(), 0f))
        for (i in 1..10) points.add(point(10f, i.toFloat()))

        val result = simplifier.simplify(points)

        assertTrue("corner vertex was dropped", result.any { it.x == 10f && it.y == 0f })
    }

    @Test
    fun `pressure spike on a straight line is retained`() {
        // Plain RDP would drop this point because it sits exactly on the chord;
        // spec §22 requires pressure variation to survive simplification.
        val points = listOf(
            point(0f, 0f, 0.2f),
            point(1f, 0f, 0.2f),
            point(2f, 0f, 0.95f),
            point(3f, 0f, 0.2f),
            point(4f, 0f, 0.2f)
        )
        val result = simplifier.simplify(points)
        assertTrue("pressure spike was flattened", result.any { it.pressure > 0.9f })
    }

    @Test
    fun `timestamps ride along on retained points`() {
        val points = listOf(
            InkPoint(0f, 0f, 0.5f, 100L),
            InkPoint(5f, 5f, 0.5f, 200L),
            InkPoint(10f, 0f, 0.5f, 300L)
        )
        val result = simplifier.simplify(points)
        assertEquals(100L, result.first().timestamp)
        assertEquals(300L, result.last().timestamp)
    }

    @Test
    fun `degenerate inputs pass through untouched`() {
        val empty = emptyList<InkPoint>()
        assertSame(empty, simplifier.simplify(empty))

        val single = listOf(point(1f, 1f))
        assertSame(single, simplifier.simplify(single))

        val pair = listOf(point(1f, 1f), point(2f, 2f))
        assertSame(pair, simplifier.simplify(pair))
    }

    @Test
    fun `deeply subdivided stroke does not overflow the stack`() {
        // Iterative RDP: a recursive implementation risks StackOverflowError on a
        // path this long where every point must be kept.
        val points = (0..20_000).map {
            point(it.toFloat(), if (it % 2 == 0) 0f else 40f)
        }
        val result = simplifier.simplify(points)
        assertTrue(result.size > 2)
    }

    @Test
    fun `simplification actually reduces a dense noisy path`() {
        val points = (0..500).map { i ->
            // Gentle arc with sub-tolerance jitter.
            val x = i.toFloat()
            val y = x * x / 500f + (if (i % 3 == 0) 0.1f else -0.1f)
            point(x, y)
        }
        val result = simplifier.simplify(points)
        assertTrue("expected reduction, got ${result.size} of ${points.size}", result.size < points.size)
    }

    private fun point(x: Float, y: Float, pressure: Float = 0.5f) =
        InkPoint(x = x, y = y, pressure = pressure, timestamp = 1_000L)
}
