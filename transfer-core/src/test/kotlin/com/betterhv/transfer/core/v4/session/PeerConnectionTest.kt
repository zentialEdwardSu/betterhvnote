package com.betterhv.transfer.core.v4.session

import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.proto.v4.ContentKind
import com.betterhv.transfer.proto.v4.Frame
import com.betterhv.transfer.proto.v4.Ping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class PeerConnectionTest {
  private val psk = ByteArray(SessionKeys.KEY_BYTES) { (it * 7 + 3).toByte() }
  private val sessionBytes = ByteArray(SessionKeys.SESSION_ID_BYTES) { (it + 11).toByte() }
  private val sessionId = SessionKeys.sessionIdLong(sessionBytes)
  private val keys = SessionKeys.derive(
    psk,
    SessionKeys.keyId(psk),
    ByteArray(SessionKeys.NONCE_BYTES) { 1 },
    ByteArray(SessionKeys.NONCE_BYTES) { 2 },
    sessionBytes,
  )
  private val negotiated = NegotiatedCapabilities(
    transports = 1 or 2,
    maxStreams = 8,
    maxRecordBytes = 64 * 1024,
    maxInflightBytes = 4L * 1024 * 1024,
    kinds = listOf(ContentKind.CONTENT_IMAGE),
    features = 0,
  )

  private fun pingFrame(ts: Long): Frame = Frame.newBuilder()
    .setPing(Ping.newBuilder().setSentAtMs(ts))
    .build()

  @Test
  fun tracksHighestRemoteIdPerLinkSoFasterLanDoesNotDropSlowerBleRequests() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val serverSession = ProtocolSession("tablet", sessionId, keys, negotiated, initiator = false)
    val clientSession = ProtocolSession("phone", sessionId, keys, negotiated, initiator = true)
    val bleCipherClient = clientSession.handshakeLinkCipher(512)
    val bleCipherServer = serverSession.handshakeLinkCipher(512)
    val lanSalt = ByteArray(32) { 5 }
    val lanCipherClient = clientSession.linkCipher(PeerConnection.LABEL_TCP, lanSalt, 64 * 1024)
    val lanCipherServer = serverSession.linkCipher(PeerConnection.LABEL_TCP, lanSalt, 64 * 1024)

    val served = CopyOnWriteArrayList<Pair<Int, Long>>()
    lateinit var clientBle: ChannelRecordLink
    lateinit var serverBle: ChannelRecordLink
    lateinit var clientLan: ChannelRecordLink
    lateinit var serverLan: ChannelRecordLink

    clientBle = ChannelRecordLink(PeerConnection.LABEL_BLE, 512, { serverBle.offer(it) })
    serverBle = ChannelRecordLink(PeerConnection.LABEL_BLE, 512, { clientBle.offer(it) })
    clientLan = ChannelRecordLink(PeerConnection.LABEL_LAN, 64 * 1024, { serverLan.offer(it) })
    serverLan = ChannelRecordLink(PeerConnection.LABEL_LAN, 64 * 1024, { clientLan.offer(it) })

    val serverConn = PeerConnection(
      session = serverSession,
      parentScope = scope,
      responder = { exchange, first ->
        served += exchange.id to first.ping.sentAtMs
        exchange.send(first)
      },
    )
    val clientConn = PeerConnection(
      session = clientSession,
      parentScope = scope,
      responder = { _, _ -> },
    )
    try {
      serverConn.attach(serverBle, bleCipherServer)
      serverConn.attach(serverLan, lanCipherServer)
      clientConn.attach(clientBle, bleCipherClient)
      clientConn.attach(clientLan, lanCipherClient)
      withTimeout(2_000) { serverConn.links.first { it.size == 2 } }

      // Open BLE exchange (id=1) first, then LAN exchange (id=3).
      // Execute LAN (id=3) BEFORE BLE (id=1) so server sees id=3 on LAN first, then id=1 on BLE.
      val bleExchange = clientConn.open(PeerConnection.LABEL_BLE)
      val lanExchange = clientConn.open(PeerConnection.LABEL_LAN)
      assertEquals(1, bleExchange.id)
      assertEquals(3, lanExchange.id)

      val lanReply = lanExchange.use { it.request(pingFrame(300L), 2_000) }
      assertEquals(300L, lanReply.ping.sentAtMs)

      val bleReply = bleExchange.use { it.request(pingFrame(100L), 2_000) }
      assertEquals(100L, bleReply.ping.sentAtMs)
      assertEquals(setOf(1, 3), served.map { it.first }.toSet())

      // A stale lower stream id (1) on the same LAN link (after id=3) must be dropped.
      lanCipherClient.seal(1, pingFrame(999L)).forEach { clientLan.send(it) }
      val newerLan = clientConn.open(PeerConnection.LABEL_LAN).use { it.request(pingFrame(500L), 2_000) }
      assertEquals(500L, newerLan.ping.sentAtMs)
      assertEquals(false, served.any { it.second == 999L })
    } finally {
      clientConn.close()
      serverConn.close()
      scope.cancel()
    }
  }
}
