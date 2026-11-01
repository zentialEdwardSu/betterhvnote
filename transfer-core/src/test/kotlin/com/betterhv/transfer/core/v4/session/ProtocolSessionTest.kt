package com.betterhv.transfer.core.v4.session

import com.betterhv.transfer.core.v4.codec.RecordCodec
import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.proto.v4.ContentKind
import com.betterhv.transfer.proto.v4.Frame
import com.betterhv.transfer.proto.v4.StreamData
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolSessionTest {
  private val psk = ByteArray(SessionKeys.KEY_BYTES) { (it * 13 + 5).toByte() }
  private val sessionBytes = ByteArray(SessionKeys.SESSION_ID_BYTES) { (it + 1).toByte() }
  private val sessionId = SessionKeys.sessionIdLong(sessionBytes)
  private val keys = SessionKeys.derive(
    psk,
    SessionKeys.keyId(psk),
    ByteArray(SessionKeys.NONCE_BYTES) { 3 },
    ByteArray(SessionKeys.NONCE_BYTES) { 4 },
    sessionBytes,
  )
  private val negotiated = NegotiatedCapabilities(
    transports = 1,
    maxStreams = 8,
    maxRecordBytes = 64 * 1024,
    maxInflightBytes = 4L * 1024 * 1024,
    kinds = listOf(ContentKind.CONTENT_IMAGE),
    features = 0,
  )

  private val client = ProtocolSession("phone", sessionId, keys, negotiated, initiator = true)
  private val server = ProtocolSession("tablet", sessionId, keys, negotiated, initiator = false)

  private fun dataFrame(text: String): Frame = Frame.newBuilder()
    .setStreamData(StreamData.newBuilder().setData(ByteString.copyFromUtf8(text)))
    .build()

  @Test
  fun exchangesFramesInBothDirections() {
    val c = client.handshakeLinkCipher(512)
    val s = server.handshakeLinkCipher(512)
    val inbound = s.open(c.seal(3, dataFrame("hello")).single())!!
    assertEquals(3, inbound.streamId)
    assertEquals("hello", inbound.frame.streamData.data.toStringUtf8())
    val back = c.open(s.seal(4, dataFrame("world")).single())!!
    assertEquals("world", back.frame.streamData.data.toStringUtf8())
  }

  @Test
  fun reassemblesFragmentedMessagesBeforeDeliveringTheFrame() {
    val c = client.handshakeLinkCipher(100)
    val s = server.handshakeLinkCipher(100)
    val records = c.seal(1, dataFrame("x".repeat(1_000)))
    assertTrue(records.size > 1)
    records.dropLast(1).forEach { assertNull(s.open(it)) }
    assertEquals(1_000, s.open(records.last())!!.frame.streamData.data.size())
  }

  @Test
  fun eachLinkHasItsOwnMessageNumberingAndKey() {
    val salt = ByteArray(32) { 9 }
    val bleC = client.handshakeLinkCipher(512)
    val bleS = server.handshakeLinkCipher(512)
    val lanC = client.linkCipher("tcp", salt, 64 * 1024)
    val lanS = server.linkCipher("tcp", salt, 64 * 1024)
    // Both links start at message 1 and interleave freely without tripping either replay window.
    repeat(3) { index ->
      assertEquals("b$index", bleS.open(bleC.seal(1, dataFrame("b$index")).single())!!.frame.streamData.data.toStringUtf8())
      assertEquals("l$index", lanS.open(lanC.seal(1, dataFrame("l$index")).single())!!.frame.streamData.data.toStringUtf8())
    }
    try {
      lanS.open(bleC.seal(1, dataFrame("wrong link")).single())
      fail("A BLE record was accepted on the LAN link")
    } catch (expected: RecordCodec.RecordException) {
      // different link key
    }
  }

  @Test
  fun differentConnectionSaltsGiveIndependentKeys() {
    val first = client.linkCipher("tcp", ByteArray(32) { 1 }, 64 * 1024)
    val second = server.linkCipher("tcp", ByteArray(32) { 2 }, 64 * 1024)
    try {
      second.open(first.seal(1, dataFrame("x")).single())
      fail("A record from another TCP connection was accepted")
    } catch (expected: RecordCodec.RecordException) {
      // salts differ
    }
  }

  @Test
  fun recordsForAnotherSessionAreRejected() {
    val other = ProtocolSession("phone", sessionId + 1, keys, negotiated, initiator = true)
    try {
      server.handshakeLinkCipher(512).open(other.handshakeLinkCipher(512).seal(1, dataFrame("x")).single())
      fail("A record for another session was accepted")
    } catch (expected: RecordCodec.RecordException) {
      // expected
    }
  }

  @Test
  fun tooSmallLinksAreRefused() {
    try {
      client.handshakeLinkCipher(20)
      fail("ATT MTU 23 was accepted")
    } catch (expected: RecordCodec.RecordException) {
      // expected
    }
  }
}
