package com.betterhv.transfer.core.v4.notelink

import com.betterhv.transfer.core.ContentCounts
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.core.v4.pairing.PairingCode
import com.betterhv.transfer.core.v4.pairing.PairingException
import com.betterhv.transfer.core.v4.pairing.PairingInitiator
import com.betterhv.transfer.core.v4.pairing.PairingOutcome
import com.betterhv.transfer.core.v4.pairing.PairingResponder
import com.betterhv.transfer.core.v4.session.ChannelRecordLink
import com.betterhv.transfer.core.v4.session.PairingKey
import com.betterhv.transfer.core.v4.session.PairingKeyStore
import com.betterhv.transfer.core.v4.session.PeerConnection
import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.ContentKind as ProtoKind
import com.betterhv.transfer.proto.v4.Endpoint
import com.betterhv.transfer.proto.v4.LeaseKind
import com.betterhv.transfer.proto.v4.PushOffer
import com.betterhv.transfer.proto.v4.UpdateCheck
import com.betterhv.transfer.proto.v4.UpdateResult
import com.betterhv.transfer.proto.v4.UpdateState
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Full NoteLink v4 flow between the Note client and a sender host over an in-memory BLE link
 * (182-byte records, 10 ms one-way latency) and a real loopback TCP link.
 */
class NoteLinkEndToEndTest {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private lateinit var host: FakeHost
  private lateinit var server: NoteLinkServer
  private lateinit var temp: File
  private val bleToNote = ConcurrentHashMap<String, ChannelRecordLink>()
  private val port = ServerSocket(0).use { it.localPort }

  @Before
  fun setUp() {
    temp = Files.createTempDirectory("notelink-e2e").toFile()
    host = FakeHost(port)
    server = NoteLinkServer(host, sendBle = { key, record ->
      delay(BLE_LATENCY_MILLIS)
      bleToNote[key]?.offer(record)
    }, lanPort = port)
    host.server = server
    server.start()
  }

  @After
  fun tearDown() {
    server.close()
    scope.cancel()
    temp.deleteRecursively()
  }

  /** Note-side BLE link; records reach the server after [BLE_LATENCY_MILLIS]. */
  private fun bleLink(key: String = "AA:BB"): ChannelRecordLink {
    server.onBleMtu(key, BLE_MTU)
    lateinit var link: ChannelRecordLink
    link = ChannelRecordLink(
      label = PeerConnection.LABEL_BLE,
      maxRecordBytes = BLE_MTU - 3,
      sender = { record ->
        delay(BLE_LATENCY_MILLIS)
        server.onBleRecord(key, record)
      },
      onClose = { server.onBleClosed(key) },
    )
    bleToNote[key] = link
    return link
  }

  private fun pair(): PairingOutcome = runBlocking {
    val code = PairingCode.generate()
    host.responder = PairingResponder(code, host.localDeviceId, host.localDeviceName)
    bleLink().use { link -> NoteLinkClient.pair(link, PairingInitiator(code, NOTE_ID, "N10Pro")) }
  }

  private suspend fun connect(outcome: PairingOutcome, withLan: Boolean = true): NoteLinkSession {
    val session = NoteLinkClient.handshake(
      scope,
      bleLink(),
      outcome.psk,
      NOTE_ID,
      outcome.peerDeviceId,
      CapabilitySet.newBuilder().addKinds(ProtoKind.CONTENT_IMAGE).build(),
    )
    if (withLan) {
      val attached = NoteLinkClient.attachTcp(session, NoteLinkClient.lanCandidates(session.capabilities))
      assertEquals(PeerConnection.LABEL_LAN, attached)
      withTimeout(2_000) { session.connection.links.first { PeerConnection.LABEL_LAN in it } }
    }
    return session
  }

  @Test
  fun pairingExchangesIdentitiesAndThePskOpensASession() = runBlocking {
    val outcome = pair()
    assertEquals(PHONE_ID, outcome.peerDeviceId)
    assertEquals(NOTE_ID, host.paired!!.peerDeviceId)
    assertArrayEquals(outcome.psk, host.paired!!.psk)
    val session = connect(outcome, withLan = false)
    assertEquals(PHONE_ID, session.peerDeviceId)
    val counts = withTimeout(2_000) { session.counts.first { it != null } }
    assertEquals(0, counts!!.images)
    session.connection.close()
  }

