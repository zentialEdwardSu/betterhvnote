package com.betterhv.transfer.windows

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsNativeLoaderTest {
    @Test
    fun bundledLibraryIsExtractedToAFileJnaCanLoad() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val path = JnaWindowsNativeApi.resolveLibraryPath(null)
        val extracted = File(path)

        assertEquals("notelink_windows.dll", extracted.name)
        assertTrue(extracted.isFile)
        assertTrue(extracted.length() > 0)
    }

    @Test
    fun explicitLibraryPathRemainsUnchanged() {
        assertEquals(
            "C:\\test\\notelink_windows.dll",
            JnaWindowsNativeApi.resolveLibraryPath("C:\\test\\notelink_windows.dll")
        )
    }
}
