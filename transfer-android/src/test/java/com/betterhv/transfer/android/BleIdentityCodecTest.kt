package com.betterhv.transfer.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BleIdentityCodecTest {
    @Test fun androidIdentityUsesSharedGoldenLayout() {
        val id = "00112233-4455-6677-8899-aabbccddeeff"
        val encoded = BleIdentityCodec.encode(id, "NoteLink", 2, 3)
        assertEquals(100, encoded.size)
        assertArrayEquals(byteArrayOf(2, 2, 3, 36), encoded.copyOfRange(0, 4))
        val decoded = BleIdentityCodec.decode(encoded)
        assertEquals(id, decoded.deviceId)
        assertEquals("NoteLink", decoded.deviceName)
    }

    @Test fun malformedIdentityIsRejected() {
        try {
            BleIdentityCodec.decode(ByteArray(12))
            fail("Expected malformed identity to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
