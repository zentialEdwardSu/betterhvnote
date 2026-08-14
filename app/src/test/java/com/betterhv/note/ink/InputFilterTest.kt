package com.betterhv.note.ink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputFilterTest {

    @Test
    fun `first point is always kept`() {
        val filter = InputFilter()
        assertTrue(filter.shouldKeep(point(0f, 0f), null))
    }

    @Test
    fun `duplicate sample is discarded`() {
        val filter = InputFilter(minDistance = 1.0f, minPressureDelta = 0.1f)
        val previous = point(10f, 10f, 0.5f)
        assertFalse(filter.shouldKeep(point(10f, 10f, 0.5f), previous))
    }

    @Test
    fun `sub-threshold movement is discarded`() {
        val filter = InputFilter(minDistance = 1.0f, minPressureDelta = 0.1f)
        val previous = point(10f, 10f, 0.5f)
        assertFalse(filter.shouldKeep(point(10.2f, 10.2f, 0.5f), previous))
    }

    @Test
    fun `movement past threshold is kept`() {
        val filter = InputFilter(minDistance = 1.0f, minPressureDelta = 0.1f)
        val previous = point(10f, 10f, 0.5f)
        assertTrue(filter.shouldKeep(point(12f, 10f, 0.5f), previous))
    }

    @Test
    fun `pressure change is kept even when the nib has not moved`() {
        // Spec §20 requires BOTH conditions to hold before discarding, so a
        // stationary nib with a rising pressure ramp still records it.
        val filter = InputFilter(minDistance = 1.0f, minPressureDelta = 0.1f)
        val previous = point(10f, 10f, 0.2f)
        assertTrue(filter.shouldKeep(point(10f, 10f, 0.9f), previous))
    }

    @Test
    fun `diagonal distance is measured euclidean not per-axis`() {
        // (0.8, 0.8) is under threshold on each axis alone but over it as a distance.
        val filter = InputFilter(minDistance = 1.0f, minPressureDelta = 1.0f)
        val previous = point(0f, 0f, 0.5f)
        assertTrue(filter.shouldKeep(point(0.8f, 0.8f, 0.5f), previous))
    }

    private fun point(x: Float, y: Float, pressure: Float = 0.5f) =
        InkPoint(x = x, y = y, pressure = pressure, timestamp = 1_000L)
}
