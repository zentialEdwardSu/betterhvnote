package com.betterhv.transfer.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BleIdentityCodecTest {
  @Test fun androidIdentityUsesSharedGoldenLayout() {
    val id = "00112233-4455-6677-8899-aabbccddeeff"
    val encoded = BleIdentityCodec.encode(id, "NoteLink", 2, 3, 4)
    assertEquals(101, encoded.size)
    assertArrayEquals(byteArrayOf(3, 2, 3, 4, 36), encoded.copyOfRange(0, 5))
    val decoded = BleIdentityCodec.decode(encoded)
    assertEquals(id, decoded.deviceId)
    assertEquals("NoteLink", decoded.deviceName)
    assertEquals(4, decoded.pdfCount)
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
