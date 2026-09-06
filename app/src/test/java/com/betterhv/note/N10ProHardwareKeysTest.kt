package com.betterhv.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class N10ProHardwareKeysTest {
  @Test
  fun `recognizes all eight raw body keys`() {
    (489..496).forEachIndexed { index, keyCode ->
      assertEquals(HardwareKeyId.entries[index], N10ProHardwareKeys.keyId(keyCode))
      assertEquals("K${index + 1}", N10ProHardwareKeys.keyName(keyCode))
    }
  }

  @Test
  fun `recognizes the eight pass through codes used for device verification`() {
    val expected = mapOf(
      519 to HardwareKeyId.K1,
      522 to HardwareKeyId.K2,
      525 to HardwareKeyId.K3,
      516 to HardwareKeyId.K4,
      524 to HardwareKeyId.K5,
      526 to HardwareKeyId.K6,
      527 to HardwareKeyId.K7,
      531 to HardwareKeyId.K8,
    )

    expected.forEach { (keyCode, name) ->
      assertEquals(name, N10ProHardwareKeys.keyId(keyCode))
    }
  }

  @Test
  fun `does not claim either return key`() {
    assertNull(N10ProHardwareKeys.keyName(4))
    assertNull(N10ProHardwareKeys.keyName(498))
  }
}
