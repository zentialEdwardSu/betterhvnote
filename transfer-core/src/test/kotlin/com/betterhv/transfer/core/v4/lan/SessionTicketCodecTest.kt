package com.betterhv.transfer.core.v4.lan

import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.proto.v4.ErrorCode
import com.betterhv.transfer.proto.v4.SessionTicket
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class SessionTicketCodecTest {
  private val psk = ByteArray(SessionKeys.KEY_BYTES) { (it * 5 + 2).toByte() }
  private val sessionId = ByteArray(SessionKeys.SESSION_ID_BYTES) { (it + 1).toByte() }
  private val nonce = ByteArray(16) { (it * 7).toByte() }

  private fun ticket(
    expiresAt: Long = 1_700_000_600_000L,
    listener: String = "phone",
    peer: String = "tablet",
  ): SessionTicket = SessionTicket.newBuilder()
    .setVersion(1)
    .setSessionId(ByteString.copyFrom(sessionId))
    .setListenerDeviceId(listener)
    .setPeerDeviceId(peer)
    .setExpiresAtMs(expiresAt)
    .setListenerPort(39817)
    .setConnectionNonce(ByteString.copyFrom(nonce))
    .build()

  @Test
  fun sealedTicketRoundTripsWithAllFields() {
    val envelope = SessionTicketCodec.seal(psk, ticket())
    assertEquals(SessionKeys.keyId(psk), SessionTicketCodec.keyId(envelope))
    assertArrayEquals(sessionId, SessionTicketCodec.sessionId(envelope))
    val opened = SessionTicketCodec.open(psk, envelope)
    assertEquals("phone", opened.listenerDeviceId)
    assertEquals("tablet", opened.peerDeviceId)
    assertEquals(39817, opened.listenerPort)
    assertArrayEquals(nonce, opened.connectionNonce.toByteArray())
  }

  @Test
  fun aDifferentPairingKeyCannotOpenTheTicket() {
    val envelope = SessionTicketCodec.seal(psk, ticket())
    val other = psk.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    assertTicketError(ErrorCode.ERROR_AUTHENTICATION_FAILED) {
      SessionTicketCodec.open(other, envelope)
    }
  }

  @Test
  fun tamperingWithTheSessionIdOrCiphertextFailsAuthentication() {
    val envelope = SessionTicketCodec.seal(psk, ticket())
    val sessionTampered = envelope.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
    assertTicketError(ErrorCode.ERROR_AUTHENTICATION_FAILED) {
      SessionTicketCodec.open(psk, sessionTampered)
    }
    val bodyTampered = envelope.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
    assertTicketError(ErrorCode.ERROR_AUTHENTICATION_FAILED) {
      SessionTicketCodec.open(psk, bodyTampered)
    }
  }

  @Test
  fun envelopeReaderRejectsTruncatedOrOversizedBodies() {
    val envelope = SessionTicketCodec.seal(psk, ticket())
    val truncated = envelope.copyOf(envelope.size - 1)
    assertTrue(
      runCatching {
        SessionTicketCodec.readEnvelope(DataInputStream(ByteArrayInputStream(truncated)))
      }.isFailure,
    )

    val decoded = SessionTicketCodec.readEnvelope(
      DataInputStream(ByteArrayInputStream(envelope)),
    )
    assertArrayEquals(envelope, decoded)
  }

  private fun assertTicketError(expected: ErrorCode, block: () -> Unit) {
    val error = runCatching(block).exceptionOrNull()
    if (error !is SessionTicketCodec.TicketException) {
      throw AssertionError("Expected TicketException $expected, got ${error ?: "success"}")
    }
    assertEquals(expected, error.code)
  }
}
