package com.betterhv.transfer.core.v4.session

import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.core.v4.codec.RecordCodec
import com.betterhv.transfer.core.v4.crypto.Direction
import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.ClientHello
import com.betterhv.transfer.proto.v4.ContentKind
import com.betterhv.transfer.proto.v4.ErrorCode
import com.betterhv.transfer.proto.v4.FetchRequest
import com.betterhv.transfer.proto.v4.Offer
import com.betterhv.transfer.proto.v4.Request
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HandshakeEngineTest {
  private val psk = ByteArray(SessionKeys.KEY_BYTES) { (it * 3 + 1).toByte() }
  private val keyId = SessionKeys.keyId(psk)
  private val pairing = PairingKey("tablet-id", psk)
  private val now = 1_700_000_000_000L
  private val randomCounter = AtomicInteger(1)
  private val randomBytes: (Int) -> ByteArray = { size ->
    ByteArray(size) { randomCounter.getAndIncrement().toByte() }
  }

  private val clientCapabilities = CapabilitySet.newBuilder()
    .setTransports(1 or 2)
    .setMaxStreams(8)
    .setMaxRecordBytes(64 * 1024)
    .addKinds(ContentKind.CONTENT_IMAGE)
    .addKinds(ContentKind.CONTENT_PDF)
    .setFeatures(1 or 8)
    .build()

  private val serverCapabilities = CapabilitySet.newBuilder()
    .setTransports(1)
    .setMaxStreams(4)
    .setMaxRecordBytes(32 * 1024)
    .addKinds(ContentKind.CONTENT_PDF)
    .setFeatures(8 or 16)
    .build()

  private val request = Request.newBuilder()
    .setFetch(FetchRequest.newBuilder().setKind(ContentKind.CONTENT_PDF).setMaxItems(2))
    .build()

  private fun offer() = Offer.newBuilder()
    .setItemId(com.google.protobuf.ByteString.copyFrom(ByteArray(16) { 7 }))
    .setKind(ContentKind.CONTENT_PDF)
    .setByteLength(1_024)
    .setDisplayName("page.pdf")
    .build()

  private fun client(clock: () -> Long = { now }) = ClientHandshake(
    pairingKey = psk,
    deviceId = pairing.deviceId,
    capabilities = clientCapabilities,
    request = request,
    clock = clock,
    randomBytes = randomBytes,
  )

  private fun server(clock: () -> Long = { now }, guard: HandshakeReplayGuard = HandshakeReplayGuard()) =
    ServerHandshake(
      pairings = PairingKeyStore { id -> if (id == keyId) pairing else null },
      capabilities = serverCapabilities,
      offers = { listOf(offer()) },
      replayGuard = guard,
      clock = clock,
      randomBytes = randomBytes,
    )

  @Test
  fun oneRoundTripEstablishesMatchingKeysAndNegotiatedLimits() {
    val client = client()
    val helloRecords = client.start()
    val (replyRecords, serverSession) = server().accept(helloRecords)
    val clientSession = client.complete(replyRecords)

    assertArrayEquals(serverSession.sessionId, clientSession.sessionId)
    assertArrayEquals(
      serverSession.keys.directionKey(Direction.C2S),
      clientSession.keys.directionKey(Direction.C2S),
    )
    assertArrayEquals(
      serverSession.keys.directionKey(Direction.S2C),
      clientSession.keys.directionKey(Direction.S2C),
    )
    assertEquals(1, clientSession.offers.size)
    assertEquals("page.pdf", clientSession.offers.single().displayName)
    assertEquals(1, clientSession.capabilities.transports)
    assertEquals(4, clientSession.capabilities.maxStreams)
    assertEquals(32 * 1024, clientSession.capabilities.maxRecordBytes)
    assertEquals(listOf(ContentKind.CONTENT_PDF), clientSession.capabilities.kinds)
    assertEquals(8, clientSession.capabilities.features)
    assertEquals(4, clientSession.serverHello.major)
    assertEquals(0, clientSession.serverHello.minor)
  }

  @Test
  fun unknownKeyIdIsRejectedBeforeAnySessionExists() {
    val server = ServerHandshake(
      pairings = PairingKeyStore { null },
      capabilities = serverCapabilities,
      clock = { now },
      randomBytes = randomBytes,
    )
    val records = client().start()
    assertHandshakeError(ErrorCode.ERROR_UNKNOWN_PAIRING) { server.accept(records) }
  }

  @Test
  fun aHelloForAnotherDeviceIsRejected() {
    val other = PairingKey("someone-else", psk)
    val server = ServerHandshake(
      pairings = PairingKeyStore { other },
      capabilities = serverCapabilities,
      clock = { now },
      randomBytes = randomBytes,
    )
    val records = client().start()
    assertHandshakeError(ErrorCode.ERROR_AUTHENTICATION_FAILED) { server.accept(records) }
  }

  @Test
  fun tamperedHelloIsRejected() {
    val records = client().start().toMutableList()
    val tampered = records[0].copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
    records[0] = tampered
    assertHandshakeError(ErrorCode.ERROR_AUTHENTICATION_FAILED) { server().accept(records) }
  }

  @Test
  fun aReplayedHelloIsRejectedByTheSameGuard() {
    val guard = HandshakeReplayGuard()
    val records = client().start()
    server(guard = guard).accept(records)
    assertHandshakeError(ErrorCode.ERROR_REPLAY_REJECTED) { server(guard = guard).accept(records) }
  }

  @Test
  fun clockSkewIsReportedAsRetryable() {
    val skewed = client(clock = { now - 10 * 60_000 }).start()
    val error = assertHandshakeError(ErrorCode.ERROR_CLOCK_SKEW) { server().accept(skewed) }
    assertTrue(error.retryable)
  }

  @Test
  fun tamperedReplyIsRejectedByTheClient() {
    val client = client()
    val helloRecords = client.start()
    val (replyRecords, _) = server().accept(helloRecords)
    val tampered = replyRecords.toMutableList()
    tampered[0] = tampered[0].copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
    assertHandshakeError(ErrorCode.ERROR_AUTHENTICATION_FAILED) { client.complete(tampered) }
  }

  @Test
  fun majorMismatchIsRejectedBeforeTheReplayWindow() {
    val nonce = ByteArray(SessionKeys.NONCE_BYTES) { 9 }
    val hello = ClientHello.newBuilder()
      .setMajor(5)
      .setDeviceId(pairing.deviceId)
      .setClientTimeMs(now)
      .setCapabilities(clientCapabilities)
      .setRequest(request)
      .build()
    val header = RecordCodec.Header(
      RecordCodec.FLAG_ENCRYPTED or RecordCodec.FLAG_HELLO,
      keyId,
      0,
      0,
      1,
    )
    val records = RecordCodec.seal(
      SessionKeys.helloKey(psk, keyId, nonce),
      header,
      hello.toByteArray(),
      256,
      nonce,
    )
    assertHandshakeError(ErrorCode.ERROR_UNSUPPORTED_PROTOCOL) { server().accept(records) }
  }

  private fun assertHandshakeError(expected: ErrorCode, block: () -> Unit): HandshakeException {
    val error = runCatching(block).exceptionOrNull()
    if (error !is HandshakeException) {
      throw AssertionError("Expected HandshakeException $expected, got ${error ?: "success"}")
    }
    assertEquals(expected, error.code)
    return error
  }
}
