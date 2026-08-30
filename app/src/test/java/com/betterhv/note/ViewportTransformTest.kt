package com.betterhv.note

import com.betterhv.note.ink.Bounds
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportTransformTest {
    @Test
    fun fitLetterPageAndRoundTripCoordinates() {
        val transform = ViewportTransform.fit(800f, 1200f, 1600, 2560)
        assertEquals(2f, transform.scale, 0.0001f)
        assertEquals(0f, transform.offsetX, 0.0001f)
        assertEquals(80f, transform.offsetY, 0.0001f)

        val screen = transform.pageToScreen(320f, 440f)
        val page = transform.screenToPage(screen[0], screen[1])
        assertEquals(320f, page[0], 0.0001f)
        assertEquals(440f, page[1], 0.0001f)
        assertEquals(
            Bounds(200f, 280f, 600f, 680f),
            transform.pageToScreen(Bounds(100f, 100f, 300f, 300f))
        )
    }
}