  @Test
  fun aWrongPairingCodeIsReportedToTheNote() = runBlocking {
    host.responder = PairingResponder("111111", host.localDeviceId, host.localDeviceName)
    try {
      bleLink().use { NoteLinkClient.pair(it, PairingInitiator("222222", NOTE_ID, "N10Pro")) }
      fail("Wrong code paired")
    } catch (expected: PairingException) {
      assertEquals(2, expected.attemptsLeft)
    }
    assertNull(host.paired)
  }

  @Test
  fun anUnknownPskFailsFastInsteadOfTimingOut() = runBlocking {
    pair()
    val started = System.nanoTime()
    try {
      NoteLinkClient.handshake(
        scope, bleLink(), ByteArray(32) { 5 }, NOTE_ID, PHONE_ID, CapabilitySet.getDefaultInstance()
      )
      fail("Unknown PSK opened a session")
    } catch (expected: com.betterhv.transfer.core.v4.session.HandshakeException) {
      assertEquals(com.betterhv.transfer.proto.v4.ErrorCode.ERROR_UNKNOWN_PAIRING, expected.code)
    }
    assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
  }

  @Test
  fun warmInsertOfATwoMegabyteImageOverLanTakesUnderOneSecond() = runBlocking {
    val session = connect(pair())
    val image = ByteArray(2 * 1024 * 1024).also { java.util.Random(3).nextBytes(it) }
    val id = host.enqueue(ContentKind.IMAGE, "image/jpeg", image)
    withTimeout(2_000) { session.counts.first { it?.images == 1 } }

    val target = File(temp, "image.part")
    val started = System.nanoTime()
    val item = NoteLinkClient.fetch(session, ContentKind.IMAGE, { FileByteSink(target) })!!
    val millis = (System.nanoTime() - started) / 1_000_000
    assertEquals(id, NoteLinkProto.uuid(item.offer.itemId))
    assertArrayEquals(image, target.readBytes())
    assertEquals(PeerConnection.LABEL_LAN, item.timing.link)
    assertTrue("warm 2 MB insert took $millis ms (${item.timing})", millis < 1_000)

    assertTrue(NoteLinkClient.lease(session, id, LeaseKind.LEASE_COMMIT))
    assertTrue(id in host.committed)
    withTimeout(2_000) { session.counts.first { it?.images == 0 } }
    session.connection.close()
  }

  @Test
  fun textTravelsInlineInTheFetchResult() = runBlocking {
    val session = connect(pair())
    host.enqueue(ContentKind.TEXT, "text/plain", "你好 NoteLink".encodeToByteArray())
    val item = NoteLinkClient.fetch(session, ContentKind.TEXT, { error("Text must be inline") })!!
    assertEquals("你好 NoteLink", item.inline!!.decodeToString())
    session.connection.close()
  }

  @Test
  fun anEmptyQueueReturnsNull() = runBlocking {
    val session = connect(pair())
    assertNull(NoteLinkClient.fetch(session, ContentKind.PDF, { error("unused") }))
    session.connection.close()
  }

  @Test
  fun withoutLanTheTransferFallsBackToBle() = runBlocking {
    val session = connect(pair(), withLan = false)
    val pdf = ByteArray(20_000) { (it % 251).toByte() }
    host.enqueue(ContentKind.PDF, "application/pdf", pdf)
    val sink = MemoryByteSink(1 shl 20)
    val item = NoteLinkClient.fetch(session, ContentKind.PDF, { sink })!!
    assertEquals(PeerConnection.LABEL_BLE, item.timing.link)
    assertArrayEquals(pdf, sink.bytes)
    session.connection.close()
  }

  @Test
  fun losingTheLanLinkKeepsTheSessionOnBleAndLanCanReattach() = runBlocking {
    val session = connect(pair())
    session.connection.closeLink(PeerConnection.LABEL_LAN)
    assertEquals(listOf(PeerConnection.LABEL_BLE), session.connection.links.value)
    assertTrue(session.connection.isOpen)
    host.enqueue(ContentKind.TEXT, "text/plain", "over ble".encodeToByteArray())
    val overBle = NoteLinkClient.fetch(session, ContentKind.TEXT, { error("inline") })!!
    assertEquals(PeerConnection.LABEL_BLE, overBle.timing.link)
    assertEquals(
      PeerConnection.LABEL_LAN,
      NoteLinkClient.attachTcp(session, NoteLinkClient.lanCandidates(session.capabilities)),
    )
    host.enqueue(ContentKind.TEXT, "text/plain", "over lan".encodeToByteArray())
    assertEquals(
      PeerConnection.LABEL_LAN, NoteLinkClient.fetch(session, ContentKind.TEXT, { error("inline") })!!.timing.link
    )
    session.connection.close()
  }

