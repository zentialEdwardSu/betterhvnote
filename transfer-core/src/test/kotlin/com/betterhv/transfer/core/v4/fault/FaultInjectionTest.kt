package com.betterhv.transfer.core.v4.fault

import com.betterhv.transfer.core.v4.codec.RecordCodec
import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.core.v4.lan.SessionTicketCodec
import com.betterhv.transfer.core.v4.session.LinkCipher
import com.betterhv.transfer.core.v4.session.NegotiatedCapabilities
import com.betterhv.transfer.core.v4.session.ProtocolSession
import com.betterhv.transfer.proto.v4.ContentKind
import com.betterhv.transfer.proto.v4.Frame
import com.betterhv.transfer.proto.v4.StreamData
import com.google.protobuf.ByteString
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * Deterministic fault injection for the record/authentication surface: random bytes, truncation,
 * loss, duplication and an oversized fragment count. The parser may reject data, but it must fail
 * with a typed exception instead of crashing or returning a half-decoded record.
 */
class FaultInjectionTest {
  private val psk = ByteArray(SessionKeys.KEY_BYTES) { (it * 17 + 9).toByte() }
  private val sessionBytes = ByteArray(SessionKeys.SESSION_ID_BYTES) { (it + 1).toByte() }
  private val sessionId = SessionKeys.sessionIdLong(sessionBytes)
  private val keys = SessionKeys.derive(
    psk,
    SessionKeys.keyId(psk),
    ByteArray(SessionKeys.NONCE_BYTES) { 3 },
    ByteArray(SessionKeys.NONCE_BYTES) { 4 },
    sessionBytes,
  )
  private val capabilities = NegotiatedCapabilities(
    transports = 1,
    maxStreams = 8,
    maxRecordBytes = 64 * 1024,
    maxInflightBytes = 4L * 1024 * 1024,
    kinds = listOf(ContentKind.CONTENT_IMAGE),
    features = 0,
  )
  private val random = Random(42)

  private fun cipher(initiator: Boolean, recordBytes: Int = 128): LinkCipher = ProtocolSession(
    peerDeviceId = if (initiator) "phone" else "tablet",
    sessionId = sessionId,
    keys = keys,
    capabilities = capabilities,
    initiator = initiator,
  ).handshakeLinkCipher(recordBytes)

  private fun dataFrame(size: Int): Frame = Frame.newBuilder()
    .setStreamData(StreamData.newBuilder().setData(ByteString.copyFrom(ByteArray(size) { 7 })))
    .build()

  @Test
  fun randomBytesNeverEscapeAsUntypedCrashes() {
    repeat(20_000) {
      val bytes = ByteArray(random.nextInt(160)).also(random::nextBytes)
      try {
        RecordCodec.parse(bytes)
      } catch (expected: RecordCodec.RecordException) {
        // rejected as intended
      } catch (expected: IllegalArgumentException) {
        // bounds/require failures are typed enough for callers
      } catch (error: Throwable) {
        fail("Unexpected ${error.javaClass.name} for ${bytes.size} random bytes")
      }
    }
  }

  @Test
  fun truncatedRecordsAreRejected() {
    val records = cipher(initiator = true).seal(1, dataFrame(300))
    records.forEach { record ->
      for (length in 0 until RecordCodec.HEADER_BYTES) {
        try {
          RecordCodec.parse(record.copyOf(length))
        } catch (expected: RecordCodec.RecordException) {
          continue
        } catch (expected: IllegalArgumentException) {
          continue
        }
        fail("Truncated header of $length bytes was accepted")
      }
    }
  }

  @Test
  fun droppedFragmentPreventsDeliveryAndTheLinkRejectsTheNextMessage() {
    val client = cipher(initiator = true, recordBytes = 92)
    val server = cipher(initiator = false, recordBytes = 92)
    val records = client.seal(1, dataFrame(300))
    assertTrue(records.size > 1)
    records.dropLast(1).forEach { assertNull(server.open(it)) }
    val next = client.seal(1, dataFrame(10)).single()
    expectRejected("A message after a lost fragment") { server.open(next) }
  }

  @Test
  fun duplicatedRecordIsRejectedAsAReplay() {
    val client = cipher(initiator = true)
    val server = cipher(initiator = false)
    val record = client.seal(1, dataFrame(10)).single()
    assertNotNull(server.open(record))
    expectRejected("Duplicate record") { server.open(record) }
  }

  @Test
  fun reorderedFragmentsAreRejectedInsteadOfMisassembled() {
    val client = cipher(initiator = true, recordBytes = 92)
    val server = cipher(initiator = false, recordBytes = 92)
    val records = client.seal(1, dataFrame(300))
    assertTrue(records.size > 1)
    expectRejected("Reordered fragments") { records.reversed().forEach { server.open(it) } }
  }

  @Test
  fun corruptedCiphertextFailsAuthenticationWithoutAdvancingTheWindow() {
    val client = cipher(initiator = true)
    val server = cipher(initiator = false)
    val record = client.seal(1, dataFrame(10)).single()
    val corrupted = record.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 0xff).toByte() }
    expectRejected("Corrupted ciphertext") { server.open(corrupted) }
    assertNotNull("The genuine record must still be accepted", server.open(record))
  }

  @Test
  fun aRecordFromAnotherLinkKeyIsRejected() {
    val session = ProtocolSession("phone", sessionId, keys, capabilities, initiator = true)
    val lan = session.linkCipher("tcp", ByteArray(32) { 1 }, 64 * 1024)
    val server = cipher(initiator = false)
    expectRejected("Record sealed for another link") { server.open(lan.seal(1, dataFrame(10)).single()) }
  }

  @Test
  fun oversizedFragmentCountIsRejected() {
    val header = ByteArray(RecordCodec.HEADER_BYTES)
    header[0] = 'N'.code.toByte()
    header[1] = 'L'.code.toByte()
    header[2] = 4
    header[3] = 0
    // Fragment count lives in the last two header bytes; encode 0xFFFF.
    header[RecordCodec.HEADER_BYTES - 2] = 0xFF.toByte()
    header[RecordCodec.HEADER_BYTES - 1] = 0xFF.toByte()
    try {
      RecordCodec.parse(header)
      fail("Oversized fragment count was accepted")
    } catch (expected: RecordCodec.RecordException) {
      // expected
    } catch (expected: IllegalArgumentException) {
      // expected
    }
  }

  private fun expectRejected(what: String, block: () -> Unit) {
    try {
      block()
    } catch (expected: RecordCodec.RecordException) {
      return
    }
    fail("$what was accepted")
  }

  @Test
  fun randomTicketBytesAreRejectedCleanly() {
    repeat(5_000) {
      val bytes = ByteArray(random.nextInt(96)).also(random::nextBytes)
      try {
        SessionTicketCodec.open(psk, bytes)
      } catch (expected: SessionTicketCodec.TicketException) {
        // expected
      } catch (expected: RecordCodec.RecordException) {
        // expected
      } catch (expected: IllegalArgumentException) {
        // expected
      } catch (error: Throwable) {
        fail("Unexpected ${error.javaClass.name} for ${bytes.size} ticket bytes")
      }
    }
  }
}
