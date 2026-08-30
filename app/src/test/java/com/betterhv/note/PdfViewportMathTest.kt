package com.betterhv.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfViewportMathTest {
    @Test fun zoomKeepsFocusPagePointStationaryAwayFromEdges() {
        val start = PdfViewportState(zoom = 1.5f, panX = 40f, panY = -60f)
        val before = PdfViewportMath.transform(start, 1000f, 1400f, 1000, 1000)
        val focusX = 500f
        val focusY = 500f
        val pagePoint = before.screenToPage(focusX, focusY)

        val afterState = PdfViewportMath.zoomAt(
            start, 2f, focusX, focusY, 1000f, 1400f, 1000, 1000
        )
        val after = PdfViewportMath.transform(afterState, 1000f, 1400f, 1000, 1000)
        val mapped = after.pageToScreen(pagePoint[0], pagePoint[1])

        assertEquals(focusX, mapped[0], 0.01f)
        assertEquals(focusY, mapped[1], 0.01f)
    }

    @Test fun clampLimitsZoomAndPreventsPageLeavingViewport() {
        val clamped = PdfViewportMath.clamp(
            PdfViewportState(zoom = 10f, panX = 100_000f, panY = -100_000f),
            1000f, 1400f, 1000, 1000
        )
        val viewport = PdfViewportMath.transform(clamped, 1000f, 1400f, 1000, 1000)

        assertEquals(4f, clamped.zoom, 0f)
        assertTrue(viewport.offsetX <= 0f)
        assertTrue(viewport.offsetY <= 0f)
        assertTrue(viewport.offsetX + 1000f * viewport.scale >= 1000f)
        assertTrue(viewport.offsetY + 1400f * viewport.scale >= 1000f)
    }

    @Test fun fitZoomAllowsBoundedSideOnePan() {
        val state = PdfViewportMath.clamp(
            PdfViewportState(zoom = 1f, panX = 200f, panY = -200f),
            1000f, 1400f, 1000, 1000
        )
        assertEquals(200f, state.panX, 0f)
        assertEquals(-200f, state.panY, 0f)

        val bounded = PdfViewportMath.clamp(
            PdfViewportState(zoom = 1f, panX = 10_000f, panY = -10_000f),
            1000f, 1400f, 1000, 1000
        )
        assertEquals(250f, bounded.panX, 0f)
        assertEquals(-250f, bounded.panY, 0f)
    }
}
