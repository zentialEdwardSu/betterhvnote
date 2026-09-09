package com.betterhv.note.jni

import com.betterhv.note.ink.InkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests exercise the transactional raw path because JNI is Android-only. */
class ModelerStrokeSmootherTest {
  @Test fun nativeLibraryIsUnavailableOnTheJvm() {
    assertFalse(InkStrokeModelerJNI.available)
  }

  @Test fun batchesAreHeldUntilTheGestureDecisionIsFinal() {
    val smoother = ModelerStrokeSmoother()
    assertTrue(smoother.smoothBatch(listOf(point(0f, 0.4f), point(5f, 0.5f))).isEmpty())
    assertFalse(smoother.usedRawFallback)

    val output = smoother.finishStroke()
    assertEquals(listOf(point(0f, 0.4f), point(5f, 0.5f)), output)
    assertTrue(smoother.usedRawFallback)
  }

  @Test fun invalidScalarInALaterBatchFallsBackTheWholeStrokeWithoutClipping() {
    val smoother = ModelerStrokeSmoother()
    val first = listOf(point(0f, 0.4f), point(5f, 0.5f))
    val second = listOf(point(10f, 1.25f))
    smoother.smoothBatch(first)
    smoother.smoothBatch(second)

    val output = smoother.finishStroke()
    assertEquals(first + second, output)
    assertEquals(1.25f, output.last().pressure, 0f)
    assertTrue(smoother.usedRawFallback)
  }

  @Test fun invalidScalarInTheFirstBatchFallsBackWithoutClipping() {
    val smoother = ModelerStrokeSmoother()
    val input = listOf(point(0f, -0.2f), point(5f, 0.5f))
    smoother.smoothBatch(input)

    val output = smoother.finishStroke()
    assertEquals(input, output)
    assertEquals(-0.2f, output.first().pressure, 0f)
    assertTrue(smoother.usedRawFallback)
  }

  @Test fun resetClearsBufferedGestureAndFallbackState() {
    val smoother = ModelerStrokeSmoother()
    smoother.smoothBatch(listOf(point(1f, 0.5f)))
    smoother.finishStroke()
    smoother.reset()

    assertFalse(smoother.usedRawFallback)
    assertTrue(smoother.finishStroke().isEmpty())
  }

  private fun point(x: Float, scalar: Float) = InkPoint(x, x * 0.5f, scalar, 1_000L)
}
