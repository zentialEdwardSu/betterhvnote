package com.betterhv.transfer.core.v4.notelink

import com.betterhv.transfer.core.ContentCounts
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.core.v4.lan.LanDiscovery
import com.betterhv.transfer.core.v4.lan.LanDiscoveryCodec
import com.betterhv.transfer.core.v4.lan.TcpAttach
import com.betterhv.transfer.core.v4.pairing.PairingCode
import com.betterhv.transfer.core.v4.pairing.PairingInitiator
import com.betterhv.transfer.core.v4.pairing.PairingOutcome
import com.betterhv.transfer.core.v4.pairing.PairingResponder
import com.betterhv.transfer.core.v4.session.PairingKey
import com.betterhv.transfer.core.v4.session.PairingKeyStore
import com.betterhv.transfer.core.v4.session.PeerConnection
import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.Endpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A host without Bluetooth: discovery over UDP, then pairing, handshake and transfers over one
 * TCP control connection. Uses loopback with a unicast probe target, so it runs on CI.
 */
class NoteLinkLanOnlyTest {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val tcpPort = ServerSocket(0).use { it.localPort }
  private val udpPort = java.net.DatagramSocket(0).use { it.localPort }
  private lateinit var host: Host
  private lateinit var server: NoteLinkServer
  private val loopback = listOf(InetAddress.getLoopbackAddress())

  @Before
  fun setUp() {
    host = Host(tcpPort)
    server = NoteLinkServer(host, sendBle = { _, _ -> error("no Bluetooth on this host") }, lanPort = tcpPort, discoveryPort = udpPort)
    server.start()
  }

  @After
  fun tearDown() {
    server.close()
    scope.cancel()
  }

  @Test
  fun discoverPairConnectAndFetchWithoutBluetooth() = runBlocking {
    val code = PairingCode.generate()
    host.responder = PairingResponder(code, host.localDeviceId, host.localDeviceName)

    val found = LanDiscovery.find(3_000, port = udpPort, targets = loopback) { it.pairing }
    assertNotNull("pairing host must answer the probe", found)
    assertEquals(tcpPort, found!!.tcpPort)
    assertEquals("Desktop", found.discovery.deviceName)

    val outcome = TcpAttach.dialControl(found.host, found.tcpPort).use { link ->
      NoteLinkClient.pair(link, PairingInitiator(code, NOTE_ID, "N10Pro"))
    }
    assertEquals(HOST_ID, outcome.peerDeviceId)
    assertArrayEquals(outcome.psk, host.paired!!.psk)

    // Discovery now identifies the paired host by its identity hash (no pairing flag).
    val again = LanDiscovery.find(3_000, port = udpPort, targets = loopback) {
      it.discovery.identityHash.toByteArray().contentEquals(LanDiscoveryCodec.identityHash(HOST_ID))
    }
    assertNotNull(again)

    val started = System.nanoTime()
    val session = NoteLinkClient.handshake(
      scope,
      TcpAttach.dialControl(again!!.host, again.tcpPort),
      outcome.psk,
      NOTE_ID,
      outcome.peerDeviceId,
      CapabilitySet.getDefaultInstance(),
    )
    val handshakeMillis = (System.nanoTime() - started) / 1_000_000
    assertEquals(listOf(PeerConnection.LABEL_LAN), session.connection.links.value)
    assertTrue("LAN handshake took $handshakeMillis ms", handshakeMillis < 1_000)

    val image = ByteArray(1_500_000).also { java.util.Random(9).nextBytes(it) }
    val id = host.enqueue(image)
    // Real hosts publish after every queue change; the fake does it here.
    server.publishQueueChanged()
    withTimeout(2_000) { session.counts.first { it?.images == 1 } }
    val sink = MemoryByteSink(4 shl 20)
    val item = NoteLinkClient.fetch(session, ContentKind.IMAGE, { sink })!!
    assertEquals(PeerConnection.LABEL_LAN, item.timing.link)
    assertArrayEquals(image, sink.bytes)
    assertTrue(NoteLinkClient.lease(session, id, com.betterhv.transfer.proto.v4.LeaseKind.LEASE_COMMIT))
    session.connection.close()
  }

  @Test
  fun aProbeFindsTheHostExactlyOnce() = runBlocking {
    // Three probe repeats produce three announces; they collapse to one peer.
    val peers = LanDiscovery.probe(400, port = udpPort, targets = loopback)
    assertEquals(1, peers.size)
    assertEquals(10, peers.single().identityHashHex.length)
  }

  private class Host(private val tcpPort: Int) : NoteLinkHost {
    override val localDeviceId = HOST_ID
    override val localDeviceName = "Desktop"
    @Volatile var responder: PairingResponder? = null
    @Volatile var paired: PairingOutcome? = null
    private val leased = ConcurrentHashMap<UUID, ByteArray>()
    private val queue = ConcurrentHashMap<UUID, ByteArray>()

    fun enqueue(bytes: ByteArray): UUID = UUID.randomUUID().also { queue[it] = bytes }

    override fun capabilities(): CapabilitySet = CapabilitySet.newBuilder()
      .setLan(Endpoint.newBuilder().setHost("127.0.0.1").setPort(tcpPort))
      .build()

    override fun pairings() = PairingKeyStore { keyId ->
      paired?.takeIf { SessionKeys.keyId(it.psk) == keyId }?.let { PairingKey(it.peerDeviceId, it.psk) }
    }

    override fun pairingResponder() = responder

    override fun onPaired(outcome: PairingOutcome) {
      paired = outcome
      responder = null
    }

    override fun counts(peerDeviceId: String) = ContentCounts(queue.size, 0)

    override fun advertisedCounts() = ContentCounts(queue.size, 0)

    override fun lease(peerDeviceId: String, kind: ContentKind): HostLease? {
      val (id, bytes) = queue.entries.firstOrNull()?.toPair() ?: return null
      queue.remove(id)
      leased[id] = bytes
      return hostLease(id, bytes)
    }

    private fun hostLease(id: UUID, bytes: ByteArray) = HostLease(
      NoteLinkProto.offer(id, ContentKind.IMAGE, "image/jpeg", bytes.size.toLong(), TransferCrypto.sha256(bytes), "a.jpg"),
      PayloadSource.of(bytes),
    )

    override fun leased(peerDeviceId: String, itemId: UUID) = leased[itemId]?.let { hostLease(itemId, it) }
    override fun heartbeat(peerDeviceId: String, itemId: UUID) = leased.containsKey(itemId)
    override fun commit(peerDeviceId: String, itemId: UUID) = leased.remove(itemId) != null
    override fun release(peerDeviceId: String, itemId: UUID) = leased.remove(itemId) != null

    override fun log(message: String) = println("host: $message")
  }

  private companion object {
    const val NOTE_ID = "note-lan-only"
    const val HOST_ID = "desktop-without-bluetooth"
  }
}
