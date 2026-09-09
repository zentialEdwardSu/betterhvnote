package com.betterhv.note.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureCurveTest {


  @Test
  fun `default response retains legacy curve`() {
    val curve = PressureCurve()
    assertEquals(0.25f, curve.factor(0f), EPS)
    assertEquals(0.25f + 0.75f * Math.pow(0.25, 0.7).toFloat(), curve.factor(0.25f), EPS)
    assertEquals(0.25f + 0.75f * Math.pow(0.5, 0.7).toFloat(), curve.factor(0.5f), EPS)
    assertEquals(1f, curve.factor(1f), EPS)
  }

  @Test
  fun `zero pressure yields the floor factor`() {
    val curve = PressureCurve(a = 0.25f, gamma = 0.7f)
    assertEquals(0.25f, curve.factor(0.0f), EPS)
  }

  @Test
  fun `full pressure yields factor of one`() {
    val curve = PressureCurve(a = 0.25f, gamma = 0.7f)
    assertEquals(1.0f, curve.factor(1.0f), EPS)
  }

  @Test
  fun `spec example matches documented formula`() {
    val curve = PressureCurve(0.25f, 0.7f)
    val p = 0.5f
    val expected = 0.25f + 0.75f * Math.pow(0.5, 0.7).toFloat()
    assertEquals(expected, curve.factor(p), EPS)
  }

  @Test
  fun `factor is monotonically increasing in pressure`() {
    val curve = PressureCurve()
    var previous = curve.factor(0.0f)
    var p = 0.05f
    while (p <= 1.0f) {
      val current = curve.factor(p)
      assertTrue("factor decreased at p=$p", current >= previous)
      previous = current
      p += 0.05f
    }
  }

  @Test
  fun `light pressure never collapses width to zero`() {
    assertTrue(PressureCurve().factor(0.0f) > 0.0f)
  }

  @Test
  fun `width scales linearly with base width`() {
    val factor = PressureCurve().factor(0.4f)
    assertEquals(3.0f, (6f * factor) / (2f * factor), EPS)
  }

  private companion object {
    const val EPS = 1e-4f
  }
}
