package com.betterhv.note.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StrokeSmootherTest {

  @Test
  fun `first point passes through unchanged`() {
    // Nothing to average against yet; the filter must not lurch toward zero.
    val smoother = OneEuroSmoother()
    val p = InkPoint(100f, 200f, 0.5f, 1_000L)
    val out = smoother.smooth(p)
    assertEquals(100f, out.x, EPS)
    assertEquals(200f, out.y, EPS)
  }

  @Test
  fun `jitter is attenuated`() {
    val smoother = OneEuroSmoother()
    // A nib held still, with alternating noise on it.
    val noisy = listOf(0f, 2f, -2f, 2f, -2f, 2f, -2f)
    var last = 0f
    for ((i, dx) in noisy.withIndex()) {
      last = smoother.smooth(InkPoint(dx, 0f, 0.5f, 1_000L + i * 8L)).x
    }
    assertTrue("noise was not attenuated (got $last)", abs(last) < 2f)
  }

  @Test
  fun `output tracks a sustained move`() {
    // Smoothing must lag, not block: after enough samples the output has to
    // get most of the way to the input or the pen feels detached.
    val smoother = OneEuroSmoother()
    var out = 0f
    for (i in 0..60) {
      out = smoother.smooth(InkPoint(i * 10f, 0f, 0.5f, 1_000L + i * 8L)).x
    }
    assertTrue("filter failed to track motion (got $out, input 600)", out > 500f)
  }

  @Test
  fun `pressure stays in range`() {
    val smoother = OneEuroSmoother()
    for (p in listOf(0f, 1f, 0f, 1f, 0.5f)) {
      val out = smoother.smooth(InkPoint(0f, 0f, p, 1_000L))
      assertTrue(out.pressure in 0.0f..1.0f)
    }
  }

  @Test
  fun `reset clears filter state between strokes`() {
    val smoother = OneEuroSmoother()
    repeat(20) { smoother.smooth(InkPoint(500f, 500f, 0.5f, 1_000L + it * 8L)) }

    smoother.reset()

    // Post-reset the first point of the next stroke must pass through, not
    // be dragged toward the previous stroke's position.
    val out = smoother.smooth(InkPoint(10f, 10f, 0.5f, 2_000L))
    assertEquals(10f, out.x, EPS)
    assertEquals(10f, out.y, EPS)
  }

  @Test
  fun `noop smoother is a passthrough`() {
    val p = InkPoint(1f, 2f, 0.3f, 5L)
    assertEquals(p, NoopSmoother().smooth(p))
  }

  @Test
  fun `smoothing preserves timestamp`() {
    val out = OneEuroSmoother().smooth(InkPoint(1f, 2f, 0.3f, 4_242L))
    assertEquals(4_242L, out.timestamp)
  }

  @Test
  fun `smoothing actually changes a noisy stream`() {
    // Guard against accidentally wiring in a passthrough.
    val smoother = OneEuroSmoother()
    smoother.smooth(InkPoint(0f, 0f, 0.5f, 1_000L))
    val out = smoother.smooth(InkPoint(50f, 0f, 0.5f, 1_008L))
    assertNotEquals(50f, out.x)
  }

  private companion object {
    const val EPS = 1e-3f
  }
}
