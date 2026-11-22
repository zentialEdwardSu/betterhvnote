package com.betterhv.transfer.core.v4.notelink

import com.betterhv.transfer.core.ContentCounts
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.core.v4.codec.RecordCodec
import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.core.v4.lan.LanAnnouncer
import com.betterhv.transfer.core.v4.lan.LanDiscoveryCodec
import com.betterhv.transfer.core.v4.lan.TcpAttachListener
import com.betterhv.transfer.core.v4.lan.TcpRecordLink
import com.betterhv.transfer.core.v4.pairing.PairingException
import com.betterhv.transfer.core.v4.pairing.PairingOutcome
import com.betterhv.transfer.core.v4.pairing.PairingRecords
import com.betterhv.transfer.core.v4.pairing.PairingResponder
import com.betterhv.transfer.core.v4.session.ChannelRecordLink
import com.betterhv.transfer.core.v4.session.Exchange
import com.betterhv.transfer.core.v4.session.ExchangeException
import com.betterhv.transfer.core.v4.session.HandshakeException
import com.betterhv.transfer.core.v4.session.HandshakeReplayGuard
import com.betterhv.transfer.core.v4.session.PairingKeyStore
import com.betterhv.transfer.core.v4.session.PeerConnection
import com.betterhv.transfer.core.v4.session.ProtocolSession
import com.betterhv.transfer.core.v4.session.ServerHandshake
import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.Discovery
import com.betterhv.transfer.proto.v4.ErrorCode
import com.betterhv.transfer.proto.v4.FetchResult
import com.betterhv.transfer.proto.v4.Frame
import com.betterhv.transfer.proto.v4.LeaseKind
import com.betterhv.transfer.proto.v4.LeaseUpdate
import com.betterhv.transfer.proto.v4.Offer
import com.betterhv.transfer.proto.v4.P2pGroup
import com.betterhv.transfer.proto.v4.P2pState
import com.betterhv.transfer.proto.v4.PairResult
import com.betterhv.transfer.proto.v4.PairingFrame
import com.betterhv.transfer.proto.v4.PushOffer
import com.betterhv.transfer.proto.v4.PushState
import com.betterhv.transfer.proto.v4.PushStatus
import com.betterhv.transfer.proto.v4.UpdateCheck
import com.betterhv.transfer.proto.v4.UpdateResult
import com.betterhv.transfer.proto.v4.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One item the host leased to a peer. */
class HostLease(val offer: Offer, val payload: PayloadSource?)

sealed interface PushDecision {
  class Receive(val sink: ByteSink, val onComplete: () -> Unit = {}) : PushDecision
  data object AlreadyReceived : PushDecision
  data class Reject(val message: String) : PushDecision
}

/** Storage, policy and UI hooks of a NoteLink sender (Android phone or Windows PC). */
interface NoteLinkHost {
  val localDeviceId: String
  val localDeviceName: String

  /** Fresh capabilities for every handshake, so LAN addresses never go stale. */
  fun capabilities(): CapabilitySet

  fun pairings(): PairingKeyStore

  /** Non-null while the user entered a pairing code on this device. */
  fun pairingResponder(): PairingResponder?

  fun onPaired(outcome: PairingOutcome)

  fun onPairingFailed(attemptsLeft: Int) = Unit

  /** Queue counts announced to every Note in LAN discovery replies (any destination). */
  fun advertisedCounts(): ContentCounts = ContentCounts(0, 0)

  /**
   * Hosts (or reuses) a Wi-Fi Direct group the peer can join; null when unsupported. Called only
   * when the peer could not reach any LAN candidate, so the radio is not used otherwise.
   */
  suspend fun prepareP2p(peerDeviceId: String): P2pGroup? = null

  fun counts(peerDeviceId: String): ContentCounts

  fun lease(peerDeviceId: String, kind: ContentKind): HostLease?

  /** Re-opens the payload of an item already leased to [peerDeviceId]. */
  fun leased(peerDeviceId: String, itemId: UUID): HostLease?

  fun heartbeat(peerDeviceId: String, itemId: UUID): Boolean

  fun commit(peerDeviceId: String, itemId: UUID): Boolean

  fun release(peerDeviceId: String, itemId: UUID): Boolean

  fun push(peerDeviceId: String, offer: PushOffer): PushDecision = PushDecision.Reject("Exports are not accepted")

