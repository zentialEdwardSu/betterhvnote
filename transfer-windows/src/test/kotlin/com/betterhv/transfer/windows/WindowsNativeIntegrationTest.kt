package com.betterhv.transfer.windows

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class WindowsNativeIntegrationTest {
    @Test fun packagedDllLoadsAndDpapiRoundTrips() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        JnaWindowsNativeApi.load().use { api ->
            val source = "NoteLink-native-contract".encodeToByteArray()
            val protected = api.protect(source)
            assertTrue(protected.isNotEmpty())
            assertContentEquals(source, api.unprotect(protected))
            assertTrue(api.capabilities().dataProtection)
        }
    }
}
