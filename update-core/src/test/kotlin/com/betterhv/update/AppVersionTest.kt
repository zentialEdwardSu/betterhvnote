package com.betterhv.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {
    @Test
    fun `missing components are treated as zero`() {
        assertEquals(0, assertNotNull(AppVersion.parse("0.2")).compareTo(assertNotNull(AppVersion.parse("0.2.0"))))
        assertTrue(assertNotNull(AppVersion.parse("1.2.1")) > assertNotNull(AppVersion.parse("1.2")))
    }

    @Test
    fun `stable version sorts after prerelease`() {
        assertTrue(assertNotNull(AppVersion.parse("1.0.0")) > assertNotNull(AppVersion.parse("1.0.0-rc.2")))
        assertTrue(assertNotNull(AppVersion.parse("1.0.0-rc.10")) > assertNotNull(AppVersion.parse("1.0.0-rc.2")))
        assertTrue(assertNotNull(AppVersion.parse("0.1-phase6")) < assertNotNull(AppVersion.parse("0.1.0")))
    }

    @Test
    fun `invalid version is rejected`() {
        assertNull(AppVersion.parse("v1.2.3"))
        assertNull(AppVersion.parse("1.02.3"))
        assertNull(AppVersion.parse("latest"))
    }
}