  /** Called repeatedly until it returns a terminal state; PREPARING results are relayed as progress. */
  fun updateCheck(peerDeviceId: String, check: UpdateCheck): UpdateResult =
    UpdateResult.newBuilder().setState(UpdateState.UPDATE_UP_TO_DATE).build()

  fun onSessionChanged(peerDeviceId: String, links: List<String>) = Unit

  fun onTransfer(event: NoteLinkTransferEvent) = Unit

  fun log(message: String) = Unit
}

sealed interface NoteLinkTransferEvent {
  val itemId: UUID

  data class Started(override val itemId: UUID, val peerDeviceId: String, val link: String, val totalBytes: Long) :
    NoteLinkTransferEvent

  data class Progress(override val itemId: UUID, val bytes: Long, val totalBytes: Long) : NoteLinkTransferEvent

  data class Finished(override val itemId: UUID, val bytes: Long, val millis: Long) : NoteLinkTransferEvent

  data class Failed(override val itemId: UUID, val message: String) : NoteLinkTransferEvent
}

/**
 * Transport-neutral NoteLink server. The platform layer feeds BLE control records with
 * [onBleRecord] and reports GATT disconnects with [onBleClosed]; TCP connections arrive on the
 * listener started by [start]. Everything after the handshake runs on [PeerConnection]s.
 */
