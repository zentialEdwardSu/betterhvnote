package com.betterhv.transfer.core.v4.codec

import com.betterhv.transfer.proto.v4.ErrorCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class RecordCodecTest {
  private val key = ByteArray(32) { (it * 7 + 1).toByte() }
  private val random = Random(42)

  private fun header(
    messageId: Int = 1,
    flags: Int = RecordCodec.FLAG_ENCRYPTED,
    sessionId: Long = 0x1234L,
    streamId: Int = 0,
    keyId: Int = 0x0102,
  ) = RecordCodec.Header(flags, keyId, sessionId, streamId, messageId)

  private fun payload(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

  @Test
  fun roundTripsAcrossSizesAndFragmentBudgets() {
    val fragmentBudgets = listOf(1, 17, 64, 220, 1025)
    val sizes = listOf(0, 1, 15, 16, 100, 1024, 4096, 65536)
    for (size in sizes) {
      val plaintext = payload(size)
      for (budget in fragmentBudgets) {
        val fragments = (size + RecordCodec.TAG_BYTES + budget - 1) / budget
        if (fragments > RecordCodec.MAX_FRAGMENTS) continue
        val records = RecordCodec.seal(key, header(messageId = size + 1), plaintext, budget)
        assertTrue(records.isNotEmpty())
        assertTrue(records.size <= RecordCodec.MAX_FRAGMENTS)
        assertArrayEquals(
          "size=$size budget=$budget",
          plaintext,
          RecordCodec.open(key, records),
        )
      }
    }
  }

  @Test
  fun helloRecordsCarryAndAuthenticateTheHandshakeNonce() {
    val nonce = payload(RecordCodec.HELLO_NONCE_BYTES)
    val plaintext = payload(300)
    val records = RecordCodec.seal(
      key,
      header(flags = RecordCodec.FLAG_ENCRYPTED or RecordCodec.FLAG_HELLO),
      plaintext,
      220,
      nonce,
    )
    assertEquals(RecordCodec.HELLO_HEADER_BYTES.toByte(), records.first()[4])
    assertArrayEquals(plaintext, RecordCodec.open(key, records))
  }

  @Test
  fun rejectsTamperedCiphertext() {
    val records = RecordCodec.seal(key, header(), payload(200), 220).toMutableList()
    records[0] = records[0].copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
    assertRecordError(ErrorCode.ERROR_AUTHENTICATION_FAILED) { RecordCodec.open(key, records) }
  }

  @Test
  fun rejectsTamperedRoutingHeader() {
    val records = RecordCodec.seal(key, header(), payload(200), 220).toMutableList()
    val tampered = records[0].copyOf()
    tampered[8] = (tampered[8].toInt() xor 1).toByte()
    records[0] = tampered
    assertRecordError(ErrorCode.ERROR_AUTHENTICATION_FAILED) { RecordCodec.open(key, records) }
  }

  @Test
  fun rejectsOutOfOrderAndMissingFragments() {
    val records = RecordCodec.seal(key, header(), payload(1000), 64)
    assertTrue(records.size > 2)
    assertRecordError(ErrorCode.ERROR_REPLAY_REJECTED) { RecordCodec.open(key, records.reversed()) }
    assertRecordError(ErrorCode.ERROR_MESSAGE_TOO_LARGE) { RecordCodec.open(key, records.drop(1)) }
  }

  @Test
  fun rejectsOversizedMessagesAndTooManyFragments() {
    assertRecordError(ErrorCode.ERROR_MESSAGE_TOO_LARGE) {
      RecordCodec.seal(key, header(), payload(RecordCodec.MAX_MESSAGE_BYTES + 1), 220)
    }
    assertRecordError(ErrorCode.ERROR_MESSAGE_TOO_LARGE) {
      // 64 KiB ciphertext with one byte per fragment needs more than the fragment cap.
      RecordCodec.seal(key, header(), payload(RecordCodec.MAX_MESSAGE_BYTES), 1)
    }
  }

  @Test
  fun rejectsUnknownFlagsAndTruncatedRecords() {
    val record = RecordCodec.seal(key, header(), payload(10), 220).single()
    val unknownFlags = record.copyOf().also { it[3] = 0x7f }
    assertRecordError(ErrorCode.ERROR_PROTOCOL_MISMATCH) { RecordCodec.parse(unknownFlags) }
    assertRecordError(ErrorCode.ERROR_MESSAGE_TOO_LARGE) { RecordCodec.parse(record.copyOf(10)) }
  }

  @Test
  fun mtuBudgetNeverDropsBelowOneByte() {
    assertEquals(216, RecordCodec.fragmentBudget(247))
    assertEquals(200, RecordCodec.fragmentBudget(247, hello = true))
    assertEquals(1, RecordCodec.fragmentBudget(RecordCodec.HEADER_BYTES + 4))
    assertEquals(1, RecordCodec.fragmentBudget(RecordCodec.HELLO_HEADER_BYTES + 4, hello = true))
    assertRecordError(ErrorCode.ERROR_UNSUPPORTED_MODE) {
      RecordCodec.fragmentBudget(RecordCodec.HEADER_BYTES + 3)
    }
    assertRecordError(ErrorCode.ERROR_UNSUPPORTED_MODE) {
      RecordCodec.fragmentBudget(RecordCodec.HELLO_HEADER_BYTES + 3, hello = true)
    }
  }

  @Test
  fun nonceIsDerivedFromTheMessageId() {
    assertArrayEquals(
      byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7),
      RecordCodec.nonceFor(7),
    )
    assertTrue(!RecordCodec.nonceFor(7).contentEquals(RecordCodec.nonceFor(8)))
  }

  private fun assertRecordError(expected: ErrorCode, block: () -> Unit) {
    try {
      block()
      fail("Expected RecordException $expected")
    } catch (error: RecordCodec.RecordException) {
      assertEquals(expected, error.code)
    }
  }
}
