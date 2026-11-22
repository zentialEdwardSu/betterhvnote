package com.betterhv.transfer.core.v4.notelink

import com.betterhv.transfer.core.ContentCounts
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.v4.codec.RecordCodec
import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.core.v4.pairing.PairingCode
import com.betterhv.transfer.core.v4.pairing.PairingInitiator
import com.betterhv.transfer.core.v4.pairing.PairingOutcome
import com.betterhv.transfer.core.v4.pairing.PairingRecords
import com.betterhv.transfer.core.v4.pairing.PairingResponder
import com.betterhv.transfer.core.v4.session.ChannelRecordLink
import com.betterhv.transfer.core.v4.session.HandshakeException
import com.betterhv.transfer.core.v4.session.PairingKey
import com.betterhv.transfer.core.v4.session.PairingKeyStore
import com.betterhv.transfer.core.v4.session.PeerConnection
import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.ErrorCode
import com.betterhv.transfer.proto.v4.PairResult
import com.betterhv.transfer.proto.v4.PairingFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class NoteLinkRegressionTest {
  @Test
  fun handshakeReassemblesMultiFragmentPairingErrorReply() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var clientLink: ChannelRecordLink
    clientLink = ChannelRecordLink(
      label = PeerConnection.LABEL_BLE,
      maxRecordBytes = 120,
      sender = {
        val errorReply = PairingFrame.newBuilder()
          .setResult(PairResult.newBuilder().setError(ErrorCode.ERROR_CLOCK_SKEW).setAttemptsLeft(3))
          .build()
        val fragments = PairingRecords.encode(
          errorReply,
          response = true,
          messageId = 1,
          maxFragmentBody = 2,
        )
        assertTrue(fragments.size > 1)
        fragments.forEach { clientLink.offer(it) }
      },
    )
    try {
      try {
        NoteLinkClient.handshake(
          scope = scope,
          link = clientLink,
          pairingKey = ByteArray(SessionKeys.KEY_BYTES) { 1 },
          localDeviceId = "note-1",
          peerDeviceId = "host-1",
          capabilities = CapabilitySet.getDefaultInstance(),
        )
        fail("Expected HandshakeException")
      } catch (expected: HandshakeException) {
        assertEquals(ErrorCode.ERROR_CLOCK_SKEW, expected.code)
        assertTrue(expected.retryable)
      }
    } finally {
      clientLink.close()
      scope.cancel()
    }
  }

  @Test
  fun serverNotifiesHostWithEmptyLinksWhenSessionCloses() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val port = ServerSocket(0).use { it.localPort }
    val sessionChanges = CopyOnWriteArrayList<Pair<String, List<String>>>()
    val paired = AtomicReference<PairingOutcome?>()
    val responder = AtomicReference<PairingResponder?>()
    val bleToClient = HashMap<String, ChannelRecordLink>()
    val host = object : NoteLinkHost {
      override val localDeviceId = "host-close-test"
      override val localDeviceName = "Host"
      override fun capabilities() = CapabilitySet.getDefaultInstance()
      override fun pairings() = PairingKeyStore { keyId ->
        paired.get()?.takeIf { SessionKeys.keyId(it.psk) == keyId }?.let { PairingKey(it.peerDeviceId, it.psk) }
      }
      override fun pairingResponder() = responder.get()
      override fun onPaired(outcome: PairingOutcome) {
        paired.set(outcome)
        responder.set(null)
      }
      override fun counts(peerDeviceId: String) = ContentCounts(0, 0)
      override fun lease(peerDeviceId: String, kind: ContentKind) = null
      override fun leased(peerDeviceId: String, itemId: UUID) = null
      override fun heartbeat(peerDeviceId: String, itemId: UUID) = false
      override fun commit(peerDeviceId: String, itemId: UUID) = false
      override fun release(peerDeviceId: String, itemId: UUID) = false
      override fun onSessionChanged(peerDeviceId: String, links: List<String>) {
        sessionChanges += peerDeviceId to links
      }
    }
    val udpFreeServer = NoteLinkServer(
      host = host,
      sendBle = { key, record -> bleToClient[key]?.offer(record) },
      lanPort = port,
      discoveryPort = java.net.DatagramSocket(0).use { it.localPort },
    )
    try {
      udpFreeServer.start()
      val code = PairingCode.generate()
      responder.set(PairingResponder(code, host.localDeviceId, host.localDeviceName))
      lateinit var pairLink: ChannelRecordLink
      pairLink = ChannelRecordLink(
        PeerConnection.LABEL_BLE,
        182,
        { udpFreeServer.onBleRecord("k1", it) },
        { udpFreeServer.onBleClosed("k1") },
      )
      bleToClient["k1"] = pairLink
      val outcome = pairLink.use { NoteLinkClient.pair(it, PairingInitiator(code, "note-close", "Note")) }

      lateinit var hsLink: ChannelRecordLink
      hsLink = ChannelRecordLink(
        PeerConnection.LABEL_BLE,
        182,
        { udpFreeServer.onBleRecord("k2", it) },
        { udpFreeServer.onBleClosed("k2") },
      )
      bleToClient["k2"] = hsLink
      val session = NoteLinkClient.handshake(
        scope,
        hsLink,
        outcome.psk,
        "note-close",
        outcome.peerDeviceId,
        CapabilitySet.getDefaultInstance(),
      )
      withTimeout(2_000) { serverSessionUp(sessionChanges, "note-close") }
      udpFreeServer.onBleClosed("k2")
      session.connection.close()
      withTimeout(2_000) {
        while (sessionChanges.lastOrNull() != ("note-close" to emptyList<String>())) {
          kotlinx.coroutines.delay(10)
        }
      }
      assertEquals("note-close" to emptyList<String>(), sessionChanges.last())
    } finally {
      udpFreeServer.close()
      scope.cancel()
    }
  }

  private suspend fun serverSessionUp(
    events: CopyOnWriteArrayList<Pair<String, List<String>>>,
    deviceId: String,
  ) {
    while (events.none { it.first == deviceId && it.second.isNotEmpty() }) {
      kotlinx.coroutines.delay(10)
    }
  }

  @Test
  fun attachTcpClosesNonWinningCandidatesWhenMultipleSucceed() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val port1 = ServerSocket(0).use { it.localPort }
    val udpPort1 = java.net.DatagramSocket(0).use { it.localPort }
    val ble1 = HashMap<String, ChannelRecordLink>()
    val paired1 = AtomicReference<PairingOutcome?>()
    val responder1 = AtomicReference<PairingResponder?>()

    val host1 = object : NoteLinkHost {
      override val localDeviceId = "host-1"
      override val localDeviceName = "Host"
      override fun capabilities() = CapabilitySet.getDefaultInstance()
      override fun pairings() = PairingKeyStore { keyId ->
        paired1.get()?.takeIf { SessionKeys.keyId(it.psk) == keyId }?.let { PairingKey(it.peerDeviceId, it.psk) }
      }
      override fun pairingResponder() = responder1.get()
      override fun onPaired(outcome: PairingOutcome) {
        paired1.set(outcome)
        responder1.set(null)
      }
      override fun counts(peerDeviceId: String) = ContentCounts(0, 0)
      override fun lease(peerDeviceId: String, kind: ContentKind) = null
      override fun leased(peerDeviceId: String, itemId: UUID) = null
      override fun heartbeat(peerDeviceId: String, itemId: UUID) = false
      override fun commit(peerDeviceId: String, itemId: UUID) = false
      override fun release(peerDeviceId: String, itemId: UUID) = false
    }

    val server1 = NoteLinkServer(host1, { k, r -> ble1[k]?.offer(r) }, lanPort = port1, discoveryPort = udpPort1)
    try {
      server1.start()
      val code = PairingCode.generate()
      responder1.set(PairingResponder(code, host1.localDeviceId, host1.localDeviceName))
      lateinit var pairLink: ChannelRecordLink
      pairLink = ChannelRecordLink(
        PeerConnection.LABEL_BLE,
        182,
        { server1.onBleRecord("p", it) },
        { server1.onBleClosed("p") },
      )
      ble1["p"] = pairLink
      val outcome = pairLink.use { NoteLinkClient.pair(it, PairingInitiator(code, "note-race", "Note")) }

      lateinit var hsLink: ChannelRecordLink
      hsLink = ChannelRecordLink(
        PeerConnection.LABEL_BLE,
        182,
        { server1.onBleRecord("h", it) },
        { server1.onBleClosed("h") },
      )
      ble1["h"] = hsLink
      val session = NoteLinkClient.handshake(
        scope, hsLink, outcome.psk, "note-race", outcome.peerDeviceId, CapabilitySet.getDefaultInstance(),
      )
      val attached = NoteLinkClient.attachTcp(session, listOf("127.0.0.1" to port1, "localhost" to port1))
      assertEquals(PeerConnection.LABEL_LAN, attached)
      withTimeout(2_000) { session.connection.links.first { PeerConnection.LABEL_LAN in it } }
      assertEquals(listOf(PeerConnection.LABEL_BLE, PeerConnection.LABEL_LAN), session.connection.links.value.sorted())
      assertTrue(RecordCodec.HEADER_BYTES > 0)
      session.connection.close()
    } finally {
      server1.close()
      scope.cancel()
    }
  }
}