class NoteLinkServer(
  private val host: NoteLinkHost,
  private val sendBle: suspend (peerKey: String, record: ByteArray) -> Unit,
  private val lanPort: Int = TcpAttachListener.DEFAULT_PORT,
  private val clock: () -> Long = System::currentTimeMillis,
  private val discoveryPort: Int = LanDiscoveryCodec.PORT,
) : AutoCloseable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val peers = ConcurrentHashMap<String, ControlPeer>()
  private val connections = ConcurrentHashMap<String, PeerConnection>()
  private val bySession = ConcurrentHashMap<Long, PeerConnection>()
  private val sessionLock = Any()
  // E-ink tablets often run without network time; the nonce cache and per-device monotonic
  // timestamp still reject replays, so the wall-clock window only needs to catch gross errors.
  private val replayGuard = HandshakeReplayGuard(clockSkewMillis = CLOCK_SKEW_MILLIS)
  private var listener: TcpAttachListener? = null
  private var announcer: LanAnnouncer? = null
  private val controlIds = java.util.concurrent.atomic.AtomicLong()
  private val mutableConnected = MutableStateFlow<Map<String, List<String>>>(emptyMap())

  /** Connected peer device ids and their link labels. */
  val connected: StateFlow<Map<String, List<String>>> = mutableConnected.asStateFlow()

  val lanBoundPort: Int get() = listener?.boundPort ?: -1

  /** One control channel (a GATT client or a TCP control connection) before and after the handshake. */
  private inner class ControlPeer(
    val key: String,
    val label: String = PeerConnection.LABEL_BLE,
    private val sender: suspend (ByteArray) -> Unit = { sendBle(key, it) },
    val onLinkClose: () -> Unit = {},
  ) {
    /**
     * Every record to this peer goes through one writer, so the handshake reply always leaves
     * before the session's first record and the GATT callback thread never blocks on a send.
     */
    val outbound = kotlinx.coroutines.channels.Channel<ByteArray>(BLE_OUTBOUND_CAPACITY)
    val writer = scope.launch {
      for (record in outbound) {
        runCatching { sender(record) }.onFailure { host.log("control send failed peer=$key ${it.message}") }
      }
    }
    @Volatile var maxRecordBytes: Int = DEFAULT_BLE_RECORD
    val pairing = PairingRecords.Reassembler()
    val hello = ArrayList<ByteArray>()
    var helloCount = 0
    @Volatile var link: ChannelRecordLink? = null
    var pairingMessageId = 1
  }

  /** Binds the TCP listener; a taken port throws so the caller can show it. */
  @Synchronized
  fun start() {
    if (listener != null) return
    listener = TcpAttachListener(
      scope = scope,
      pairings = host.pairings(),
      listenerDeviceId = host.localDeviceId,
      session = { id -> bySession[id]?.session },
      onLink = { session, link, cipher ->
        val connection = bySession[session.sessionId]
        if (connection == null) {
          link.close()
        } else {
          host.log("tcp link attached peer=${session.peerDeviceId} from=${link.remoteAddress}")
          connection.attach(link, cipher)
        }
      },
      port = lanPort,
      labelFor = { socket ->
        if (socket.localAddress?.hostAddress?.startsWith(P2P_SUBNET) == true) PeerConnection.LABEL_P2P
        else PeerConnection.LABEL_LAN
      },
      clock = clock,
      onControl = ::onTcpControl,
    ).also { it.start() }
    // Discovery is best effort: without it a Note still connects over BLE or a cached address.
    announcer = LanAnnouncer(scope, ::discoveryAnnouncement, port = discoveryPort, log = host::log)
      .also {
        runCatching { it.start() }.onFailure { error -> host.log("lan discovery unavailable: ${error.message}") }
      }
  }

  private fun discoveryAnnouncement(): Discovery {
    val counts = runCatching { host.advertisedCounts() }.getOrDefault(ContentCounts(0, 0))
    val pairing = runCatching { host.pairingResponder()?.isActive() == true }.getOrDefault(false)
    var flags = if (pairing) LanDiscoveryCodec.FLAG_PAIRING else 0
    if (counts.images + counts.texts + counts.pdfs > 0) flags = flags or LanDiscoveryCodec.FLAG_HAS_ITEMS
    return Discovery.newBuilder()
      .setMajor(4)
      .setIdentityHash(com.google.protobuf.ByteString.copyFrom(LanDiscoveryCodec.identityHash(host.localDeviceId)))
      .setFlags(flags)
      .setImageCount(counts.images)
      .setTextCount(counts.texts)
      .setPdfCount(counts.pdfs)
      .setTcpPort(lanBoundPort)
      .setDeviceName(host.localDeviceName)
      .build()
  }

  /**
   * A Note opened a control connection over TCP: the same pairing and handshake records BLE
   * carries. The session's first link is this connection, so no separate LAN attach follows.
   */
  private fun onTcpControl(link: TcpRecordLink) {
    val key = "tcp:${link.remoteAddress}#${controlIds.incrementAndGet()}"
    val peer = ControlPeer(key, link.label, sender = link::send, onLinkClose = link::close)
    peer.maxRecordBytes = TCP_CONTROL_RECORD
    peers[key] = peer
    host.log("control connection from ${link.remoteAddress} label=${link.label}")
    scope.launch {
      try {
        while (true) {
          val record = link.receive() ?: break
          // After the handshake the reader waits for the session instead of dropping records:
          // a TCP control link carries bulk streams, unlike a BLE characteristic.
          val established = peer.link
          if (established != null && !PairingRecords.isPairingRecord(record) && !isHello(record)) {
            if (!established.deliver(record)) break
          } else {
            onControlRecord(peer, record)
          }
        }
      } catch (_: Throwable) {
        // A reset ends this control connection; the session (if any) sees its link close.
      } finally {
        onBleClosed(key)
        link.close()
      }
    }
  }

  /** The negotiated ATT MTU of [peerKey]; records are sized to it. */
  fun onBleMtu(peerKey: String, mtu: Int) {
    val peer = peers.getOrPut(peerKey) { ControlPeer(peerKey) }
    peer.maxRecordBytes = (mtu - ATT_OVERHEAD).coerceIn(MIN_BLE_RECORD, MAX_BLE_RECORD)
    peer.link?.maxRecordBytes = peer.maxRecordBytes
  }

  /** Called from the transport's receive callback; never blocks on protocol work. */
  fun onBleRecord(peerKey: String, record: ByteArray) {
    val peer = peers.getOrPut(peerKey) { ControlPeer(peerKey) }
    // A write the client managed to send proves the ATT MTU is at least that large, which covers
    // stacks (Windows) that do not report the negotiated MTU to the server.
    if (record.size > peer.maxRecordBytes) onBleRecordSize(peer, record.size)
    onControlRecord(peer, record)
  }

  private fun onControlRecord(peer: ControlPeer, record: ByteArray) {
    val peerKey = peer.key
    try {
      when {
        PairingRecords.isPairingRecord(record) -> onPairingRecord(peer, record)
        isHello(record) -> onHelloRecord(peer, record)
        else -> peer.link?.offer(record) ?: host.log("ble record before handshake peer=$peerKey")
      }
    } catch (error: RecordCodec.RecordException) {
      host.log("ble record rejected peer=$peerKey ${error.code} ${error.message}")
    }
  }

  private fun onBleRecordSize(peer: ControlPeer, size: Int) {
    peer.maxRecordBytes = size.coerceAtMost(MAX_BLE_RECORD)
    peer.link?.maxRecordBytes = peer.maxRecordBytes
  }

  fun onBleClosed(peerKey: String) {
    peers.remove(peerKey)?.let { peer ->
      peer.link?.close()
      peer.outbound.close()
      peer.onLinkClose()
    }
  }

  /** Tells every connected peer its queue counts; called after every queue change. */
  fun publishQueueChanged() {
    connections.values.forEach { connection ->
      scope.launch { notifyQueue(connection) }
    }
  }

  /** Tells every connected peer the current LAN addresses, e.g. after a Wi-Fi change. */
  fun publishCapabilities() {
    val capabilities = runCatching { host.capabilities() }.getOrNull() ?: return
    val frame = Frame.newBuilder().setCapabilityUpdate(
      com.betterhv.transfer.proto.v4.CapabilityUpdate.newBuilder().setCapabilities(capabilities),
    ).build()
    connections.values.forEach { connection -> scope.launch { connection.notify(frame) } }
  }

  private suspend fun notifyQueue(connection: PeerConnection) {
    val counts = runCatching { host.counts(connection.peerDeviceId) }.getOrNull() ?: return
    connection.notify(
      Frame.newBuilder().setQueueChanged(NoteLinkProto.queueChanged(counts, revision = clock())).build()
    )
  }

  private fun isHello(record: ByteArray): Boolean =
    record.size > RecordCodec.HEADER_BYTES && (record[3].toInt() and RecordCodec.FLAG_HELLO) != 0

  private fun onPairingRecord(peer: ControlPeer, record: ByteArray) {
    val frame = synchronized(peer) { peer.pairing.add(record) } ?: return
    val reply: PairingFrame = try {
      handlePairing(frame)
    } catch (error: PairingException) {
      host.log("pairing rejected peer=${peer.key} ${error.code} ${error.message}")
      PairingFrame.newBuilder().setResult(
        PairResult.newBuilder().setError(error.code).setAttemptsLeft(error.attemptsLeft),
      ).build()
    }
    sendClear(peer, reply)
  }

  private fun handlePairing(frame: PairingFrame): PairingFrame {
    val responder = host.pairingResponder()
      ?: throw PairingException(ErrorCode.ERROR_PENDING_APPROVAL, "Enter the code shown on Note first")
    return when (frame.bodyCase) {
      PairingFrame.BodyCase.INIT -> PairingFrame.newBuilder().setChallenge(responder.onInit(frame.init)).build()
      PairingFrame.BodyCase.CONFIRM -> {
        val (result, outcome) = responder.onConfirm(frame.confirm)
        if (outcome != null) {
          host.onPaired(outcome)
          host.log("paired peer=${outcome.peerDeviceId}")
        } else {
          host.onPairingFailed(result.attemptsLeft)
        }
        PairingFrame.newBuilder().setResult(result).build()
      }
      else -> throw PairingException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Unexpected pairing frame")
    }
  }

  private fun sendClear(peer: ControlPeer, frame: PairingFrame) {
    val records = synchronized(peer) {
      PairingRecords.encode(
        frame,
        response = true,
        messageId = peer.pairingMessageId++,
        maxFragmentBody = peer.maxRecordBytes - RecordCodec.HEADER_BYTES,
      )
    }
    sendRecords(peer, records)
  }

  private fun sendRecords(peer: ControlPeer, records: List<ByteArray>) {
    records.forEach { record ->
      if (peer.outbound.trySend(record).isFailure) host.log("ble outbound full peer=${peer.key}")
    }
  }

  private fun onHelloRecord(peer: ControlPeer, record: ByteArray) {
    val records = synchronized(peer) {
      val fragment = RecordCodec.parse(record)
      if (fragment.header.isResponse) return
      if (fragment.index == 0) {
        peer.hello.clear()
        peer.helloCount = fragment.count
      } else if (fragment.index != peer.hello.size) {
        peer.hello.clear()
        return
      }
      peer.hello += record
      if (peer.hello.size < peer.helloCount) return
      peer.hello.toList().also { peer.hello.clear() }
    }
    val (reply, established) = try {
      newHandshake(peer.maxRecordBytes).accept(records)
    } catch (error: HandshakeException) {
      host.log("handshake rejected peer=${peer.key} ${error.code} ${error.message}")
      // Without a shared key the reject is sent in the clear; it carries only the error code.
      sendClear(peer, PairingFrame.newBuilder().setResult(PairResult.newBuilder().setError(error.code)).build())
      return
    }
    val deviceId = established.clientHello.deviceId
    val session = ProtocolSession(
      peerDeviceId = deviceId,
      sessionId = SessionKeys.sessionIdLong(established.sessionId),
      keys = established.keys,
      capabilities = established.capabilities,
      initiator = false,
    )
    synchronized(sessionLock) {
      peer.link?.close()
      connections.remove(deviceId)?.close()
      val linksJob = java.util.concurrent.atomic.AtomicReference<Job?>()
      val connection = PeerConnection(
        session = session,
        parentScope = scope,
        responder = { exchange, first -> serve(deviceId, exchange, first) },
        onClosed = { closed ->
          linksJob.get()?.cancel()
          val removed = connections.remove(deviceId, closed)
          bySession.remove(session.sessionId, closed)
          publishConnected()
          if (removed && !connections.containsKey(deviceId)) {
            host.onSessionChanged(deviceId, emptyList())
          }
        },
        onLinkClosed = { label, reason -> host.log("link $label closed peer=$deviceId: $reason") },
        clock = clock,
      )
      val link = ChannelRecordLink(
        label = peer.label,
        maxRecordBytes = peer.maxRecordBytes,
        sender = { peer.outbound.send(it) },
        onClose = peer.onLinkClose,
      )
      sendRecords(peer, reply)
      peer.link = link
      connections[deviceId] = connection
      bySession[session.sessionId] = connection
      linksJob.set(scope.launch { connection.links.collect { publishConnected() } })
      connection.attach(link, session.handshakeLinkCipher(peer.maxRecordBytes))
      if (!connection.isOpen) linksJob.get()?.cancel()
      scope.launch { notifyQueue(connection) }
    }
    host.log("session up peer=$deviceId via=${peer.label} key=${peer.key} record=${peer.maxRecordBytes}")
  }

  private fun publishConnected() {
    val snapshot = connections.mapValues { it.value.links.value }
    mutableConnected.value = snapshot
    snapshot.forEach { (peer, links) -> host.onSessionChanged(peer, links) }
  }

  private fun newHandshake(maxRecordBytes: Int) = ServerHandshake(
    pairings = host.pairings(),
    capabilities = host.capabilities(),
    replayGuard = replayGuard,
    maxFragmentBody = maxRecordBytes - RecordCodec.HELLO_HEADER_BYTES,
    clock = clock,
  )

  private suspend fun serve(peerDeviceId: String, exchange: Exchange, first: Frame) {
    when (first.bodyCase) {
      Frame.BodyCase.FETCH_REQUEST -> serveFetch(peerDeviceId, exchange, first)
      Frame.BodyCase.STREAM_OPEN -> {
        if (first.streamOpen.flags and NoteLinkProto.FLAG_UPLOAD != 0) {
          throw ExchangeException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Upload without a push offer")
        }
        val id = NoteLinkProto.uuid(first.streamOpen.itemId)
        val lease = host.leased(peerDeviceId, id)
          ?: throw ExchangeException(ErrorCode.ERROR_STREAM_RESET, "Item $id is not leased")
        val payload = lease.payload ?: throw ExchangeException(ErrorCode.ERROR_STREAM_RESET, "Item has no payload")
        streamPayload(peerDeviceId, exchange, id, payload, first.streamOpen.offset)
      }
      Frame.BodyCase.LEASE_UPDATE -> exchange.send(
        Frame.newBuilder().setLeaseUpdate(applyLease(peerDeviceId, first.leaseUpdate)).build(),
      )
      Frame.BodyCase.PUSH_OFFER -> servePush(peerDeviceId, exchange, first.pushOffer)
      Frame.BodyCase.UPDATE_CHECK -> serveUpdate(peerDeviceId, exchange, first.updateCheck)
      Frame.BodyCase.CAPABILITY_UPDATE -> {
        val wantsP2p = first.capabilityUpdate.capabilities.p2P.state == P2pState.P2P_STATE_STARTING
        val capabilities = host.capabilities().toBuilder()
        if (wantsP2p) {
          val group = host.prepareP2p(peerDeviceId)
            ?: throw ExchangeException(ErrorCode.ERROR_UNSUPPORTED_MODE, "Wi-Fi Direct is unavailable")
          capabilities.setP2P(group)
        }
        exchange.send(
          Frame.newBuilder().setCapabilityUpdate(
            com.betterhv.transfer.proto.v4.CapabilityUpdate.newBuilder().setCapabilities(capabilities),
          ).build(),
        )
      }
      Frame.BodyCase.PING -> exchange.send(first)
      else -> throw ExchangeException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Unexpected ${first.bodyCase}")
    }
  }

  private suspend fun serveFetch(peerDeviceId: String, exchange: Exchange, first: Frame) {
    val kind = NoteLinkProto.fromProto(first.fetchRequest.kind)
      ?: throw ExchangeException(ErrorCode.ERROR_UNSUPPORTED_MODE, "Unknown content kind")
    val lease = host.lease(peerDeviceId, kind)
    val result = FetchResult.newBuilder().setQueueRevision(clock())
    lease?.let { result.addOffers(it.offer) }
    exchange.send(Frame.newBuilder().setFetchResult(result).build())
    val offer = lease?.offer ?: return
    publishQueueChanged()
    val payload = lease.payload
    val wantsStream = first.fetchRequest.flags and FETCH_STREAM != 0
    if (wantsStream && offer.inlineData.isEmpty && payload != null) {
      streamPayload(peerDeviceId, exchange, NoteLinkProto.uuid(offer.itemId), payload, 0)
    }
  }

  private suspend fun streamPayload(
    peerDeviceId: String,
    exchange: Exchange,
    id: UUID,
    payload: PayloadSource,
    offset: Long,
  ) {
    val started = clock()
    host.onTransfer(NoteLinkTransferEvent.Started(id, peerDeviceId, exchange.linkLabel, payload.length))
    try {
      var position = offset.coerceIn(0, payload.length)
      val buffer = ByteArray(exchange.maxChunkBytes)
      var lastProgress = 0L
      payload.open(position).use { input ->
        while (position < payload.length) {
          val read = input.read(buffer, 0, minOf(buffer.size.toLong(), payload.length - position).toInt())
          if (read < 0) throw ExchangeException(ErrorCode.ERROR_INTERNAL, "Payload ended early")
          exchange.send(NoteLinkProto.streamData(buffer, read, position))
          position += read
          val now = clock()
          if (now - lastProgress >= PROGRESS_INTERVAL_MILLIS) {
            lastProgress = now
            host.onTransfer(NoteLinkTransferEvent.Progress(id, position, payload.length))
          }
        }
      }
      exchange.send(NoteLinkProto.streamComplete(payload.sha256, payload.length))
      host.onTransfer(NoteLinkTransferEvent.Finished(id, payload.length - offset, clock() - started))
    } catch (error: Throwable) {
      host.onTransfer(NoteLinkTransferEvent.Failed(id, error.message ?: "Stream failed"))
      throw error
    }
  }

  private fun applyLease(peerDeviceId: String, update: LeaseUpdate): LeaseUpdate {
    var changed = false
    val actions = update.actionsList.map { action ->
      val id = runCatching { NoteLinkProto.uuid(action.itemId) }.getOrNull()
      val ok = id != null && when (action.action) {
        LeaseKind.LEASE_HEARTBEAT -> host.heartbeat(peerDeviceId, id)
        LeaseKind.LEASE_COMMIT -> host.commit(peerDeviceId, id).also { changed = changed || it }
        LeaseKind.LEASE_RELEASE -> host.release(peerDeviceId, id).also { changed = changed || it }
        else -> false
      }
      action.toBuilder().setResult(if (ok) RESULT_OK else RESULT_NOT_FOUND).build()
    }
    if (changed) publishQueueChanged()
    return LeaseUpdate.newBuilder().addAllActions(actions).build()
  }

  private suspend fun servePush(peerDeviceId: String, exchange: Exchange, offer: PushOffer) {
    val decision = host.push(peerDeviceId, offer)
    val state = when (decision) {
      is PushDecision.Receive -> PushState.PUSH_ACCEPTED
      PushDecision.AlreadyReceived -> PushState.PUSH_COMPLETE
      is PushDecision.Reject -> PushState.PUSH_REJECTED
    }
    exchange.send(
      Frame.newBuilder().setPushStatus(PushStatus.newBuilder().setItemId(offer.itemId).setState(state)).build(),
    )
    if (decision !is PushDecision.Receive) return
    val id = NoteLinkProto.uuid(offer.itemId)
    val sink = decision.sink
    val started = clock()
    host.onTransfer(NoteLinkTransferEvent.Started(id, peerDeviceId, exchange.linkLabel, offer.byteLength))
    try {
      val open = exchange.receive(STREAM_IDLE_MILLIS)
      if (open.bodyCase != Frame.BodyCase.STREAM_OPEN || open.streamOpen.flags and NoteLinkProto.FLAG_UPLOAD == 0) {
        throw ExchangeException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Expected an upload stream")
      }
      var received = 0L
      var lastProgress = 0L
      while (true) {
        val frame = exchange.receive(STREAM_IDLE_MILLIS)
        when (frame.bodyCase) {
          Frame.BodyCase.STREAM_DATA -> {
            sink.write(frame.streamData.data.toByteArray())
            received += frame.streamData.data.size()
            if (received > offer.byteLength) throw ExchangeException(ErrorCode.ERROR_INTEGRITY_FAILED, "Too many bytes")
            val now = clock()
            if (now - lastProgress >= PROGRESS_INTERVAL_MILLIS) {
              lastProgress = now
              host.onTransfer(NoteLinkTransferEvent.Progress(id, received, offer.byteLength))
            }
          }
          Frame.BodyCase.STREAM_COMPLETE -> {
            if (!sink.complete(offer.sha256.toByteArray(), offer.byteLength)) {
              throw ExchangeException(ErrorCode.ERROR_INTEGRITY_FAILED, "Export failed verification")
            }
            decision.onComplete()
            exchange.send(NoteLinkProto.streamComplete(offer.sha256.toByteArray(), received))
            host.onTransfer(NoteLinkTransferEvent.Finished(id, received, clock() - started))
            return
          }
          else -> throw ExchangeException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Unexpected ${frame.bodyCase}")
        }
      }
    } catch (error: Throwable) {
      sink.abort()
      host.onTransfer(NoteLinkTransferEvent.Failed(id, error.message ?: "Export failed"))
      throw error
    }
  }

  private suspend fun serveUpdate(peerDeviceId: String, exchange: Exchange, check: UpdateCheck) {
    val deadline = clock() + UPDATE_PREPARE_MILLIS
    while (true) {
      val result = host.updateCheck(peerDeviceId, check)
      exchange.send(Frame.newBuilder().setUpdateResult(result).build())
      if (result.state != UpdateState.UPDATE_PREPARING) {
        if (result.state == UpdateState.UPDATE_AVAILABLE) publishQueueChanged()
        return
      }
      if (clock() > deadline) throw ExchangeException(ErrorCode.ERROR_STALLED, "Update preparation timed out")
      kotlinx.coroutines.delay(UPDATE_POLL_MILLIS)
    }
  }

  override fun close() {
    connections.values.toList().forEach(PeerConnection::close)
    connections.clear()
    bySession.clear()
    peers.values.forEach {
      it.link?.close()
      it.outbound.close()
    }
    peers.clear()
    synchronized(this) {
      listener?.close()
      listener = null
      announcer?.close()
      announcer = null
    }
    scope.cancel()
  }

  companion object {
    /** `FetchRequest.flags`: stream the leased item on the same exchange right after the result. */
    const val FETCH_STREAM = 1
    const val RESULT_OK = 0
    const val RESULT_NOT_FOUND = 1
    const val ATT_OVERHEAD = 3
    const val DEFAULT_BLE_RECORD = 182
    const val MIN_BLE_RECORD = 100
    const val MAX_BLE_RECORD = 512
    /** 16 KiB fragments on a TCP control connection: few records per message, small buffers. */
    const val TCP_CONTROL_RECORD = 16 * 1024 + RecordCodec.HELLO_HEADER_BYTES
    private const val BLE_OUTBOUND_CAPACITY = 64
    private const val CLOCK_SKEW_MILLIS = 24 * 60 * 60_000L
    private const val P2P_SUBNET = "192.168.49."
    private const val PROGRESS_INTERVAL_MILLIS = 100L
    private const val STREAM_IDLE_MILLIS = 20_000L
    private const val UPDATE_PREPARE_MILLIS = 10 * 60_000L
    private const val UPDATE_POLL_MILLIS = 250L

    fun newPairingKeyId(psk: ByteArray): Int = SessionKeys.keyId(psk)

    fun randomId(): UUID = UUID.nameUUIDFromBytes(TransferCrypto.randomBytes(16))
  }
}