  @Test
  fun exportPushIsVerifiedAndIdempotent() = runBlocking {
    val session = connect(pair())
    val export = ByteArray(300_000) { (it * 7).toByte() }
    val offer = PushOffer.newBuilder()
      .setItemId(ByteString.copyFrom(NoteLinkProto.uuidBytes(UUID.randomUUID())))
      .setKind(ProtoKind.CONTENT_PDF)
      .setMimeType("application/pdf")
      .setByteLength(export.size.toLong())
      .setSha256(ByteString.copyFrom(TransferCrypto.sha256(export)))
      .setDisplayName("notes.pdf")
      .build()
    assertTrue(NoteLinkClient.push(session, offer, { export.inputStream() }))
    assertArrayEquals(export, host.received[NoteLinkProto.uuid(offer.itemId)])
    assertFalse(
      "A second push of the same artifact is a no-op", NoteLinkClient.push(session, offer, { export.inputStream() })
    )
    session.connection.close()
  }

  @Test
  fun aCorruptedExportIsRejected() = runBlocking {
    val session = connect(pair())
    val export = ByteArray(10_000) { 1 }
    val offer = PushOffer.newBuilder()
      .setItemId(ByteString.copyFrom(NoteLinkProto.uuidBytes(UUID.randomUUID())))
      .setKind(ProtoKind.CONTENT_PDF)
      .setMimeType("application/pdf")
      .setByteLength(export.size.toLong())
      .setSha256(ByteString.copyFrom(ByteArray(32)))
      .setDisplayName("bad.pdf")
      .build()
    try {
      NoteLinkClient.push(session, offer, { export.inputStream() })
      fail("A corrupted export was accepted")
    } catch (expected: com.betterhv.transfer.core.v4.session.ExchangeException) {
      assertEquals(com.betterhv.transfer.proto.v4.ErrorCode.ERROR_INTEGRITY_FAILED, expected.code)
    }
    assertTrue(host.received.isEmpty())
    session.connection.close()
  }

  @Test
  fun updateCheckReportsPreparationThenStreamsTheLeasedPackage() = runBlocking {
    val session = connect(pair())
    val apk = ByteArray(1_000_000) { (it % 13).toByte() }
    host.updatePackage = apk
    val progress = mutableListOf<UpdateResult>()
    val result = NoteLinkClient.checkUpdate(
      session,
      UpdateCheck.newBuilder().setPackageName("com.betterhv.note").setVersionCode(1).build(),
    ) { progress += it }
    assertEquals(UpdateState.UPDATE_AVAILABLE, result.state)
    assertTrue(progress.isNotEmpty())
    val sink = MemoryByteSink(2 shl 20)
    NoteLinkClient.download(session, result.offer, sink)
    assertArrayEquals(apk, sink.bytes)
    assertTrue(NoteLinkClient.lease(session, NoteLinkProto.uuid(result.offer.itemId), LeaseKind.LEASE_COMMIT))
    session.connection.close()
  }

  @Test
  fun aNewHandshakeReplacesTheOldSessionAndItsLanLink() = runBlocking {
    val outcome = pair()
    val first = connect(outcome)
    val second = connect(outcome)
    // The server closed the replaced session, which drops its TCP link at once.
    withTimeout(2_000) { first.connection.links.first { PeerConnection.LABEL_LAN !in it } }
    first.connection.close()
    host.enqueue(ContentKind.TEXT, "text/plain", "after reconnect".encodeToByteArray())
    val item = NoteLinkClient.fetch(second, ContentKind.TEXT, { error("inline") })!!
    assertEquals("after reconnect", item.inline!!.decodeToString())
    second.connection.close()
  }

  private class FakeHost(private val lanPort: Int) : NoteLinkHost {
    override val localDeviceId = PHONE_ID
    override val localDeviceName = "Xiaomi"
    @Volatile var responder: PairingResponder? = null
    @Volatile var paired: PairingOutcome? = null
    @Volatile var updatePackage: ByteArray? = null
    private var updatePolls = 0
    private val queue = ArrayList<Item>()
    private val leased = ConcurrentHashMap<UUID, Item>()
    val committed = ConcurrentHashMap.newKeySet<UUID>()
    val received = ConcurrentHashMap<UUID, ByteArray>()

