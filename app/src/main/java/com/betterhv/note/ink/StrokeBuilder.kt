package com.betterhv.note.ink

/**
 * Accumulates one gesture's worth of samples into a single [Stroke].
 * Spec §19: owns only the in-progress stroke, nothing else.
 *
 * The reason this class exists in this shape: the ROM's
 * HvPenDrawListener.onPenTouchUpStatus() fires MULTIPLE times during one
 * physical pen-down-to-up gesture, and each call carries a self-contained batch
 * with no correlation to the others -- the vendor's own app just rasterizes each
 * batch independently and never reassembles them. Since a vector document needs
 * one object per gesture, the correlation the ROM does not do has to happen
 * here: [append] is called once per delivered batch and everything accumulates
 * into one point list until [finish].
 *
 * Pipeline per sample (§18): filter -> smooth -> accumulate. Simplification is
 * deliberately NOT here; it runs once in [finish] because it needs the whole
 * path and must stay off the input hot path (§64).
 */
class StrokeBuilder(
  val style: PenStyle,
  private val filter: InputFilter = InputFilter(),
  private val smoother: StrokeSmoother = OneEuroSmoother(),
  private val simplifier: StrokeSimplifier = RamerDouglasPeuckerSimplifier(),
) {
  private val points = ArrayList<InkPoint>(INITIAL_CAPACITY)

  // Last raw sample kept by the filter. Filtering is done on the raw stream
  // (before smoothing) so a model-based smoother is fed the believable,
  // de-duplicated input it expects, rather than its own smoothed output --
  // and so the redundant-sample test is not skewed by smoothing lag.
  private var lastKeptRaw: InkPoint? = null

  /** Number of batches merged so far -- surfaced for on-device diagnostics. */
  var batchCount: Int = 0
    private set

  var rawSampleCount: Int = 0
    private set

  val pointCount: Int get() = points.size
  val isEmpty: Boolean get() = points.isEmpty()

  /**
   * Merge one delivered batch. Points must be in temporal order within the
   * batch, and batches must arrive in order -- both hold for the ROM callback.
   *
   * Filter (raw) -> smoothBatch -> accumulate. smoothBatch may return a
   * different count than it was given (a model-based smoother can upsample or
   * lag), so we cannot assume 1:1; whatever it returns is accumulated as-is.
   */
  fun append(batch: List<InkPoint>) {
    if (batch.isEmpty()) return
    batchCount++
    rawSampleCount += batch.size
    val kept = ArrayList<InkPoint>(batch.size)
    for (raw in batch) {
      if (!filter.shouldKeep(raw, lastKeptRaw)) continue
      kept.add(raw)
      lastKeptRaw = raw
    }
    if (kept.isEmpty()) return
    points.addAll(smoother.smoothBatch(kept))
  }

  fun append(point: InkPoint) {
    batchCount++
    rawSampleCount++
    if (!filter.shouldKeep(point, lastKeptRaw)) return
    lastKeptRaw = point
    points.addAll(smoother.smoothBatch(listOf(point)))
  }

  /**
   * Outline of the stroke as it stands, for drawing work in progress.
   * Rebuilt on demand rather than cached: the point list is still growing, so
   * any cache would be invalidated by the next batch anyway.
   */
  fun liveOutline(): StrokeOutline = StrokeGeometry.build(points, style)

  fun liveBounds(): Bounds = Bounds.of(points)

  /**
   * Outline of just the points from [fromIndex] onward, so a caller drawing
   * incrementally can paint only what just arrived instead of rebuilding the
   * whole ribbon on every batch (which would make one long stroke O(n^2)).
   *
   * Includes one point before [fromIndex] so the new segment overlaps the
   * previously drawn part.
   *
   * Uses [StrokeGeometry.buildRange] over the FULL point list rather than
   * slicing a sublist into [StrokeGeometry.build]: buildRange computes the
   * seam point's tangent from its real neighbors in the whole stroke, so the
   * emitted geometry matches what a full-stroke rebuild would produce and the
   * tail joins the prior segment with no width/curvature kink (plan doc §4a).
   */
  fun tailOutline(fromIndex: Int): StrokeOutline {
    if (points.isEmpty()) return EMPTY_OUTLINE
    val start = (fromIndex - 1).coerceIn(0, points.size - 1)
    return StrokeGeometry.buildRange(points, start, style)
  }

  /**
   * Simplify and produce the final stroke. Returns null when the gesture
   * produced nothing worth keeping, so a stray hover or a fully-filtered
   * no-op does not litter the page with empty objects.
   */
  fun finish(): Stroke? {
    // Drain any end-of-stroke catch-up the smoother was holding back (a
    // lagging model-based smoother trails the raw input mid-stroke and only
    // emits the tail at pen-up), so the committed stroke reaches the last
    // real sample instead of ending short.
    points.addAll(smoother.finishStroke())
    if (points.isEmpty()) return null
    val simplified = simplifier.simplify(points)
    if (simplified.isEmpty()) return null
    return Stroke(points = simplified.toList(), style = style)
  }

  fun reset() {
    points.clear()
    lastKeptRaw = null
    smoother.reset()
    batchCount = 0
    rawSampleCount = 0
  }

  private companion object {
    // Typical handwritten glyph lands well under this, so the common case
    // never reallocates.
    const val INITIAL_CAPACITY = 256

    val EMPTY_OUTLINE = StrokeOutline(FloatArray(0), FloatArray(0), Bounds(0f, 0f, 0f, 0f))
  }
}
