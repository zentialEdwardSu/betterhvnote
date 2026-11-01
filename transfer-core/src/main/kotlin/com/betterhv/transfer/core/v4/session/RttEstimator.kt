package com.betterhv.transfer.core.v4.session

/**
 * Smoothed round-trip estimate per peer, used to pick budgets and to report degradation.
 * Samples are millisecond exchange times; the first sample seeds the estimate.
 */
class RttEstimator(initialMillis: Long = DEFAULT_INITIAL_MILLIS) {
  private var smoothed = initialMillis.coerceAtLeast(0)

  @Synchronized
  fun sample(millis: Long) {
    if (millis < 0) return
    smoothed = if (smoothed == 0L) millis else (smoothed * 7 + millis) / 8
  }

  @Synchronized
  fun estimateMillis(): Long = smoothed

  @Synchronized
  fun reset(initialMillis: Long = DEFAULT_INITIAL_MILLIS) {
    smoothed = initialMillis.coerceAtLeast(0)
  }

  companion object {
    const val DEFAULT_INITIAL_MILLIS = 100L
  }
}
