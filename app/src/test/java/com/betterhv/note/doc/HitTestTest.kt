package com.betterhv.note.doc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HitTestTest {

    @Test
    fun `point on the segment has zero distance`() {
        val d2 = HitTest.pointSegmentDistanceSquared(5f, 0f, 0f, 0f, 10f, 0f)
        assertTrue(d2 < 1e-4f)
    }

    @Test
    fun `point beyond the segment end clamps to the endpoint`() {
        val d2 = HitTest.pointSegmentDistanceSquared(20f, 0f, 0f, 0f, 10f, 0f)
        assertTrue(kotlin.math.abs(d2 - 100f) < 1e-3f)
    }

    @Test
    fun `degenerate zero-length segment falls back to point distance`() {
        val d2 = HitTest.pointSegmentDistanceSquared(3f, 4f, 0f, 0f, 0f, 0f)
        assertTrue(kotlin.math.abs(d2 - 25f) < 1e-3f)
    }

    @Test
    fun `point inside a square polygon is inside`() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertTrue(HitTest.pointInPolygon(5f, 5f, square))
    }

    @Test
    fun `point outside a square polygon is outside`() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertFalse(HitTest.pointInPolygon(50f, 50f, square))
    }

    @Test
    fun `degenerate polygon with fewer than 3 points is never inside`() {
        val line = floatArrayOf(0f, 0f, 10f, 10f)
        assertFalse(HitTest.pointInPolygon(5f, 5f, line))
    }

    @Test
    fun `segment crossing polygon is detected even when endpoints are outside`() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertTrue(HitTest.segmentIntersectsPolygon(-5f, 5f, 15f, 5f, square))
    }

    @Test
    fun `segment outside polygon does not intersect`() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertFalse(HitTest.segmentIntersectsPolygon(-5f, 20f, 15f, 20f, square))
    }

    @Test
    fun `segment touching polygon boundary counts as intersection`() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertTrue(HitTest.segmentIntersectsPolygon(-5f, 0f, 5f, 0f, square))
    }
}
