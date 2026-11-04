package com.betterhv.note

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.betterhv.transfer.android.AndroidPairingController
import com.betterhv.transfer.android.BleProtocolClient
import com.betterhv.transfer.android.BleReceiverScanner
import com.betterhv.transfer.android.DiscoveredSender
import com.betterhv.transfer.android.TransferPermissions
import com.betterhv.transfer.android.V4UnsupportedException
import com.betterhv.transfer.android.WifiDirectController
import com.betterhv.transfer.core.ContentCounts
import com.betterhv.transfer.core.PairedDevice
import com.betterhv.transfer.core.v4.discovery.PeerAddressBook
import com.betterhv.transfer.core.v4.discovery.PeerProtocol
import com.betterhv.transfer.core.v4.notelink.NoteLinkClient
import com.betterhv.transfer.core.v4.notelink.NoteLinkSession
import com.betterhv.transfer.core.v4.lan.LanDiscovery
import com.betterhv.transfer.core.v4.lan.TcpAttach
import com.betterhv.transfer.core.v4.lan.TcpRecordLink
import com.betterhv.transfer.core.v4.session.RecordLink
import com.betterhv.transfer.core.v4.session.HandshakeException
import com.betterhv.transfer.core.v4.session.PeerConnection
import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.ContentKind as ProtoContentKind
import com.betterhv.transfer.proto.v4.ErrorCode
import com.betterhv.transfer.proto.v4.Feature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/** Connection state of one paired NoteLink, as shown in settings and used by the insert path. */
data class PeerLinkState(
  val peerId: String,
  val status: Status,
  val links: List<String> = emptyList(),
  val counts: ContentCounts? = null,
  val message: String? = null,
  /** Transport of the control plane of the current session: [PeerConnection.LABEL_LAN] or BLE. */
  val control: String? = null,
) {
  enum class Status { CONNECTING, CONNECTED, OFFLINE, UNPAIRED_BY_PEER }

  val online: Boolean get() = status == Status.CONNECTED

  /** Connected with known counts, or definitely not reachable: nothing more to wait for. */
  val settled: Boolean get() = when (status) {
    Status.CONNECTED -> counts != null
    Status.OFFLINE, Status.UNPAIRED_BY_PEER -> true
    Status.CONNECTING -> false
  }

  /** How this NoteLink is reached right now, e.g. "LAN (UDP discovery)", shown after its name. */
  val modeLabel: String? get() {
    if (!online) return null
    val controlLabel = when (control) {
      PeerConnection.LABEL_LAN -> noteText("局域网 UDP 发现", "LAN · UDP discovery")
      PeerConnection.LABEL_BLE -> noteText("蓝牙", "Bluetooth")
      else -> return null
    }
    return if (PeerConnection.LABEL_P2P in links) "$controlLabel + Wi-Fi Direct" else controlLabel
  }
}

/**
 * Keeps one warm v4 session per paired NoteLink while the Note app is in the foreground:
 * BLE control link + LAN TCP link, reconnecting with backoff. An insert then needs one round
 * trip plus the payload instead of scan → GATT connect → MTU → handshake → LAN dial.
 */
