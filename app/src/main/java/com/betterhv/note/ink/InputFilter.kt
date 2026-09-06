package com.betterhv.note.ink

import kotlin.math.abs

/**
 * Redundant-sample rejection. Spec §20: drop a sample when it has moved
 * negligibly AND its pressure has barely changed:
 *
 *     d(Pi, Pi-1) < eps  AND  |pi - pi-1| < eps_p   ->  discard
 *
 * Both conditions must hold, so a stationary nib with changing pressure still
 * records the pressure ramp (important for dots and for pen-down onset).
 *
 * Stateless: the caller supplies the previous kept point. That keeps this
 * trivially testable and lets [StrokeBuilder] own all mutable stroke state.
 */
class InputFilter(private val minDistance: Float = 0.75f, private val minPressureDelta: Float = 0.02f) {
  fun shouldKeep(candidate: InkPoint, previous: InkPoint?): Boolean {
    if (previous == null) return true
    val dx = candidate.x - previous.x
    val dy = candidate.y - previous.y
    // Compare squared distance to avoid a sqrt per sample on the hot path.
    val movedEnough = (dx * dx + dy * dy) >= minDistance * minDistance
    val pressureChangedEnough =
      abs(candidate.pressure - previous.pressure) >= minPressureDelta
    return movedEnough || pressureChangedEnough
  }
}
