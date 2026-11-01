package com.betterhv.transfer.core.v4.lan

import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.core.v4.session.PairingKey
import com.betterhv.transfer.core.v4.session.PairingKeyStore
import com.betterhv.transfer.proto.v4.SessionTicket
import com.google.protobuf.ByteString
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TicketAuthenticatorTest {
  private val psk = ByteArray(SessionKeys.KEY_BYTES) { (it * 23 + 11).toByte() }
  private val keyId = SessionKeys.keyId(psk)
  private val pairing = PairingKey("tablet", psk)
  private val now = 1_700_000_000_000L
  private val sessionId = ByteArray(SessionKeys.SESSION_ID_BYTES) { (it + 1).toByte() }

  private fun envelope(expiresAt: Long = now + 60_000, nonceSeed: Int = 1): ByteArray =
    SessionTicketCodec.seal(
      psk,
      SessionTicket.newBuilder()
        .setVersion(1)
        .setSessionId(ByteString.copyFrom(sessionId))
        .setListenerDeviceId("phone")
        .setPeerDeviceId("tablet")
        .setExpiresAtMs(expiresAt)
        .setListenerPort(39817)
        .setConnectionNonce(ByteString.copyFrom(ByteArray(16) { (it + nonceSeed).toByte() }))
        .build(),
    )

  private fun authenticator(sessionActive: (ByteArray) -> Boolean = { true }) = TicketAuthenticator(
    pairings = PairingKeyStore { id -> if (id == keyId) pairing else null },
    clock = { now },
    sessionActive = sessionActive,
  )

  @Test
  fun acceptsAValidTicketOnlyOnce() {
    val authenticator = authenticator()
    val envelope = envelope()
    assertNotNull(authenticator.authenticate(envelope, "phone"))
    assertNull("the connection nonce must be single use", authenticator.authenticate(envelope, "phone"))
  }

  @Test
  fun rejectsExpiredTicketsAndUnknownSessions() {
    assertNull(authenticator().authenticate(envelope(expiresAt = now), "phone"))
    assertNull(authenticator(sessionActive = { false }).authenticate(envelope(), "phone"))
  }

  @Test
  fun rejectsTicketsForAnotherListenerOrPeer() {
    val authenticator = authenticator()
    assertNull(authenticator.authenticate(envelope(), "another-listener"))
    val foreign = SessionTicketCodec.seal(
      psk,
      SessionTicket.newBuilder()
        .setVersion(1)
        .setSessionId(ByteString.copyFrom(sessionId))
        .setListenerDeviceId("phone")
        .setPeerDeviceId("stranger")
        .setExpiresAtMs(now + 60_000)
        .setConnectionNonce(ByteString.copyFrom(ByteArray(16) { 9 }))
        .build(),
    )
    assertNull(authenticator.authenticate(foreign, "phone"))
  }
}
