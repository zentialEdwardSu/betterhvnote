package com.betterhv.note.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behaviour under test here is the reason StrokeBuilder exists: the ROM
 * fires onPenTouchUpStatus() several times per physical gesture with no
 * correlation between calls, so the builder has to be the thing that reassembles
 * them into one stroke.
 */
class StrokeBuilderTest {

  private fun builder(
    filter: InputFilter = InputFilter(minDistance = 0.0f, minPressureDelta = 0.0f),
    smoother: StrokeSmoother = NoopSmoother(),
    simplifier: StrokeSimplifier = NoopSimplifier(),
  ) = StrokeBuilder(
    style = PenStyle(baseWidth = 3.0f),
    filter = filter,
    smoother = smoother,
    simplifier = simplifier,
  )

  @Test
  fun `multiple batches accumulate into one stroke`() {
    val b = builder()
    b.append(listOf(point(0f, 0f), point(1f, 1f)))
    b.append(listOf(point(2f, 2f), point(3f, 3f)))
    b.append(listOf(point(4f, 4f)))

    val stroke = b.finish()
    assertNotNull(stroke)
    assertEquals(5, stroke!!.points.size)
    assertEquals(3, b.batchCount)
  }

  @Test
  fun `accumulated points preserve arrival order`() {
    val b = builder()
    b.append(listOf(point(0f, 0f), point(10f, 0f)))
    b.append(listOf(point(20f, 0f), point(30f, 0f)))

    val xs = b.finish()!!.points.map { it.x }
    assertEquals(listOf(0f, 10f, 20f, 30f), xs)
  }

  @Test
  fun `empty batch is ignored`() {
    val b = builder()
    b.append(emptyList())
    assertTrue(b.isEmpty)
    assertEquals(0, b.batchCount)
  }

  @Test
  fun `gesture with no points yields no stroke`() {
    assertNull(builder().finish())
  }

  @Test
  fun `single point gesture still produces a stroke`() {
    // A deliberate dot must not be silently dropped.
    val b = builder()
    b.append(listOf(point(5f, 5f)))
    val stroke = b.finish()
    assertNotNull(stroke)
    assertEquals(1, stroke!!.points.size)
  }

  @Test
  fun `filter drops redundant samples but keeps raw count`() {
    val b = builder(filter = InputFilter(minDistance = 5.0f, minPressureDelta = 1.0f))
    b.append(listOf(point(0f, 0f), point(0.1f, 0f), point(0.2f, 0f), point(50f, 0f)))

    assertEquals(4, b.rawSampleCount)
    assertEquals(2, b.pointCount)
  }

  @Test
  fun `reset clears accumulated state`() {
    val b = builder()
    b.append(listOf(point(0f, 0f), point(1f, 1f)))
    b.reset()

    assertTrue(b.isEmpty)
    assertEquals(0, b.batchCount)
    assertEquals(0, b.rawSampleCount)
    assertNull(b.finish())
  }

  @Test
  fun `live outline is available mid-stroke`() {
    val b = builder()
    b.append(listOf(point(0f, 0f), point(10f, 0f), point(20f, 0f)))

    val outline = b.liveOutline()
    assertEquals(3, outline.pointCount)
  }

  @Test
  fun `tail outline covers new points plus one for continuity`() {
    val b = builder()
    b.append(listOf(point(0f, 0f), point(10f, 0f)))
    val drawn = b.pointCount
    b.append(listOf(point(20f, 0f), point(30f, 0f)))

    // 2 new points, plus 1 carried back so the segment joins cleanly.
    assertEquals(3, b.tailOutline(drawn).pointCount)
  }

  @Test
  fun `simplifier runs on finish not during accumulation`() {
    val counting = CountingSimplifier()
    val b = builder(simplifier = counting)

    b.append(listOf(point(0f, 0f), point(1f, 0f)))
    b.append(listOf(point(2f, 0f), point(3f, 0f)))
    assertEquals("simplifier must stay off the input hot path", 0, counting.calls)

    b.finish()
    assertEquals(1, counting.calls)
  }

  @Test
  fun `stroke timestamps span the gesture`() {
    val b = builder()
    b.append(listOf(InkPoint(0f, 0f, 0.5f, 1_000L)))
    b.append(listOf(InkPoint(10f, 0f, 0.5f, 1_050L)))
    b.append(listOf(InkPoint(20f, 0f, 0.5f, 1_100L)))

    val stroke = b.finish()!!
    assertEquals(1_000L, stroke.startTime)
    assertEquals(1_100L, stroke.endTime)
  }

  @Test
  fun `single point overload accumulates like a batch`() {
    val b = builder()
    b.append(point(0f, 0f))
    b.append(point(10f, 0f))
    assertEquals(2, b.pointCount)
  }

  @Test
  fun `stroke carries the builder style`() {
    val style = PenStyle(baseWidth = 7.5f)
    val b = StrokeBuilder(
      style = style,
      filter = InputFilter(minDistance = 0f, minPressureDelta = 0f),
      smoother = NoopSmoother(),
      simplifier = NoopSimplifier(),
    )
    b.append(listOf(point(0f, 0f), point(5f, 5f)))
    assertEquals(style, b.finish()!!.style)
  }

  private fun point(x: Float, y: Float, pressure: Float = 0.5f) =
    InkPoint(x = x, y = y, pressure = pressure, timestamp = 1_000L)

  private class CountingSimplifier : StrokeSimplifier {
    var calls = 0
    override fun simplify(points: List<InkPoint>): List<InkPoint> {
      calls++
      return points
    }
  }
}
