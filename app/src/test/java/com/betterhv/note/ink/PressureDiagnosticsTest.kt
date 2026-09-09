package com.betterhv.note.ink

import org.junit.Assert.assertTrue
import org.junit.Test

class PressureDiagnosticsTest {
  @Test fun reportsVendorWidthsInScreenAndPageUnitsWithoutClipping() {
    val raw = floatArrayOf(3f, 10f, 20f, 1f, 11f, 20f, 4f, 12f, 20f, 9f)
    val result = PressureDiagnostics.summarize(raw, 3, PenStyle(baseWidth = 6f), 2f, 12)
    assertTrue(result.contains("vendorWidthPxMax=9.0"))
    assertTrue(result.contains("9.0:1:0.75"))
    assertTrue(result.contains("configuredWidthPx=12"))
    assertTrue(raw.last() == 9f)
  }
}
