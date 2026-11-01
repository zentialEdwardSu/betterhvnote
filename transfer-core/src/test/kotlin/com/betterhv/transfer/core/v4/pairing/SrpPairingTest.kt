package com.betterhv.transfer.core.v4.pairing

import com.betterhv.transfer.core.v4.codec.RecordCodec
import com.betterhv.transfer.proto.v4.ErrorCode
import com.betterhv.transfer.proto.v4.PairingFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SrpPairingTest {
  private var now = 1_000L

  private fun responder(code: String = "123456") =
    PairingResponder(code, "phone-id", "Xiaomi", clock = { now })

  private fun run(initiator: PairingInitiator, responder: PairingResponder): Pair<PairingOutcome?, PairingOutcome?> {
    val challenge = responder.onInit(initiator.start())
    val (result, phone) = responder.onConfirm(initiator.onChallenge(challenge))
    val note = runCatching { initiator.onResult(result) }.getOrNull()
    return note to phone
  }

  @Test
  fun matchingCodesDeriveTheSamePskAndExchangeFullIdentities() {
    val (note, phone) = run(PairingInitiator("123456", "note-id", "N10Pro"), responder())
    assertNotNull(note)
    assertNotNull(phone)
    assertArrayEquals(note!!.psk, phone!!.psk)
    assertEquals(32, note.psk.size)
    assertEquals("phone-id", note.peerDeviceId)
    assertEquals("Xiaomi", note.peerDeviceName)
    assertEquals("note-id", phone.peerDeviceId)
    assertEquals("N10Pro", phone.peerDeviceName)
  }

  @Test
  fun everyRunDerivesAFreshPsk() {
    val first = run(PairingInitiator("123456", "note-id", "N10Pro"), responder()).first!!
    val second = run(PairingInitiator("123456", "note-id", "N10Pro"), responder()).first!!
    assertFalse(first.psk.contentEquals(second.psk))
  }

  @Test
  fun aWrongCodeFailsOnBothSidesAndCountsAnAttempt() {
    val phone = responder()
    val initiator = PairingInitiator("654321", "note-id", "N10Pro")
    val challenge = phone.onInit(initiator.start())
    val (result, outcome) = phone.onConfirm(initiator.onChallenge(challenge))
    assertNull(outcome)
    assertEquals(ErrorCode.ERROR_AUTHENTICATION_FAILED, result.error)
    assertEquals(2, result.attemptsLeft)
    try {
      initiator.onResult(result)
      fail("A rejected code produced a key")
    } catch (expected: PairingException) {
      assertEquals(2, expected.attemptsLeft)
    }
  }

  @Test
  fun aForgedServerProofIsRejectedByTheNote() {
    val phone = responder()
    val initiator = PairingInitiator("123456", "note-id", "N10Pro")
    val (result, _) = phone.onConfirm(initiator.onChallenge(phone.onInit(initiator.start())))
    val forged = result.toBuilder().setServerProof(com.google.protobuf.ByteString.copyFrom(ByteArray(32))).build()
    try {
      initiator.onResult(forged)
      fail("A forged server proof was accepted")
    } catch (expected: PairingException) {
      assertEquals(ErrorCode.ERROR_AUTHENTICATION_FAILED, expected.code)
    }
  }

  @Test
  fun theResponderLocksAfterThreeWrongCodes() {
    val phone = responder()
    repeat(3) {
      val initiator = PairingInitiator("000000", "note-id", "N10Pro")
      phone.onConfirm(initiator.onChallenge(phone.onInit(initiator.start())))
    }
    assertFalse(phone.isActive())
    try {
      phone.onInit(PairingInitiator("123456", "note-id", "N10Pro").start())
      fail("A locked responder accepted another attempt")
    } catch (expected: PairingException) {
      assertEquals(ErrorCode.ERROR_RATE_LIMITED, expected.code)
    }
  }

  @Test
  fun theCodeExpires() {
    val phone = responder()
    now += PairingResponder.DEFAULT_TTL_MILLIS
    try {
      phone.onInit(PairingInitiator("123456", "note-id", "N10Pro").start())
      fail("An expired code was accepted")
    } catch (expected: PairingException) {
      assertEquals(ErrorCode.ERROR_TICKET_EXPIRED, expected.code)
    }
  }

  @Test
  fun aZeroPublicValueIsRejected() {
    val phone = responder()
    val init = PairingInitiator("123456", "note-id", "N10Pro").start().toBuilder()
      .setClientPublic(com.google.protobuf.ByteString.copyFrom(ByteArray(256)))
      .build()
    try {
      phone.onInit(init)
      fail("A = 0 was accepted")
    } catch (expected: PairingException) {
      assertEquals(ErrorCode.ERROR_AUTHENTICATION_FAILED, expected.code)
    }
  }

  @Test
  fun pairingFramesRoundTripThroughClearRecords() {
    val frame = PairingFrame.newBuilder().setInit(PairingInitiator("123456", "note-id", "N10Pro").start()).build()
    val records = PairingRecords.encode(frame, response = false, messageId = 1, maxFragmentBody = 150)
    assertTrue(records.size > 1)
    assertTrue(records.all(PairingRecords::isPairingRecord))
    val reassembler = PairingRecords.Reassembler()
    records.dropLast(1).forEach { assertNull(reassembler.add(it)) }
    assertEquals(frame, reassembler.add(records.last()))
  }

  @Test
  fun encryptedRecordsAreNotMistakenForPairingRecords() {
    val header = RecordCodec.Header(RecordCodec.FLAG_ENCRYPTED, 0, 1L, 1, 1)
    val record = RecordCodec.seal(ByteArray(32), header, byteArrayOf(1, 2, 3), 200).single()
    assertFalse(PairingRecords.isPairingRecord(record))
  }

  @Test
  fun generatedCodesAreSixDigits() {
    repeat(100) { assertTrue(PairingCode.isValid(PairingCode.generate())) }
  }
}
