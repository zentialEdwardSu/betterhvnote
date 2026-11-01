package com.betterhv.transfer.core.v4.session

import org.junit.Assert.assertEquals
import org.junit.Test

class RttEstimatorTest {
  @Test
  fun firstSampleSeedsAndLaterSamplesSmooth() {
    val estimator = RttEstimator(initialMillis = 0)
    estimator.sample(50)
    assertEquals(50, estimator.estimateMillis())
    estimator.sample(250)
    assertEquals((50L * 7 + 250) / 8, estimator.estimateMillis())
  }

  @Test
  fun negativeSamplesAreIgnoredAndResetRestoresTheDefault() {
    val estimator = RttEstimator(initialMillis = 100)
    estimator.sample(-1)
    assertEquals(100, estimator.estimateMillis())
    estimator.sample(900)
    estimator.reset()
    assertEquals(RttEstimator.DEFAULT_INITIAL_MILLIS, estimator.estimateMillis())
  }
}