    class Item(val id: UUID, val kind: ContentKind, val mime: String, val bytes: ByteArray)

    @Synchronized
    fun enqueue(kind: ContentKind, mime: String, bytes: ByteArray): UUID {
      val id = UUID.randomUUID()
      queue += Item(id, kind, mime, bytes)
      server?.publishQueueChanged()
      return id
    }

    var server: NoteLinkServer? = null

    override fun capabilities(): CapabilitySet = CapabilitySet.newBuilder()
      .setLan(Endpoint.newBuilder().setHost("203.0.113.1").setPort(lanPort))
      .addLanCandidates(Endpoint.newBuilder().setHost("127.0.0.1").setPort(lanPort))
      .setDeviceName(localDeviceName)
      .build()

    override fun pairings(): PairingKeyStore = PairingKeyStore { keyId ->
      paired?.takeIf { SessionKeys.keyId(it.psk) == keyId }?.let { PairingKey(it.peerDeviceId, it.psk) }
    }

    override fun pairingResponder(): PairingResponder? = responder

    override fun onPaired(outcome: PairingOutcome) {
      paired = outcome
      responder = null
    }

    @Synchronized
    override fun counts(peerDeviceId: String): ContentCounts = ContentCounts(
      queue.count { it.kind == ContentKind.IMAGE },
      queue.count { it.kind == ContentKind.TEXT },
      queue.count { it.kind == ContentKind.PDF },
    )

    @Synchronized
    override fun lease(peerDeviceId: String, kind: ContentKind): HostLease? {
      val item = queue.firstOrNull { it.kind == kind } ?: return null
      queue.remove(item)
      leased[item.id] = item
      return hostLease(item)
    }

    private fun hostLease(item: Item): HostLease {
      val inline = item.bytes.takeIf { item.kind == ContentKind.TEXT && it.size <= NoteLinkProto.INLINE_TEXT_BYTES }
      return HostLease(
        NoteLinkProto.offer(
          item.id, item.kind, item.mime, item.bytes.size.toLong(),
          TransferCrypto.sha256(item.bytes), "x", 60_000, inline
        ),
        PayloadSource.of(item.bytes),
      )
    }

    override fun leased(peerDeviceId: String, itemId: UUID): HostLease? = leased[itemId]?.let(::hostLease)

    override fun heartbeat(peerDeviceId: String, itemId: UUID) = leased.containsKey(itemId)

    override fun commit(peerDeviceId: String, itemId: UUID): Boolean =
      (leased.remove(itemId) != null).also { if (it) committed += itemId }

    @Synchronized
    override fun release(peerDeviceId: String, itemId: UUID): Boolean {
      val item = leased.remove(itemId) ?: return false
      queue.add(0, item)
      return true
    }

    override fun push(peerDeviceId: String, offer: PushOffer): PushDecision {
      val id = NoteLinkProto.uuid(offer.itemId)
      if (received.containsKey(id)) return PushDecision.AlreadyReceived
      val buffer = ByteArrayOutputStream()
      val sink = object : ByteSink {
        override fun write(bytes: ByteArray) = buffer.write(bytes)
        override fun complete(sha256: ByteArray, totalBytes: Long): Boolean =
          TransferCrypto.sha256(buffer.toByteArray()).contentEquals(sha256) && buffer.size().toLong() == totalBytes
        override fun abort() = buffer.reset()
      }
      return PushDecision.Receive(sink) { received[id] = buffer.toByteArray() }
    }

    @Synchronized
    override fun updateCheck(peerDeviceId: String, check: UpdateCheck): UpdateResult {
      val apk = updatePackage ?: return UpdateResult.newBuilder().setState(UpdateState.UPDATE_UP_TO_DATE).build()
      if (updatePolls++ < 2) {
        return UpdateResult.newBuilder().setState(UpdateState.UPDATE_PREPARING).setTotalBytes(apk.size.toLong()).build()
      }
      val item = Item(UUID.randomUUID(), ContentKind.APP_PACKAGE, "application/vnd.android.package-archive", apk)
      leased[item.id] = item
      return UpdateResult.newBuilder()
        .setState(UpdateState.UPDATE_AVAILABLE)
        .setLatestVersion("9.9.9")
        .setOffer(hostLease(item).offer)
        .build()
    }
  }

  private companion object {
    const val NOTE_ID = "note-device-id"
    const val PHONE_ID = "phone-device-id"
    const val BLE_MTU = 185
    const val BLE_LATENCY_MILLIS = 10L
  }
}
