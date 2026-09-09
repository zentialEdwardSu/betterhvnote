package com.betterhv.note.jni

import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.StrokeSmoother
import com.betterhv.note.BuildConfig
import com.betterhv.note.EventLog

/**
 * [StrokeSmoother] backed by Google's ink-stroke-modeler via [InkStrokeModelerJNI].
 *
 * The native modeler is 1:many (a batch of N raw samples yields some other
 * number of modeled Results), which is exactly why the interface grew
 * [smoothBatch]: this class overrides it and passes the count through, while
 * [smooth] (the 1:1 method) is only a degenerate single-element batch.
 *
 * Output is transactional per gesture. Raw filtered input and native output are
 * buffered separately; only [finishStroke] publishes one of them. This allows
 * an invalid scalar or native failure in a late batch to fall back to the whole
 * raw gesture instead of splicing two algorithms into one stroke.
 * Kept OUTSIDE the framework-free `com.betterhv.note.ink` package on purpose:
 * System.loadLibrary and native methods are Android-runtime concepts and must
 * not leak into the JVM-testable core.
 *
 * Single-gesture, stateful: [StrokeBuilder] makes one per stroke (or calls
 * [reset]). The native modeler holds one in-progress stroke process-wide, so
 * only one ModelerStrokeSmoother may be driving it at a time -- which holds,
 * since PenDrawView runs one gesture at a time on one thread.
 */
class ModelerStrokeSmoother : StrokeSmoother {

  private val jni: InkStrokeModelerJNI? =
    if (InkStrokeModelerJNI.available) InkStrokeModelerJNI() else null

  private var degraded: Boolean = jni == null
  private var fallbackPublished = false
  private val rawPoints = ArrayList<InkPoint>()
  private val modeledPoints = ArrayList<InkPoint>()

  override val usedRawFallback: Boolean get() = fallbackPublished

  // Whether the next sample is the first of the gesture (a kDown event).
  private var atGestureStart: Boolean = true

  // Reused output buffer, grown as needed, to avoid per-batch allocation.
  private var outBuf: FloatArray = FloatArray(INITIAL_OUT_CAPACITY)

  // Last raw sample fed to the modeler this gesture, so finishStroke() can
  // synthesize a kUp at the correct terminal position. Null before any input.
  private var lastRaw: InkPoint? = null

  override fun smooth(point: InkPoint): InkPoint {
    smoothBatch(listOf(point))
    return point
  }

  override fun smoothBatch(batch: List<InkPoint>): List<InkPoint> {
    if (batch.isEmpty()) return emptyList()
    rawPoints.addAll(batch)
    if (batch.any { !it.pressure.isFinite() || it.pressure !in 0f..1f }) {
      degrade("input scalar outside [0,1]")
      return emptyList()
    }
    val modeler = jni
    if (degraded || modeler == null) return emptyList()

    // On the first batch of a gesture, (re)initialize native state.
    if (atGestureStart) {
      val resetResult = try {
        modeler.nativeReset()
      } catch (t: Throwable) {
        degrade("native reset threw ${t.javaClass.simpleName}: ${t.message}")
        return emptyList()
      }
      if (resetResult != 0) {
        degrade("native reset failed: $resetResult")
        return emptyList()
      }
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
    val count = updateWithRetry(modeler, inXYP, events)
    if (count < 0) {
      degrade("native update failed: $count")
      return emptyList()
    }

    atGestureStart = false
    lastRaw = batch.last()
    val output = marshalOut(count, batch.last().timestamp)
    if (!validModeledPoints(output)) {
      degrade("modeled point non-finite or scalar outside [0,1]")
      return emptyList()
    }
    modeledPoints.addAll(output)
    return emptyList()
  }

  override fun finishStroke(): List<InkPoint> {
    val modeler = jni
    val last = lastRaw
    if (degraded || modeler == null || last == null) {
      fallbackPublished = true
      return rawPoints.toList()
    }

    // A kUp at the last raw position makes the modeler emit its end-of-
    // stroke catch-up run, so the committed stroke reaches the real pen-up
    // point instead of ending short of it (the spring model lags).
    val inXYP = floatArrayOf(last.x, last.y, last.pressure)
    val events = byteArrayOf(EVENT_UP)
    ensureCapacity(OUTPUT_HEADROOM)
    val count = updateWithRetry(modeler, inXYP, events)
    if (count < 0) {
      degrade("native finish failed: $count")
      fallbackPublished = true
      return rawPoints.toList()
    }
    val tail = marshalOut(count, last.timestamp)
    if (!validModeledPoints(tail)) {
      degrade("modeled finish point non-finite or scalar outside [0,1]")
      fallbackPublished = true
      return rawPoints.toList()
    }
    modeledPoints.addAll(tail)
    if (modeledPoints.isEmpty()) {
      fallbackPublished = true
      return rawPoints.toList()
    }
    return modeledPoints.toList()
  }

  override fun reset() {
    jni?.let { modeler ->
      if (!degraded) runCatching { modeler.nativeClear() }
        .onFailure { EventLog.log("PressureModeler", "native clear failed: ${it.message}") }
    }
    atGestureStart = true
    lastRaw = null
    rawPoints.clear()
    modeledPoints.clear()
    fallbackPublished = false
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
          pressure = outBuf[i * 3 + 2],
          timestamp = ts,
        ),
      )
    }
    if (BuildConfig.DEBUG && result.isNotEmpty()) {
      EventLog.log("PressureModeler", "t=$ts n=$count min=${result.minOf { it.pressure }} " +
        "max=${result.maxOf { it.pressure }} saturated=${result.count { it.pressure >= 1f }}")
    }
    return result
  }

  private fun validModeledPoints(points: List<InkPoint>): Boolean =
    points.all {
      it.x.isFinite() && it.y.isFinite() && it.pressure.isFinite() && it.pressure in 0f..1f
    }

  private fun degrade(reason: String) {
    if (!degraded) EventLog.log("PressureModeler", "raw fallback: $reason")
    degraded = true
  }

  private fun updateWithRetry(modeler: InkStrokeModelerJNI, input: FloatArray, events: ByteArray): Int {
    repeat(8) {
      var checkpointed = false
      try {
        modeler.nativeSave()
        checkpointed = true
        val count = modeler.nativeUpdate(input, events, outBuf)
        if (count >= 0) return count
        modeler.nativeRestore()
        if (count != ERR_BUFFER_TOO_SMALL) return count
        ensureCapacity(outBuf.size * 2)
      } catch (t: Throwable) {
        if (checkpointed) runCatching { modeler.nativeRestore() }
        EventLog.log("PressureModeler", "native update threw ${t.javaClass.simpleName}: ${t.message}")
        return ERR_NATIVE_EXCEPTION
      }
    }
    return ERR_BUFFER_TOO_SMALL
  }

  private fun ensureCapacity(floats: Int) {
    if (outBuf.size < floats) outBuf = FloatArray(floats)
  }

  private companion object {
    const val EVENT_DOWN: Byte = 0
    const val EVENT_MOVE: Byte = 1
    const val EVENT_UP: Byte = 2
    const val ERR_BUFFER_TOO_SMALL = -3
    const val ERR_NATIVE_EXCEPTION = -4

    // A batch rarely exceeds a few dozen samples; start comfortably above
    // that (in floats: 3 per point) so the first gesture never reallocates.
    const val INITIAL_OUT_CAPACITY = 256 * 3

    // Output-point headroom over input count, per point (3 floats each),
    // covering the modeler upsampling a slow batch.
    const val OUTPUT_HEADROOM = 4 * 3
  }
}
