package com.betterhv.note.sender.desktop

import com.betterhv.note.sender.shared.noteLinkText
import com.betterhv.transfer.core.ContentCounts
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.ExportTransferOffer
import com.betterhv.transfer.core.NOTELINK_LAN_PORT
import com.betterhv.transfer.core.NoteLinkAdvertisementCodec
import com.betterhv.transfer.core.TransferErrorCode
import com.betterhv.transfer.core.TransferEvent
import com.betterhv.transfer.core.TransferEventLog
import com.betterhv.transfer.core.TransferFailure
import com.betterhv.transfer.core.TransferLimits
import com.betterhv.transfer.core.TransferLogEntry
import com.betterhv.transfer.core.TransferLogLevel
import com.betterhv.transfer.core.TransferMode
import com.betterhv.transfer.core.TransferObservable
import com.betterhv.transfer.core.TransferPhase
import com.betterhv.transfer.core.TransferProgressMeter
import com.betterhv.transfer.core.TransferSnapshot
import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.core.v4.notelink.ByteSink
import com.betterhv.transfer.core.v4.notelink.FileByteSink
import com.betterhv.transfer.core.v4.notelink.HostLease
import com.betterhv.transfer.core.v4.notelink.NoteLinkHost
import com.betterhv.transfer.core.v4.notelink.NoteLinkProto
import com.betterhv.transfer.core.v4.notelink.NoteLinkServer
import com.betterhv.transfer.core.v4.notelink.NoteLinkTransferEvent
import com.betterhv.transfer.core.v4.notelink.PayloadSource
import com.betterhv.transfer.core.v4.notelink.PushDecision
import com.betterhv.transfer.core.v4.observability.LinkTimeline
import com.betterhv.transfer.core.v4.observability.LinkTimelineRecorder
import com.betterhv.transfer.core.v4.observability.LinkTimelineSummary
import com.betterhv.transfer.core.v4.pairing.PairingOutcome
import com.betterhv.transfer.core.v4.pairing.PairingResponder
import com.betterhv.transfer.core.v4.session.PairingKey
import com.betterhv.transfer.core.v4.session.PairingKeyStore
import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.Endpoint
import com.betterhv.transfer.proto.v4.Feature
import com.betterhv.transfer.proto.v4.PushOffer
import com.betterhv.transfer.proto.v4.UpdateCheck
import com.betterhv.transfer.proto.v4.UpdateResult
import com.betterhv.transfer.proto.v4.UpdateState
import com.betterhv.transfer.windows.BleControlEvent
import com.betterhv.transfer.windows.WindowsCapabilities
import com.betterhv.transfer.windows.WindowsNativeApi
import com.betterhv.update.NoteAppUpdateCoordinator
import com.betterhv.update.NoteAppUpdateTaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.betterhv.transfer.proto.v4.ContentKind as ProtoContentKind

data class DesktopTransferStatus(
  val running: Boolean = false,
  val capabilities: WindowsCapabilities? = null,
  val summary: String = noteLinkText("正在检查设备", "Checking device"),
  val detail: String = "",
  val transfer: TransferSnapshot = TransferSnapshot(),
  val transferLog: List<TransferLogEntry> = emptyList(),
  val timelineSummary: LinkTimelineSummary? = null,
)

/** Desktop pairing dialog state: the user types the code shown on the Note. */
sealed interface DesktopPairingState {
  data object Idle : DesktopPairingState
  data object Entering : DesktopPairingState
  data class Waiting(val code: String, val message: String? = null) : DesktopPairingState
  data class Paired(val deviceName: String) : DesktopPairingState
  data class Failed(val message: String) : DesktopPairingState
}

/**
 * Windows NoteLink host. The native GATT server and the TCP listener feed one [NoteLinkServer];
 * this class maps its callbacks onto the queue, the inbox and the Note update cache.
 */
