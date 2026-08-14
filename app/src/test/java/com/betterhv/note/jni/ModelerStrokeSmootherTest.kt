package com.betterhv.note.jni

import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.NoopSmoother
import com.betterhv.note.ink.StrokeSmoother
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * These run on the plain JVM, where libink_stroke_modeler_jni.so cannot load,
 * so [InkStrokeModelerJNI.available] is false and [ModelerStrokeSmoother] must
 * degrade to its fallback on every call. That is exactly the contract this
 * covers: the native upgrade is optional and its absence must never break
 * writing. The real native path can only be exercised on-device (Milestone 6).
 */
class ModelerStrokeSmootherTest {

    @Test
    fun `native library is unavailable on the JVM`() {
        // Guards the premise of every other test here: if this ever flips, the
        // fallback assertions below would be silently testing the native path.
        assertFalse(InkStrokeModelerJNI.available)
    }

    @Test
    fun `smooth delegates to the fallback when native is unavailable`() {
        // A spy fallback that records what it saw, so we can prove delegation.
        val recorder = RecordingSmoother()
        val smoother = ModelerStrokeSmoother(fallback = recorder)

        val p = InkPoint(10f, 20f, 0.5f, 1_000L)
        val out = smoother.smooth(p)

        assertEquals(1, recorder.batchesSeen)
        assertSame(p, out)  // Noop-style recorder returns the same instance
    }

    @Test
    fun `smoothBatch delegates the whole batch to the fallback`() {
        val recorder = RecordingSmoother()
        val smoother = ModelerStrokeSmoother(fallback = recorder)

        val batch = listOf(
            InkPoint(0f, 0f, 0.4f, 1_000L),
            InkPoint(5f, 5f, 0.5f, 1_008L),
            InkPoint(10f, 10f, 0.6f, 1_016L)
        )
        val out = smoother.smoothBatch(batch)

        assertEquals(3, out.size)
        assertEquals(1, recorder.batchesSeen)
        assertEquals(3, recorder.pointsSeen)
    }

    @Test
    fun `empty batch produces no output and no fallback call`() {
        val recorder = RecordingSmoother()
        val smoother = ModelerStrokeSmoother(fallback = recorder)

        val out = smoother.smoothBatch(emptyList())

        assertEquals(0, out.size)
        assertEquals(0, recorder.batchesSeen)
    }

    @Test
    fun `reset forwards to the fallback`() {
        val recorder = RecordingSmoother()
        val smoother = ModelerStrokeSmoother(fallback = recorder)

        smoother.smooth(InkPoint(1f, 1f, 0.5f, 1_000L))
        smoother.reset()

        assertEquals(1, recorder.resets)
    }

    /** A [NoopSmoother]-equivalent that counts what passes through it. */
    private class RecordingSmoother : StrokeSmoother {
        var batchesSeen = 0
        var pointsSeen = 0
        var resets = 0

        private val delegate = NoopSmoother()

        override fun smooth(point: InkPoint): InkPoint {
            batchesSeen++
            pointsSeen++
            return delegate.smooth(point)
        }

        override fun smoothBatch(batch: List<InkPoint>): List<InkPoint> {
            batchesSeen++
            pointsSeen += batch.size
            return batch.map { delegate.smooth(it) }
        }

        override fun reset() {
            resets++
        }
    }
}