class NoteLinkConnections private constructor(
  context: Context,
  val pairing: AndroidPairingController,
  val addressBook: PeerAddressBook,
  val wifiDirect: WifiDirectController,
) {
  private val appContext = context.applicationContext
  private val scanGate = ScanGate(BleReceiverScanner(appContext))
  private val lanAddresses = appContext.getSharedPreferences(LAN_PREFS, Context.MODE_PRIVATE)
  private var scope: CoroutineScope? = null
  private val peers = HashMap<String, Job>()
  private val sessions = HashMap<String, NoteLinkSession>()
  private val sessionUpdates = MutableStateFlow(0L)
  private var screenReceiver: android.content.BroadcastReceiver? = null
  private val mutableStates = MutableStateFlow<Map<String, PeerLinkState>>(emptyMap())

  val states: StateFlow<Map<String, PeerLinkState>> = mutableStates.asStateFlow()

  @Synchronized
  fun session(peerId: String): NoteLinkSession? = sessions[peerId]?.takeIf { it.connection.isOpen }

  /** Starts (or refreshes) a connection loop for every paired NoteLink. */
  @Synchronized
  fun start() {
    if (!TransferPermissions.hasAllNotePermissions(appContext)) {
      Log.i(TAG, "warm links not started: Nearby devices permission is missing")
      return
    }
    val active = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
    val paired = pairing.pairedClients.filterNot { it.legacy }
    peers.keys.filter { id -> paired.none { it.id == id } }.forEach(::forget)
    paired.forEach { client ->
      if (peers[client.id]?.isActive != true) peers[client.id] = active.launch { maintain(client) }
    }
    registerScreenOn()
  }

  /**
   * The tablet suspends its CPU while the screen is off, so keepalives stop and the NoteLink
   * drops the session after ~25 s. Checking every session the moment the screen turns on lets the
   * reconnect run while the user is still writing instead of when they tap Insert.
   */
  private fun registerScreenOn() {
    if (screenReceiver != null) return
    val receiver = object : android.content.BroadcastReceiver() {
      override fun onReceive(context: Context, intent: android.content.Intent) {
        scope?.launch { revalidateAll() }
      }
    }
    appContext.registerReceiver(receiver, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON))
    screenReceiver = receiver
  }

  private suspend fun revalidateAll() {
    val current = synchronized(this) { sessions.values.toList() }
    current.forEach { session -> validate(session) }
  }

  /** Probes a session that has been quiet longer than a keepalive period; closes it when dead. */
  private suspend fun validate(session: NoteLinkSession): Boolean {
    if (!session.connection.isOpen) return false
    if (session.connection.idleMillis() < STALE_AFTER_MILLIS) return true
    val started = System.nanoTime()
    val alive = session.connection.probe(PROBE_TIMEOUT_MILLIS)
    Log.i(TAG, "probe ${session.peerDeviceId} alive=$alive in ${(System.nanoTime() - started) / 1_000_000} ms")
    if (!alive) session.connection.close()
    return alive
  }

  /** Drops every link; called when the app goes to the background. */
  @Synchronized
  fun stop() {
    screenReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
    screenReceiver = null
    scope?.coroutineContext?.get(Job)?.cancel()
    scope = null
    peers.clear()
    sessions.values.forEach { it.connection.close() }
    sessions.clear()
    mutableStates.value = emptyMap()
    wifiDirect.requestCloseGroup()
  }

  @Synchronized
  fun forget(peerId: String) {
    lanAddresses.edit { remove(peerId) }
    peers.remove(peerId)?.cancel()
    sessions.remove(peerId)?.connection?.close()
    mutableStates.update { it - peerId }
    addressBook.forget(peerId)
  }

  /**
   * Returns a live session for [peerId], reconnecting when needed. A session that was quiet
   * (e.g. the tablet slept) is probed first, so a dead one is replaced before the transfer
   * starts instead of failing halfway through it.
   */
  suspend fun await(peerId: String, timeoutMillis: Long): NoteLinkSession? {
    session(peerId)?.let { if (validate(it)) return it }
    start()
    return withTimeoutOrNull(timeoutMillis) {
      sessionUpdates.first {
        session(peerId) != null || states.value[peerId]?.status == PeerLinkState.Status.UNPAIRED_BY_PEER
      }
      session(peerId)
    }
  }

  private suspend fun maintain(client: PairedDevice) {
    var attempt = 0
    while (currentCoroutineContextActive()) {
      publish(client.id) { it.copy(status = PeerLinkState.Status.CONNECTING) }
      val outcome = runCatching { connectOnce(client) }
      val error = outcome.exceptionOrNull()
      if (error is CancellationException) throw error
      if (error is HandshakeException && error.code == ErrorCode.ERROR_UNKNOWN_PAIRING) {
        Log.w(TAG, "${client.name} no longer knows this Note")
        publish(client.id) {
          it.copy(status = PeerLinkState.Status.UNPAIRED_BY_PEER, links = emptyList(), message = error.message)
        }
        return
      }
      if (error == null) attempt = 0 else attempt++
      publish(client.id) {
        it.copy(status = PeerLinkState.Status.OFFLINE, links = emptyList(), message = error?.message, control = null)
      }
      error?.let { Log.i(TAG, "${client.name} offline: ${it.message}") }
      delay(BACKOFF_MILLIS[attempt.coerceAtMost(BACKOFF_MILLIS.lastIndex)])
    }
  }

  private suspend fun currentCoroutineContextActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

  /** One connect → handshake → serve cycle; returns when the session ended. */
  private suspend fun connectOnce(client: PairedDevice) {
    val key = pairing.sharedKey(client.id) ?: throw IllegalStateException("Pairing key is unavailable")
    val started = System.nanoTime()
    val control = connectControl(client)
    val session = try {
      NoteLinkClient.handshake(
        scope = checkNotNull(scope),
        link = control,
        pairingKey = key,
        localDeviceId = pairing.localDeviceId,
        peerDeviceId = client.id,
        capabilities = localCapabilities(),
        onLinkClosed = { label, reason -> Log.i(TAG, "link $label closed ${client.name}: $reason") },
      )
    } catch (error: Throwable) {
      control.close()
      throw error
    }
    when (control) {
      is BleProtocolClient -> addressBook.recordSuccess(client.id, control.address, PeerProtocol.V4)
      is TcpRecordLink -> rememberLan(client.id, control.remoteAddress, control.remotePort)
    }
    synchronized(this) { sessions[client.id] = session }
    publish(client.id) { it.copy(control = control.label) }
    Log.i(
      TAG,
      "session up ${client.name} via=${control.label} ${describe(control)} in ${(System.nanoTime() - started) / 1_000_000} ms",
    )
    runCatching { pairing.markLastUsed(client.id) }
    try {
      kotlinx.coroutines.coroutineScope {
        launch { session.counts.collect { counts -> publish(client.id) { it.copy(counts = counts) } } }
        launch {
          session.connection.links.collect { links ->
            val status = if (links.isEmpty()) PeerLinkState.Status.CONNECTING else PeerLinkState.Status.CONNECTED
            publish(client.id) { it.copy(status = status, links = links, message = null) }
          }
        }
        launch { keepLanAttached(session) }
        // The session ends when its last link closes or a probe found it dead.
        session.connection.awaitClosed()
        coroutineContext[Job]?.cancelChildren()
      }
    } finally {
      synchronized(this) { if (sessions[client.id] === session) sessions.remove(client.id) }
      sessionUpdates.value = System.nanoTime()
      session.connection.close()
      control.close()
    }
  }

  private fun describe(link: RecordLink): String = when (link) {
    is BleProtocolClient -> "ble=${link.address} mtu=${link.mtu}"
    is TcpRecordLink -> "lan=${link.remoteAddress}:${link.remotePort}"
    else -> link.label
  }

  /**
   * Opens a control link over whichever transport answers first. LAN (remembered address, then
   * UDP discovery) is raced against BLE, so a NoteLink without working Bluetooth — or a Note
   * whose Bluetooth is off — still connects, and a NoteLink on the same Wi-Fi is reached in a
   * few milliseconds instead of a BLE scan + GATT setup.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private suspend fun connectControl(client: PairedDevice): RecordLink = kotlinx.coroutines.coroutineScope {
    val lan = async {
      runCatching { connectLanControl(client) }
        .onFailure { Log.i(TAG, "lan control ${client.name}: ${it.message}") }
        .getOrNull()
    }
    val ble = async {
      // Give LAN a head start: when it answers, the radio is never touched.
      delay(BLE_HEAD_START_MILLIS)
      if (lan.isCompleted && lan.getCompleted() != null) return@async null
      if (!bluetoothOn()) return@async null
      runCatching { connectBle(client) }
        .onFailure { Log.i(TAG, "ble control ${client.name}: ${it.message}") }
        .getOrNull()
    }
    val winner: RecordLink? = kotlinx.coroutines.selects.select {
      lan.onAwait { it ?: ble.await() }
      ble.onAwait { it ?: lan.await() }
    }
    // Close whichever finished second.
    listOf(lan, ble).forEach { attempt ->
      if (attempt.isCompleted) {
        attempt.getCompleted()?.takeIf { it !== winner }?.close()
      } else {
        launch { runCatching { attempt.await() }.getOrNull()?.takeIf { it !== winner }?.close() }
        attempt.cancel()
      }
    }
    winner ?: throw IllegalStateException("${client.name} is not reachable over LAN or Bluetooth")
  }

  private suspend fun connectLanControl(client: PairedDevice): TcpRecordLink? {
    rememberedLan(client.id)?.let { (host, port) ->
      runCatching { return TcpAttach.dialControl(host, port, connectTimeoutMillis = REMEMBERED_DIAL_MILLIS) }
        .onFailure { Log.i(TAG, "remembered lan $host:$port for ${client.name} failed: ${it.message}") }
    }
    val hash = client.identityHash ?: return null
    val peer = LanDiscovery.find(LAN_DISCOVERY_MILLIS) {
      it.identityHashHex.equals(hash, ignoreCase = true)
    } ?: return null
    return TcpAttach.dialControl(peer.host, peer.tcpPort)
  }

  private fun rememberLan(peerId: String, host: String, port: Int) {
    lanAddresses.edit { putString(peerId, "$host:$port") }
  }

  private fun rememberedLan(peerId: String): Pair<String, Int>? {
    val value = lanAddresses.getString(peerId, null) ?: return null
    val host = value.substringBeforeLast(':')
    val port = value.substringAfterLast(':').toIntOrNull() ?: return null
    return host to port
  }

  private fun bluetoothOn(): Boolean =
    appContext.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true

  private fun Job.cancelChildren() = children.forEach { it.cancel() }

  /** Attaches LAN at once and whenever the link or the peer's addresses change. */
  private suspend fun keepLanAttached(session: NoteLinkSession) {
    var failures = 0
    while (session.connection.isOpen) {
      if (!session.connection.hasLink(PeerConnection.LABEL_LAN) &&
        !session.connection.hasLink(PeerConnection.LABEL_P2P)
      ) {
        val candidates = NoteLinkClient.lanCandidates(session.capabilities)
        val started = System.nanoTime()
        val attached = NoteLinkClient.attachTcp(session, candidates) { endpoint, error ->
          Log.i(TAG, "lan dial $endpoint failed: ${error.javaClass.simpleName}: ${error.message}")
        }
        Log.i(
          TAG,
          "lan attach ${session.peerDeviceId} candidates=$candidates -> ${attached ?: "none"} " +
            "in ${(System.nanoTime() - started) / 1_000_000} ms",
        )
        // A peer in Wi-Fi power save can drop the first SYNs; retry quickly before settling
        // into the slow re-check.
        if (attached == null && failures < LAN_FAST_RETRIES) {
          failures++
          delay(LAN_FAST_RETRY_MILLIS * failures)
          continue
        }
        if (attached != null) failures = 0
      }
      // Re-check on a capability change, a lost TCP link, or every 15 s.
      withTimeoutOrNull(LAN_RECHECK_MILLIS) {
        kotlinx.coroutines.flow.merge(
          session.capabilityUpdates,
          session.connection.links,
        ).first { value ->
          when (value) {
            is CapabilitySet -> true
            is List<*> -> PeerConnection.LABEL_LAN !in value && PeerConnection.LABEL_P2P !in value
            else -> false
          }
        }
      }
      delay(LAN_RETRY_SPACING_MILLIS)
    }
  }

  /**
   * Returns true once a TCP link is up. The LAN attach starts with the session, so a request
   * made right after connecting waits for it instead of starting Wi-Fi Direct: joining a group
   * while Wi-Fi is in use makes the radio alternate channels and slows both paths.
   */
  suspend fun awaitFastLink(session: NoteLinkSession, timeoutMillis: Long = LAN_WAIT_MILLIS): Boolean {
    if (hasFastLink(session)) return true
    if (NoteLinkClient.lanCandidates(session.capabilities).isEmpty()) return false
    return withTimeoutOrNull(timeoutMillis) {
      session.connection.links.first { PeerConnection.LABEL_LAN in it || PeerConnection.LABEL_P2P in it }
      true
    } ?: false
  }

  private fun hasFastLink(session: NoteLinkSession): Boolean =
    session.connection.hasLink(PeerConnection.LABEL_LAN) || session.connection.hasLink(PeerConnection.LABEL_P2P)

  /**
   * Wi-Fi Direct fallback for large payloads when no LAN candidate answers. Only Android
   * NoteLinks host a group; Windows does not advertise the feature.
   */
  suspend fun ensureP2p(session: NoteLinkSession): Boolean {
    if (awaitFastLink(session)) return true
    if (session.peerCapabilities.features and Feature.FEATURE_P2P_CREDENTIAL_JOIN_VALUE == 0) return false
    return runCatching {
      val started = System.nanoTime()
      val group = NoteLinkClient.requestP2p(session)
      wifiDirect.joinHostedGroup(
        ownerDeviceAddress = "",
        ownerDeviceName = "",
        networkName = group.ssid,
        passphrase = group.passphrase,
        timeoutMillis = P2P_JOIN_MILLIS,
      )
      val owner = InetAddress.getByAddress(group.groupOwnerIp.toByteArray()).hostAddress ?: P2P_OWNER_FALLBACK
      val attached = NoteLinkClient.attachTcp(
        session,
        listOf(owner to group.port),
        label = PeerConnection.LABEL_P2P,
        timeoutMillis = P2P_ATTACH_MILLIS,
      )
      Log.i(
        TAG,
        "p2p attach ${session.peerDeviceId} owner=$owner -> $attached in ${(System.nanoTime() - started) / 1_000_000} ms",
      )
      attached != null
    }.onFailure { Log.w(TAG, "Wi-Fi Direct fallback failed: ${it.message}") }.getOrDefault(false)
  }

  private suspend fun connectBle(client: PairedDevice): BleProtocolClient {
    addressBook.addressFor(client.id)?.let { cached ->
      try {
        // Phones rotate their random BLE address; a stale entry should cost one short attempt.
        return BleProtocolClient.connect(appContext, cached, attempts = 1, connectTimeoutMillis = CACHED_CONNECT_MILLIS)
      } catch (error: V4UnsupportedException) {
        throw error
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        Log.i(TAG, "cached address $cached for ${client.name} failed: ${error.message}")
        addressBook.recordFailure(client.id)
      }
    }
    val sender = scanGate.find(SCAN_TIMEOUT_MILLIS) { matches(it, client) }
      ?: throw IllegalStateException("${client.name} is not advertising")
    return BleProtocolClient.connect(appContext, sender.bluetoothAddress)
  }

  private fun matches(sender: DiscoveredSender, client: PairedDevice): Boolean =
    sender.identityHash.isNotBlank() && sender.identityHash.equals(client.identityHash, ignoreCase = true)

  private fun publish(peerId: String, change: (PeerLinkState) -> PeerLinkState) {
    mutableStates.update { states ->
      val current = states[peerId] ?: PeerLinkState(peerId, PeerLinkState.Status.CONNECTING)
      states + (peerId to change(current))
    }
    sessionUpdates.value = System.nanoTime()
  }

  companion object {
    @Volatile private var instance: NoteLinkConnections? = null

    /**
     * One manager per process. The phone keeps a single session per Note device id and replaces
     * it on every handshake, so two managers in one process would keep evicting each other.
     */
    fun get(context: Context): NoteLinkConnections = instance ?: synchronized(this) {
      instance ?: context.applicationContext.let { app ->
        NoteLinkConnections(
          app,
          AndroidPairingController(app),
          PeerAddressBook(com.betterhv.transfer.android.AndroidPeerAddressStore(app)),
          WifiDirectController(app),
        )
      }.also { instance = it }
    }

    private const val TAG = "NoteLinkWarm"
    private val BACKOFF_MILLIS = longArrayOf(250, 1_000, 2_000, 5_000, 10_000)
    private const val SCAN_TIMEOUT_MILLIS = 8_000L
    private const val LAN_PREFS = "notelink_lan_addresses"
    private const val REMEMBERED_DIAL_MILLIS = 800
    private const val LAN_DISCOVERY_MILLIS = 1_500L
    private const val BLE_HEAD_START_MILLIS = 300L
    /** Longer than the 8 s keepalive: a quiet session this old missed at least one ping. */
    private const val STALE_AFTER_MILLIS = 12_000L
    private const val PROBE_TIMEOUT_MILLIS = 1_500L
    private const val CACHED_CONNECT_MILLIS = 3_000L
    private const val LAN_RECHECK_MILLIS = 15_000L
    private const val LAN_RETRY_SPACING_MILLIS = 300L
    /** Covers the first LAN attach plus one fast retry on a phone in Wi-Fi power save. */
    private const val LAN_WAIT_MILLIS = 2_000L
    private const val LAN_FAST_RETRIES = 3
    private const val LAN_FAST_RETRY_MILLIS = 500L
    private const val P2P_JOIN_MILLIS = 25_000L
    private const val P2P_ATTACH_MILLIS = 4_000L
    private const val P2P_OWNER_FALLBACK = "192.168.49.1"

    fun localCapabilities(): CapabilitySet = CapabilitySet.newBuilder()
      .setTransports(3)
      .setMaxRecordBytes(64 * 1024)
      .addKinds(ProtoContentKind.CONTENT_TEXT)
      .addKinds(ProtoContentKind.CONTENT_IMAGE)
      .addKinds(ProtoContentKind.CONTENT_PDF)
      .addKinds(ProtoContentKind.CONTENT_APP_PACKAGE)
      .setFeatures(Feature.FEATURE_LAN_TICKET_VALUE or Feature.FEATURE_P2P_CREDENTIAL_JOIN_VALUE)
      .setAppVersion(BuildConfig.VERSION_NAME)
      .build()
  }
}

/**
 * Android silently blocks an app that starts more than five scans in 30 s, so every scan in
 * the Note app goes through this gate: one scan at a time, at least 6.5 s between starts.
 */
class ScanGate(private val scanner: BleReceiverScanner) {
  private val mutex = Mutex()
  private var lastStart = 0L

  suspend fun find(timeoutMillis: Long, matches: (DiscoveredSender) -> Boolean): DiscoveredSender? =
    mutex.withLock {
      val wait = lastStart + MIN_SPACING_MILLIS - System.currentTimeMillis()
      if (wait > 0) delay(wait)
      lastStart = System.currentTimeMillis()
      scanner.discoverFirst(timeoutMillis, matches)
    }

  suspend fun discover(timeoutMillis: Long, onUpdate: (List<DiscoveredSender>) -> Unit = {}): List<DiscoveredSender> =
    mutex.withLock {
      val wait = lastStart + MIN_SPACING_MILLIS - System.currentTimeMillis()
      if (wait > 0) delay(wait)
      lastStart = System.currentTimeMillis()
      scanner.discover(timeoutMillis, onUpdate = onUpdate)
    }

  private companion object {
    const val MIN_SPACING_MILLIS = 6_500L
  }
}