class DesktopTransferService(
  private val native: WindowsNativeApi,
  private val settings: DesktopSettings,
  private val queue: DesktopQueueRepository,
  private val inbox: DesktopInboxRepository,
  updateCache: File,
  private val onPairingChanged: () -> Unit = {},
) : AutoCloseable,
  TransferObservable,
  NoteLinkHost {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val leases = ConcurrentHashMap<UUID, Pair<String, DesktopSenderLease>>()
  private val meters = ConcurrentHashMap<UUID, TransferProgressMeter>()
  private val updateOperations = ConcurrentHashMap<String, UUID>()
  private val mutableStatus = MutableStateFlow(DesktopTransferStatus())
  val status: StateFlow<DesktopTransferStatus> = mutableStatus.asStateFlow()
  private val mutableSnapshot = MutableStateFlow(TransferSnapshot())
  private val mutableEvents = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)
  private val eventLog = TransferEventLog()
  private val timelines = LinkTimelineRecorder()
  private val mutablePairing = MutableStateFlow<DesktopPairingState>(DesktopPairingState.Idle)
  val pairingState: StateFlow<DesktopPairingState> = mutablePairing.asStateFlow()
  @Volatile private var responder: PairingResponder? = null
  private val noteUpdate =
    NoteAppUpdateCoordinator(scope, updateCache) { file, descriptor, peerId ->
      val item = queue.enqueueAppPackage(file, descriptor, peerId)
      refreshCounts()
      item.id
    }
  val noteUpdateState = noteUpdate.state
  override val snapshot: StateFlow<TransferSnapshot> = mutableSnapshot.asStateFlow()
  override val events: SharedFlow<TransferEvent> = mutableEvents.asSharedFlow()
  val eventHistory: StateFlow<List<TransferLogEntry>> = eventLog.entries
  @Volatile private var server: NoteLinkServer? = null
  /** False when Windows has no usable BLE peripheral; NoteLink then runs over LAN only. */
  @Volatile private var bleRunning = false
  private var eventJob: Job? = null
  private var lanError: String? = null

  init {
    scope.launch { snapshot.collect { mutableStatus.value = mutableStatus.value.copy(transfer = it) } }
    scope.launch { eventHistory.collect { mutableStatus.value = mutableStatus.value.copy(transferLog = it) } }
  }

  override val localDeviceId: String get() = settings.localDeviceId
  override val localDeviceName: String get() = settings.displayName

  @Synchronized
  fun start() {
    if (eventJob?.isActive == true) return
    val version = windowsVersion()
    if (!version.supported) {
      DesktopLog.info("transfer.readiness.unsupported-os", version.message)
      mutableStatus.value = DesktopTransferStatus(
        summary = "Unsupported Windows version",
        detail = version.message,
        transferLog = eventLog.entries.value,
      )
      return
    }
    val capabilities = runCatching { native.capabilities() }.getOrElse {
      DesktopLog.error("transfer.readiness.capabilities", it)
      mutableStatus.value = DesktopTransferStatus(
        summary = "Wireless services unavailable",
        detail = it.message.orEmpty(),
        transferLog = eventLog.entries.value,
      )
      return
    }
    if (!capabilities.dataProtection) {
      mutableStatus.value = DesktopTransferStatus(
        running = false,
        capabilities = capabilities,
        summary = noteLinkText("设备能力不足", "Missing device capabilities"),
        detail = noteLinkText("Windows 数据保护", "Windows data protection"),
        transferLog = eventLog.entries.value,
      )
      DesktopLog.info("transfer.readiness.missing", "dpapi")
      return
    }
    adoptOrphanedItems()
    val created = NoteLinkServer(this, sendBle = { client, record -> native.notifyBle(client, record) })
    lanError = runCatching { created.start() }.exceptionOrNull()?.let {
      DesktopLog.error("transfer.lan.bind", it)
      noteLinkText(
        "端口 $NOTELINK_LAN_PORT 被占用，无法通过局域网连接",
        "Port $NOTELINK_LAN_PORT is in use; the Note cannot connect over the network",
      )
    }
    server = created
    // Bluetooth is optional: the Note finds this PC by LAN discovery (UDP 39818) and connects over
    // TCP 39817. BLE only adds reach when the two devices share no network.
    val bleProblem: String? = if (!capabilities.blePeripheral) {
      noteLinkText(
        "蓝牙不可用，仅通过局域网连接（Note 需与本机在同一网络）",
        "Bluetooth is unavailable; connecting over the network only (the Note must be on the same network)",
      )
    } else {
      val counts = queue.counts()
      runCatching {
        native.startBle(
          settings.localDeviceId, settings.displayName, counts.images, counts.texts, counts.pdfs, advertisementFlags()
        )
      }.exceptionOrNull()?.let {
        DesktopLog.error("transfer.ble.start", it)
        noteLinkText("蓝牙启动失败，仅通过局域网连接：", "Bluetooth failed to start; network only: ") + it.message.orEmpty()
      }
    }
    bleRunning = bleProblem == null
    if (!bleRunning && lanError != null) {
      created.close()
      server = null
      mutableStatus.value = DesktopTransferStatus(
        running = false,
        capabilities = capabilities,
        summary = noteLinkText("无法接收", "Cannot receive"),
        detail = listOfNotNull(bleProblem, lanError).joinToString(noteLinkText("；", "; ")),
        transferLog = eventLog.entries.value,
      )
      return
    }
    mutableStatus.value = DesktopTransferStatus(
      true,
      capabilities,
      readySummary(),
      detail = listOfNotNull(lanError, bleProblem).joinToString(noteLinkText("；", "; ")),
      transferLog = eventLog.entries.value,
    )
    if (!bleRunning) return
    eventJob = scope.launch { pumpBleEvents(created) }
  }

  /** Native event pump: blocks in the DLL until a record or connection event arrives. */
  private suspend fun pumpBleEvents(server: NoteLinkServer) {
    while (scope.isActive) {
      val event = runCatching { native.pollBleEvent(EVENT_WAIT_MILLIS) }.getOrElse {
        if (!scope.isActive) return
        DesktopLog.error("transfer.ble.event", it)
        delay(EVENT_ERROR_BACKOFF_MILLIS)
        null
      } ?: continue
      when (event) {
        is BleControlEvent.Record -> server.onBleRecord(event.client, event.bytes)
        is BleControlEvent.Mtu -> server.onBleMtu(event.client, event.mtu)
        is BleControlEvent.Connected -> DesktopLog.info("transfer.ble.connected", event.client.takeLast(17))
        is BleControlEvent.Disconnected -> {
          DesktopLog.info("transfer.ble.disconnected", event.client.takeLast(17))
          server.onBleClosed(event.client)
        }
      }
    }
  }

  @Synchronized
  fun stop() {
    eventJob?.cancel()
    eventJob = null
    server?.close()
    server = null
    leases.values.forEach { (_, lease) -> runCatching { lease.release() } }
    leases.clear()
    if (bleRunning) runCatching { native.stopBle() }
    bleRunning = false
    mutableStatus.value = mutableStatus.value.copy(
      running = false,
      summary = noteLinkText("接收已暂停", "Receiving paused"),
      detail = "",
    )
  }

  @Synchronized
  fun restart() {
    stop()
    start()
  }

  fun refreshCounts() {
    if (!mutableStatus.value.running) return
    val counts = queue.counts()
    if (bleRunning) {
      runCatching { native.updateBleCounts(counts.images, counts.texts, counts.pdfs, advertisementFlags()) }
        .onFailure { mutableStatus.value = mutableStatus.value.copy(detail = it.message.orEmpty()) }
    }
    server?.publishQueueChanged()
    if (mutableStatus.value.running) mutableStatus.value = mutableStatus.value.copy(summary = readySummary())
  }

  private fun advertisementFlags(): Int =
    if (responder?.isActive() == true) NoteLinkAdvertisementCodec.FLAG_PAIRING else 0

  private fun readySummary(): String {
    val counts = queue.counts()
    val connected = server?.connected?.value.orEmpty()
    return when {
      settings.pairing == null -> noteLinkText("点击“新增设备”与 Note 配对", "Choose Add device to pair with a Note")
      connected.isNotEmpty() -> noteLinkText(
        "已连接 Note（${connected.values.first().joinToString("+")}）",
        "Note connected (${connected.values.first().joinToString("+")})",
      )
      counts.images + counts.texts + counts.pdfs > 0 -> noteLinkText("已就绪，正在等待 Note", "Ready; waiting for Note")
      else -> noteLinkText("已就绪，暂无待发送内容", "Ready; nothing queued")
    }
  }

  // --- Pairing ---------------------------------------------------------------------------

  fun openPairing() {
    responder = null
    mutablePairing.value = if (mutableStatus.value.running) {
      DesktopPairingState.Entering
    } else {
      notReadyForPairing()
    }
  }

  /** Pairing runs over BLE or LAN; with neither running the Note can never find this PC. */
  private fun notReadyForPairing(): DesktopPairingState.Failed = DesktopPairingState.Failed(
    noteLinkText("接收未运行，无法配对：", "Receiving is not running, so pairing cannot start: ") +
      mutableStatus.value.detail.ifBlank { mutableStatus.value.summary },
  )

  /** Starts responding to a Note that shows [code]; the BLE advertisement gains the pairing flag. */
  fun submitPairingCode(code: String) {
    if (!mutableStatus.value.running) {
      mutablePairing.value = notReadyForPairing()
      return
    }
    responder = PairingResponder(code, settings.localDeviceId, settings.displayName)
    mutablePairing.value = DesktopPairingState.Waiting(code)
    refreshCounts()
  }

  fun cancelPairing() {
    responder = null
    mutablePairing.value = DesktopPairingState.Idle
    refreshCounts()
  }

  override fun pairingResponder(): PairingResponder? {
    val current = responder ?: return null
    if (current.isActive()) return current
    responder = null
    mutablePairing.value = DesktopPairingState.Failed(
      noteLinkText("配对码已过期，请重新输入", "The pairing code expired; enter it again"),
    )
    refreshCounts()
    return null
  }

  override fun onPaired(outcome: PairingOutcome) {
    // Queue items without a destination already match every paired Note.
    val stored = settings.storePairing(outcome.peerDeviceId, outcome.peerDeviceName, outcome.psk)
    adoptOrphanedItems()
    responder = null
    mutablePairing.value = DesktopPairingState.Paired(stored.deviceName)
    DesktopLog.info("transfer.pairing.complete", stored.deviceId.takeLast(8))
    refreshCounts()
    onPairingChanged()
  }

  /** Items queued for a Note that was unpaired go to the most recently used paired Note. */
  private fun adoptOrphanedItems() {
    val target = settings.pairing ?: return
    val ids = settings.pairings.map(DesktopPairing::deviceId).toSet()
    runCatching { queue.adoptOrphans(ids, target.deviceId) }
      .onSuccess { if (it > 0) DesktopLog.info("transfer.queue.adopted", "$it -> ${target.deviceId.takeLast(8)}") }
      .onFailure { DesktopLog.error("transfer.queue.adopt", it) }
  }

  override fun onPairingFailed(attemptsLeft: Int) {
    val current = mutablePairing.value as? DesktopPairingState.Waiting ?: return
    if (attemptsLeft <= 0) {
      responder = null
      mutablePairing.value = DesktopPairingState.Failed(
        noteLinkText("配对码多次错误，请重新输入", "Too many wrong codes; enter the code again"),
      )
      refreshCounts()
    } else {
      mutablePairing.value = current.copy(
        message = noteLinkText("配对码与 Note 不符（剩余 $attemptsLeft 次）", "Code did not match the Note ($attemptsLeft left)"),
      )
    }
  }

  override fun pairings(): PairingKeyStore = PairingKeyStore { keyId ->
    settings.pairings.firstNotNullOfOrNull { pairing ->
      if (SessionKeys.keyId(pairing.sharedKey) == keyId) PairingKey(pairing.deviceId, pairing.sharedKey) else null
    }
  }

  // --- NoteLinkHost ------------------------------------------------------------------------

  override fun capabilities(): CapabilitySet {
    val addresses = runCatching { native.lanInfo().addresses }.getOrDefault(emptyList())
    val port = server?.lanBoundPort?.takeIf { it > 0 } ?: NOTELINK_LAN_PORT
    val builder = CapabilitySet.newBuilder()
      .setTransports(TRANSPORT_LAN)
      .setMaxRecordBytes(64 * 1024)
      .addAllKinds(
        listOf(
          ProtoContentKind.CONTENT_IMAGE,
          ProtoContentKind.CONTENT_TEXT,
          ProtoContentKind.CONTENT_PDF,
          ProtoContentKind.CONTENT_APP_PACKAGE,
        ),
      )
      // No Wi-Fi Direct on Windows: FEATURE_P2P_* is never advertised.
      .setFeatures(Feature.FEATURE_LAN_TICKET_VALUE)
      .setDeviceName(settings.displayName)
    if (lanError == null) {
      addresses.firstOrNull()?.let { builder.setLan(Endpoint.newBuilder().setHost(it).setPort(port)) }
      addresses.forEach { builder.addLanCandidates(Endpoint.newBuilder().setHost(it).setPort(port)) }
    }
    return builder.build()
  }

  override fun advertisedCounts(): ContentCounts = queue.counts()

  override fun counts(peerDeviceId: String): ContentCounts {
    queue.releaseExpired(System.currentTimeMillis())
    return queue.counts(peerDeviceId)
  }

  override fun lease(peerDeviceId: String, kind: ContentKind): HostLease? {
    val lease = queue.leaseNext(kind, peerDeviceId) ?: return null
    leases[lease.item.id] = peerDeviceId to lease
    runCatching { lease.markTransferring() }
    refreshAdvertisementOnly()
    return hostLease(lease)
  }

  private fun hostLease(lease: DesktopSenderLease): HostLease {
    val item = lease.item
    val text = lease.text?.encodeToByteArray()
    val payload = when {
      text != null -> PayloadSource.of(text)
      else -> lease.file?.let { PayloadSource.of(it, item.sha256) }
    }
    val offer = NoteLinkProto.offer(
      id = item.id,
      kind = item.kind,
      mimeType = item.mimeType,
      byteLength = payload?.length ?: item.byteLength,
      sha256 = payload?.sha256 ?: item.sha256,
      displayName = item.displayName,
      leaseTtlMillis = TransferLimits.LEASE_TIMEOUT_MILLIS,
      inlineData = text?.takeIf { it.size <= NoteLinkProto.INLINE_TEXT_BYTES },
    )
    return HostLease(offer, payload)
  }

  override fun leased(peerDeviceId: String, itemId: UUID): HostLease? {
    val (owner, lease) = leases[itemId] ?: return null
    if (owner != peerDeviceId) return null
    runCatching { lease.heartbeat() }
    return hostLease(lease)
  }

  override fun heartbeat(peerDeviceId: String, itemId: UUID): Boolean {
    val (owner, lease) = leases[itemId] ?: return false
    return owner == peerDeviceId && runCatching { lease.heartbeat() }.isSuccess
  }

  override fun commit(peerDeviceId: String, itemId: UUID): Boolean {
    val entry = leases[itemId] ?: return false
    if (entry.first != peerDeviceId) return false
    leases.remove(itemId)
    return runCatching { entry.second.commit() }.onSuccess {
      settings.markLastUsed(peerDeviceId)
      refreshAdvertisementOnly()
      emitEvent(TransferEvent.Completed(itemId, itemId))
    }.isSuccess
  }

  override fun release(peerDeviceId: String, itemId: UUID): Boolean {
    val entry = leases[itemId] ?: return false
    if (entry.first != peerDeviceId) return false
    leases.remove(itemId)
    return runCatching { entry.second.release() }.onSuccess { refreshAdvertisementOnly() }.isSuccess
  }

  /** Counts changed through the protocol; the server already published them to the peer. */
  private fun refreshAdvertisementOnly() {
    if (!mutableStatus.value.running || !bleRunning) return
    val counts = queue.counts()
    runCatching { native.updateBleCounts(counts.images, counts.texts, counts.pdfs, advertisementFlags()) }
  }

  override fun push(peerDeviceId: String, offer: PushOffer): PushDecision {
    val id = NoteLinkProto.uuid(offer.itemId)
    val export = runCatching {
      ExportTransferOffer(id, offer.displayName, offer.mimeType, offer.byteLength, offer.sha256.toByteArray())
    }.getOrElse { return PushDecision.Reject(it.message ?: "Invalid export") }
    return when (val begin = inbox.begin(peerDeviceId, export)) {
      DesktopInboxBegin.AlreadyReceived -> PushDecision.AlreadyReceived
      is DesktopInboxBegin.Receive -> {
        val file = FileByteSink(begin.partial)
        val sink = object : ByteSink by file {
          override fun abort() {
            file.abort()
            inbox.fail(id, "Transfer interrupted")
          }
        }
        PushDecision.Receive(sink) { inbox.complete(id, begin.partial) }
      }
    }
  }

  override fun updateCheck(peerDeviceId: String, check: UpdateCheck): UpdateResult {
    val operationKey = "$peerDeviceId:${check.operationId.toStringUtf8()}:${check.versionName}"
    val operation = updateOperations.getOrPut(operationKey) { UUID.randomUUID() }
    val state = noteUpdate.begin(operation, peerDeviceId, check.versionName.ifBlank { "0.0.0" })
    val result = UpdateResult.newBuilder()
    when (state) {
      NoteAppUpdateTaskState.Resolving -> result.setState(UpdateState.UPDATE_PREPARING)
      is NoteAppUpdateTaskState.Downloading -> result.setState(UpdateState.UPDATE_PREPARING)
        .setLatestVersion(state.version)
        .setBytesPrepared(state.bytesDownloaded)
        .setTotalBytes(state.totalBytes)
      is NoteAppUpdateTaskState.UpToDate -> {
        updateOperations.remove(operationKey)
        result.setState(UpdateState.UPDATE_UP_TO_DATE).setLatestVersion(state.latestVersion.orEmpty())
      }
      is NoteAppUpdateTaskState.Failed -> {
        updateOperations.remove(operationKey)
        result.setState(UpdateState.UPDATE_FAILED).setMessage(state.message)
      }
      is NoteAppUpdateTaskState.Ready -> {
        updateOperations.remove(operationKey)
        val lease = queue.leaseNext(ContentKind.APP_PACKAGE, peerDeviceId)
          ?: return result.setState(UpdateState.UPDATE_FAILED).setMessage("Update package is not queued").build()
        leases[lease.item.id] = peerDeviceId to lease
        result.setState(UpdateState.UPDATE_AVAILABLE).setLatestVersion(state.version).setOffer(hostLease(lease).offer)
      }
    }
    return result.build()
  }

  override fun onSessionChanged(peerDeviceId: String, links: List<String>) {
    DesktopLog.info("transfer.session", "peer=${peerDeviceId.takeLast(8)} links=$links")
    if (lanError == null && mutableStatus.value.running) {
      mutableStatus.value = mutableStatus.value.copy(summary = readySummary())
    }
  }

  override fun onTransfer(event: NoteLinkTransferEvent) {
    when (event) {
      is NoteLinkTransferEvent.Started -> {
        meters[event.itemId] = TransferProgressMeter(System.currentTimeMillis())
        val now = System.currentTimeMillis()
        mutableSnapshot.value = TransferSnapshot(
          operationId = event.itemId,
          itemId = event.itemId,
          deviceId = event.peerDeviceId,
          phase = TransferPhase.TRANSFERRING,
          mode = if (event.link == "ble") null else TransferMode.LAN,
          totalBytes = event.totalBytes,
          startedAtMillis = now,
          phaseStartedAtMillis = now,
        )
        emitEvent(TransferEvent.PhaseChanged(event.itemId, TransferPhase.IDLE, TransferPhase.TRANSFERRING))
      }
      is NoteLinkTransferEvent.Progress -> {
        val meter = meters[event.itemId] ?: return
        val sample = meter.sample(event.bytes, event.totalBytes)
        mutableSnapshot.value = mutableSnapshot.value.copy(
          bytesTransferred = event.bytes,
          totalBytes = event.totalBytes,
          bytesPerSecond = sample.bytesPerSecond,
          averageBytesPerSecond = sample.averageBytesPerSecond,
          etaMillis = sample.etaMillis,
        )
      }
      is NoteLinkTransferEvent.Finished -> {
        meters.remove(event.itemId)
        mutableSnapshot.value = mutableSnapshot.value.copy(
          phase = TransferPhase.COMPLETE,
          bytesTransferred = mutableSnapshot.value.totalBytes,
          phaseStartedAtMillis = System.currentTimeMillis(),
        )
        timelines.record(
          LinkTimeline(
            operation = "stream",
            transport = mutableSnapshot.value.mode?.name?.lowercase() ?: "ble",
            totalMillis = event.millis,
          ),
        )
        mutableStatus.value = mutableStatus.value.copy(timelineSummary = timelines.summary())
        emitEvent(TransferEvent.PhaseChanged(event.itemId, TransferPhase.TRANSFERRING, TransferPhase.COMPLETE))
      }
      is NoteLinkTransferEvent.Failed -> {
        meters.remove(event.itemId)
        val failure = TransferFailure(TransferErrorCode.INTERNAL, event.message, true)
        mutableSnapshot.value = mutableSnapshot.value.copy(
          phase = TransferPhase.FAILED, lastFailure = failure, canRetry = true
        )
        emitEvent(TransferEvent.Failed(event.itemId, failure))
      }
    }
  }

  override fun log(message: String) = DesktopLog.info("transfer.v4", message)

  fun importNotePackage(file: File): String = noteUpdate.importLatest(file).version.display

  private fun emitEvent(event: TransferEvent) {
    if (!mutableEvents.tryEmit(event)) DesktopLog.info("transfer.event.dropped", event.javaClass.simpleName)
    val entry = eventLog.record(event)
    val detail = "operation=${entry.operationId ?: "none"} ${entry.detail}"
    when (entry.level) {
      TransferLogLevel.INFO -> DesktopLog.info("transfer.${entry.category}", detail)
      TransferLogLevel.WARNING -> DesktopLog.info("transfer.warning.${entry.category}", detail)
      TransferLogLevel.ERROR -> DesktopLog.info("transfer.error.${entry.category}", detail)
    }
  }

  override fun cancel() {
    mutableSnapshot.value = TransferSnapshot()
  }

  override fun close() {
    stop()
    noteUpdate.close()
    scope.cancel()
    native.close()
  }

  private data class WindowsVersion(val supported: Boolean, val message: String)

  private fun windowsVersion(): WindowsVersion {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
      return WindowsVersion(false, "Only Windows 10 1903+ and Windows 11 x64 are supported")
    }
    val architecture = System.getProperty("os.arch")
    if (architecture != "amd64" && architecture != "x86_64") return WindowsVersion(false, "Only x64 is supported")
    val version = System.getProperty("os.version").split('.').mapNotNull(String::toIntOrNull)
    return if ((version.firstOrNull() ?: 0) < 10) {
      WindowsVersion(false, "Windows 10 1903 or later is required")
    } else {
      WindowsVersion(true, "")
    }
  }

  private companion object {
    const val TRANSPORT_LAN = 1
    const val EVENT_WAIT_MILLIS = 5_000
    const val EVENT_ERROR_BACKOFF_MILLIS = 500L
  }
}
