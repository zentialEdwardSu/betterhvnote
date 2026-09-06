package com.betterhv.note.jni

import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.OneEuroSmoother
import com.betterhv.note.ink.StrokeSmoother

/**
 * [StrokeSmoother] backed by Google's ink-stroke-modeler via [InkStrokeModelerJNI].
 *
 * The native modeler is 1:many (a batch of N raw samples yields some other
 * number of modeled Results), which is exactly why the interface grew
 * [smoothBatch]: this class overrides it and passes the count through, while
 * [smooth] (the 1:1 method) is only a degenerate single-element batch.
 *
 * Fallback: if the .so did not load, or the native modeler returns an error
 * mid-gesture, this transparently delegates to a [OneEuroSmoother] for the rest
 * of that gesture, so writing never breaks -- it just quietly loses the upgrade.
 * Kept OUTSIDE the framework-free `com.betterhv.note.ink` package on purpose:
 * System.loadLibrary and native methods are Android-runtime concepts and must
 * not leak into the JVM-testable core.
 *
 * Single-gesture, stateful: [StrokeBuilder] makes one per stroke (or calls
 * [reset]). The native modeler holds one in-progress stroke process-wide, so
 * only one ModelerStrokeSmoother may be driving it at a time -- which holds,
 * since PenDrawView runs one gesture at a time on one thread.
 */
class ModelerStrokeSmoother(private val fallback: StrokeSmoother = OneEuroSmoother()) : StrokeSmoother {

  private val jni: InkStrokeModelerJNI? =
    if (InkStrokeModelerJNI.available) InkStrokeModelerJNI() else null

  // True once we have committed to the fallback for the current gesture:
  // either native was never available, or a native call failed and we must
  // not resume mid-stroke (the modeler's stroke state would be inconsistent).
  private var degraded: Boolean = jni == null

  // Whether the next sample is the first of the gesture (a kDown event).
  private var atGestureStart: Boolean = true

  // Reused output buffer, grown as needed, to avoid per-batch allocation.
  private var outBuf: FloatArray = FloatArray(INITIAL_OUT_CAPACITY)

  // Last raw sample fed to the modeler this gesture, so finishStroke() can
  // synthesize a kUp at the correct terminal position. Null before any input.
  private var lastRaw: InkPoint? = null

  override fun smooth(point: InkPoint): InkPoint {
    // 1:1 convenience path: a single-element batch. Falls back to the raw
    // point if the modeler produced nothing (rare; keeps output non-null).
    val out = smoothBatch(listOf(point))
    return out.firstOrNull() ?: point
  }

  override fun smoothBatch(batch: List<InkPoint>): List<InkPoint> {
    if (batch.isEmpty()) return emptyList()
    val modeler = jni
    if (degraded || modeler == null) return fallback.smoothBatch(batch)

    // On the first batch of a gesture, (re)initialize native state.
    if (atGestureStart) {
      if (modeler.nativeReset() != 0) return degradeAndFallback(batch)
    }

    val n = batch.size
    val inXYP = FloatArray(n * 3)
    val events = ByteArray(n)
    for (i in 0 until n) {
      val p = batch[i]
      inXYP[i * 3] = p.x
      inXYP[i * 3 + 1] = p.y
      inXYP[i * 3 + 2] = p.pressure
      events[i] = if (atGestureStart && i == 0) EVENT_DOWN else EVENT_MOVE
    }

    // The modeler may emit more points than we fed it; size the buffer for
    // a generous upper bound and retry-grow if it ever reports too-small.
    ensureCapacity(n * OUTPUT_HEADROOM)
    var count = modeler.nativeUpdate(inXYP, events, outBuf)
    if (count == ERR_BUFFER_TOO_SMALL) {
      ensureCapacity(outBuf.size * 2)
      count = modeler.nativeUpdate(inXYP, events, outBuf)
    }
    if (count < 0) return degradeAndFallback(batch)

    atGestureStart = false
    lastRaw = batch.last()
    return marshalOut(count, batch.last().timestamp)
  }

  override fun finishStroke(): List<InkPoint> {
    val modeler = jni
    val last = lastRaw
    // Nothing to flush if we never modeled anything this gesture, or if the
    // gesture already degraded to the (backlog-free) fallback.
    if (degraded || modeler == null || last == null) return fallback.finishStroke()

    // A kUp at the last raw position makes the modeler emit its end-of-
    // stroke catch-up run, so the committed stroke reaches the real pen-up
    // point instead of ending short of it (the spring model lags).
    val inXYP = floatArrayOf(last.x, last.y, last.pressure)
    val events = byteArrayOf(EVENT_UP)
    ensureCapacity(OUTPUT_HEADROOM)
    var count = modeler.nativeUpdate(inXYP, events, outBuf)
    if (count == ERR_BUFFER_TOO_SMALL) {
      ensureCapacity(outBuf.size * 2)
      count = modeler.nativeUpdate(inXYP, events, outBuf)
    }
    if (count <= 0) return emptyList()
    return marshalOut(count, last.timestamp)
  }

  override fun reset() {
    jni?.let { if (!degraded) it.nativeClear() }
    fallback.reset()
    atGestureStart = true
    lastRaw = null
    // Re-arm native for the next gesture only if the .so is present; a
    // permanent load failure stays degraded for this instance's lifetime.
    degraded = (jni == null)
  }

  // Convert the first [count] modeled triples in outBuf to InkPoints, stamped
  // with [ts] (the ROM gives one receipt time per batch; the modeler's own
  // synthetic timing is internal and not surfaced here).
  private fun marshalOut(count: Int, ts: Long): List<InkPoint> {
    val result = ArrayList<InkPoint>(count)
    for (i in 0 until count) {
      result.add(
        InkPoint(
          x = outBuf[i * 3],
          y = outBuf[i * 3 + 1],
          pressure = outBuf[i * 3 + 2].coerceIn(0f, 1f),
          timestamp = ts,
        ),
      )
    }
    return result
  }

  // Abandon native for the remainder of this gesture and smooth the current
  // batch with the fallback. The fallback has no prior state for this stroke,
  // but one-euro converges within a few samples, so the visual seam is minor
  // and only occurs on an (unexpected) native error.
  private fun degradeAndFallback(batch: List<InkPoint>): List<InkPoint> {
    degraded = true
    return fallback.smoothBatch(batch)
  }

  private fun ensureCapacity(floats: Int) {
    if (outBuf.size < floats) outBuf = FloatArray(floats)
  }

  private companion object {
    const val EVENT_DOWN: Byte = 0
    const val EVENT_MOVE: Byte = 1
    const val EVENT_UP: Byte = 2
    const val ERR_BUFFER_TOO_SMALL = -3

    // A batch rarely exceeds a few dozen samples; start comfortably above
    // that (in floats: 3 per point) so the first gesture never reallocates.
    const val INITIAL_OUT_CAPACITY = 256 * 3

    // Output-point headroom over input count, per point (3 floats each),
    // covering the modeler upsampling a slow batch.
    const val OUTPUT_HEADROOM = 4 * 3
  }
}
