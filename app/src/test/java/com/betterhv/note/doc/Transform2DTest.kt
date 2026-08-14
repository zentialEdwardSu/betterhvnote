package com.betterhv.note.doc

import com.betterhv.note.ink.Bounds
import org.junit.Assert.assertEquals
import org.junit.Test

class Transform2DTest {

    @Test
    fun `identity maps points to themselves`() {
        val p = Transform2D.IDENTITY.mapPoint(3.0f, 4.0f)
        assertEquals(3.0f, p[0], EPS)
        assertEquals(4.0f, p[1], EPS)
    }

    @Test
    fun `translate offsets a point`() {
        val t = Transform2D.translate(5.0f, -2.0f)
        val p = t.mapPoint(1.0f, 1.0f)
        assertEquals(6.0f, p[0], EPS)
        assertEquals(-1.0f, p[1], EPS)
    }

    @Test
    fun `scaleAbout leaves the center fixed`() {
        val t = Transform2D.scaleAbout(10.0f, 10.0f, 2.0f)
        val p = t.mapPoint(10.0f, 10.0f)
        assertEquals(10.0f, p[0], EPS)
        assertEquals(10.0f, p[1], EPS)
    }

    @Test
    fun `scaleAbout doubles distance from center`() {
        val t = Transform2D.scaleAbout(0.0f, 0.0f, 2.0f)
        val p = t.mapPoint(3.0f, 4.0f)
        assertEquals(6.0f, p[0], EPS)
        assertEquals(8.0f, p[1], EPS)
    }

    @Test
    fun `times composes so other applies first`() {
        val translate = Transform2D.translate(10.0f, 0.0f)
        val scale = Transform2D.scaleAbout(0.0f, 0.0f, 2.0f)
        // scale after translate: (x+10)*2
        val composed = scale.times(translate)
        val p = composed.mapPoint(0.0f, 0.0f)
        assertEquals(20.0f, p[0], EPS)
        assertEquals(0.0f, p[1], EPS)
    }

    @Test
    fun `invert round-trips an arbitrary point`() {
        val t = Transform2D.scaleAbout(5.0f, 5.0f, 3.0f).times(Transform2D.translate(2.0f, -3.0f))
        val inv = t.invert()!!
        val p = t.mapPoint(7.0f, -1.0f)
        val back = inv.mapPoint(p[0], p[1])
        assertEquals(7.0f, back[0], EPS)
        assertEquals(-1.0f, back[1], EPS)
    }

    @Test
    fun `invert returns null for a singular transform`() {
        val singular = Transform2D(a = 0f, b = 0f, c = 0f, d = 0f, tx = 0f, ty = 0f)
        assertEquals(null, singular.invert())
    }

    @Test
    fun `mapRect maps all four corners of a bounds`() {
        val t = Transform2D.scaleAbout(0.0f, 0.0f, 2.0f)
        val mapped = t.mapRect(Bounds(1.0f, 1.0f, 3.0f, 3.0f))
        assertEquals(2.0f, mapped.left, EPS)
        assertEquals(2.0f, mapped.top, EPS)
        assertEquals(6.0f, mapped.right, EPS)
        assertEquals(6.0f, mapped.bottom, EPS)
    }

    companion object {
        private const val EPS = 1e-4f
    }
}
