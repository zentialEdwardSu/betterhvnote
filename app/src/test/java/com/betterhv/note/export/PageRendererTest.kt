package com.betterhv.note.export

import org.junit.Test
import org.junit.Ignore

/**
 * PageRenderer tests require Android Canvas and are not runnable as JVM unit tests.
 * These tests should be moved to androidTest/ as instrumented tests.
 *
 * Keeping as placeholders for future instrumented test implementation.
 */
class PageRendererTest {

    @Test
    @Ignore("Requires Android Canvas - move to instrumented tests")
    fun renderToBitmap_emptyPage_producesBlankBitmap() {
        // Test implementation requires Android runtime
        // Move to app/src/androidTest/java/com/betterhv/note/export/PageRendererTest.kt
    }

    @Test
    @Ignore("Requires Android Canvas - move to instrumented tests")
    fun renderToBitmap_withStroke_producesBitmapWithContent() {
        // Test implementation requires Android runtime
        // Move to app/src/androidTest/java/com/betterhv/note/export/PageRendererTest.kt
    }
}
