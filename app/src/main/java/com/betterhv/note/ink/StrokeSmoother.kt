package com.betterhv.note.ink

import kotlin.math.PI
import kotlin.math.abs

/**
 * Smoothing of the incoming sample stream. Ordinary 1:1 filters can emit
 * immediately; a transactional modeler may buffer until pen-up so that any
 * invalid scalar or native failure can select the filtered raw stroke in full
 * instead of mixing raw and modeled segments.
 *
 * Instances are stateful and single-stroke: [StrokeBuilder] creates one per
 * gesture, or calls [reset] between strokes.
 */
interface StrokeSmoother {
  /** True after [finishStroke] chose the original filtered samples. */
  val usedRawFallback: Boolean get() = false

  fun smooth(point: InkPoint): InkPoint

  /**
   * Smooth a whole delivered batch at once. The default degrades to a
   * per-point [smooth] so existing 1:1 smoothers (OneEuro, Noop) need no
   * change. Exists because a model-based smoother can emit a DIFFERENT number
   * of points than it consumes (upsampling a slow batch, or lagging behind
   * fast input) -- a 1:1 signature cannot express that, so batching is the
   * honest boundary. Returned points are already smoothed and ready to
   * accumulate; an empty result is valid while a transactional smoother is
   * buffering its whole-stroke decision. The batch is one ROM delivery,
   * always within a single gesture, in temporal order.
   */
  fun smoothBatch(batch: List<InkPoint>): List<InkPoint> = batch.map { smooth(it) }

  /**
   * Signal end-of-stroke and return any trailing smoothed points the smoother
   * was still holding back. A lagging model-based smoother (the spring-mass
   * modeler) trails behind the raw input mid-stroke; at pen-up it emits a
   * final "catch-up" run so the committed stroke reaches the last real
   * sample. The default returns nothing -- a 1:1 smoother has no backlog and
   * ends exactly where its last [smooth] left off. Called once per gesture,
   * after the last [smoothBatch]; the returned points append to the stroke
   * before simplification.
   */
  fun finishStroke(): List<InkPoint> = emptyList()

  fun reset()
}

/** Passthrough, for tests and for A/B-ing smoothing against raw input on device. */
class NoopSmoother : StrokeSmoother {
  override fun smooth(point: InkPoint): InkPoint = point
  override fun reset() {}
}

/**
 * One Euro Filter (Casiez et al.) over x/y, plus a gentle fixed low-pass on
 * pressure so stroke width does not shimmer.
 *
 * The filter adapts its cutoff to speed: slow movement gets heavy smoothing
 * (kills jitter when drawing carefully), fast movement gets light smoothing
 * (avoids lag on quick strokes) -- the property that makes it a good fit for
 * ink versus a fixed low-pass.
 *
 * Sampling interval: the ROM does not report per-sample timing, and every point
 * in one delivered batch is stamped with the same receipt time, so real
 * timestamp deltas are frequently zero and unusable as a filter input. We
 * therefore advance the filter on a nominal fixed interval instead of trusting
 * the stamps. Velocity is then effectively units-per-sample, which keeps the
 * adaptive behaviour intact. [nominalSampleHz] and the cutoffs need tuning
 * against real hardware.
 */
class OneEuroSmoother(
  nominalSampleHz: Float = 120.0f,
  private val minCutoff: Float = 1.5f,
  private val beta: Float = 0.05f,
  private val derivativeCutoff: Float = 1.0f,
  private val pressureCutoff: Float = 4.0f,
) : StrokeSmoother {

  private val dt: Float = 1.0f / nominalSampleHz

  private val xFilter = LowPass()
  private val yFilter = LowPass()
  private val dxFilter = LowPass()
  private val dyFilter = LowPass()
  private val pressureFilter = LowPass()

  private var hasPrevious = false
  private var previousX = 0.0f
  private var previousY = 0.0f

  override fun smooth(point: InkPoint): InkPoint {
    val rawDx = if (hasPrevious) (point.x - previousX) / dt else 0.0f
    val rawDy = if (hasPrevious) (point.y - previousY) / dt else 0.0f
    previousX = point.x
    previousY = point.y
    hasPrevious = true

    val dxHat = dxFilter.filter(rawDx, alpha(derivativeCutoff))
    val dyHat = dyFilter.filter(rawDy, alpha(derivativeCutoff))

    val smoothedX = xFilter.filter(point.x, alpha(minCutoff + beta * abs(dxHat)))
    val smoothedY = yFilter.filter(point.y, alpha(minCutoff + beta * abs(dyHat)))
    val smoothedPressure =
      pressureFilter.filter(point.pressure, alpha(pressureCutoff)).coerceIn(0.0f, 1.0f)

    return point.copy(x = smoothedX, y = smoothedY, pressure = smoothedPressure)
  }

  override fun reset() {
    xFilter.reset()
    yFilter.reset()
    dxFilter.reset()
    dyFilter.reset()
    pressureFilter.reset()
    hasPrevious = false
  }

  private fun alpha(cutoff: Float): Float {
    val tau = 1.0f / (2.0f * PI.toFloat() * cutoff)
    return 1.0f / (1.0f + tau / dt)
  }

  private class LowPass {
    private var initialized = false
    private var previous = 0.0f

    fun filter(value: Float, alpha: Float): Float {
      if (!initialized) {
        initialized = true
        previous = value
        return value
      }
      val output = alpha * value + (1.0f - alpha) * previous
      previous = output
      return output
    }

    fun reset() {
      initialized = false
    }
  }
}
