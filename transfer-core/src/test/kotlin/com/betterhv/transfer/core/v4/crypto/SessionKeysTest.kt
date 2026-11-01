package com.betterhv.transfer.core.v4.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SessionKeysTest {
  private val random = Random(7)
  private val psk = ByteArray(SessionKeys.KEY_BYTES).also(random::nextBytes)
  private val clientNonce = ByteArray(SessionKeys.NONCE_BYTES).also(random::nextBytes)
  private val serverNonce = ByteArray(SessionKeys.NONCE_BYTES).also(random::nextBytes)
  private val sessionId = ByteArray(SessionKeys.SESSION_ID_BYTES).also(random::nextBytes)
  private val keyId = SessionKeys.keyId(psk)

  private fun derive(
    pskValue: ByteArray = psk,
    client: ByteArray = clientNonce,
    server: ByteArray = serverNonce,
    session: ByteArray = sessionId,
  ) = SessionKeys.derive(pskValue, keyId, client, server, session)

  @Test
  fun derivationIsDeterministic() {
    val first = derive()
    val second = derive()
    assertArrayEquals(first.helloKey, second.helloKey)
    assertArrayEquals(first.replyKey, second.replyKey)
    assertArrayEquals(first.directionKey(Direction.C2S), second.directionKey(Direction.C2S))
    assertArrayEquals(first.ticketKey, second.ticketKey)
  }

  @Test
  fun everyKeyHasItsOwnLabel() {
    val keys = derive()
    val all = listOf(
      keys.helloKey,
      keys.replyKey,
      keys.directionKey(Direction.C2S),
      keys.directionKey(Direction.S2C),
      keys.ticketKey,
    )
    assertEquals(all.size, all.map { it.toList() }.distinct().size)
  }

  @Test
  fun changingAnyInputChangesTheSessionKeys() {
    val base = derive()
    val otherPsk = psk.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    val otherClient = clientNonce.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    val otherServer = serverNonce.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    val otherSession = sessionId.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    listOf(
      derive(pskValue = otherPsk),
      derive(client = otherClient),
      derive(server = otherServer),
      derive(session = otherSession),
    ).forEach { candidate ->
      assertNotEquals(
        base.directionKey(Direction.C2S).toList(),
        candidate.directionKey(Direction.C2S).toList(),
      )
    }
  }

  @Test
  fun streamKeysAreSeparatedPerDirectionAndStream() {
    val keys = derive()
    val c2sOne = keys.streamKey(Direction.C2S, 1)
    assertNotEquals(c2sOne.toList(), keys.streamKey(Direction.C2S, 3).toList())
    assertNotEquals(c2sOne.toList(), keys.streamKey(Direction.S2C, 1).toList())
    assertEquals(SessionKeys.KEY_BYTES, c2sOne.size)
    assertTrue(
      runCatching { keys.streamKey(Direction.C2S, 0) }.isFailure,
    )
  }

  @Test
  fun keyIdIsStableAndFitsSixteenBits() {
    assertEquals(keyId, SessionKeys.keyId(psk.copyOf()))
    assertTrue(keyId in 0..0xffff)
    val other = psk.copyOf().also { it[31] = (it[31].toInt() xor 0x5a).toByte() }
    assertNotEquals(keyId, SessionKeys.keyId(other))
  }

  @Test
  fun nonceUsesTheMessageId() {
    assertArrayEquals(
      byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9),
      SessionKeys.nonceFor(9),
    )
    assertEquals(12, SessionKeys.nonceFor(Int.MAX_VALUE).size)
  }

  @Test
  fun rejectsWrongInputSizes() {
    assertTrue(
      runCatching {
        SessionKeys.derive(ByteArray(16), keyId, clientNonce, serverNonce, sessionId)
      }.isFailure,
    )
    assertTrue(
      runCatching {
        SessionKeys.derive(psk, keyId, ByteArray(8), serverNonce, sessionId)
      }.isFailure,
    )
    assertTrue(
      runCatching {
        SessionKeys.derive(psk, keyId, clientNonce, serverNonce, ByteArray(4))
      }.isFailure,
    )
    assertTrue(runCatching { SessionKeys.keyId(ByteArray(0)) }.isFailure)
  }
}
