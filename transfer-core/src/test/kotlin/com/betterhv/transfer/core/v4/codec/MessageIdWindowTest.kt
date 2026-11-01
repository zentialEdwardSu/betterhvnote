package com.betterhv.transfer.core.v4.codec

import com.betterhv.transfer.proto.v4.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MessageIdWindowTest {
  @Test
  fun acceptsMonotonicIdsAndRejectsReplays() {
    val window = MessageIdWindow()
    window.accept(1)
    window.accept(2)
    window.accept(100)
    assertEquals(100, window.last())
    try {
      window.accept(100)
      fail("Expected a replay rejection")
    } catch (error: RecordCodec.RecordException) {
      assertEquals(ErrorCode.ERROR_REPLAY_REJECTED, error.code)
    }
    try {
      window.accept(99)
      fail("Expected an out-of-order rejection")
    } catch (error: RecordCodec.RecordException) {
      assertEquals(ErrorCode.ERROR_REPLAY_REJECTED, error.code)
    }
  }

  @Test
  fun resetAllowsANewSessionToStartAtOne() {
    val window = MessageIdWindow()
    window.accept(7)
    window.reset()
    window.accept(1)
    assertEquals(1, window.last())
  }
}
