package com.betterhv.note.sender.desktop

import com.betterhv.note.sender.shared.noteLinkText
import com.betterhv.transfer.core.BleCommand
import com.betterhv.transfer.core.BleQueueProtocol
import com.betterhv.transfer.core.BleResponse
import com.betterhv.transfer.core.CapabilityNegotiation
import com.betterhv.transfer.core.DeviceCapabilities
import com.betterhv.transfer.core.NetworkEndpoint
import com.betterhv.transfer.core.TransferChannelException
import com.betterhv.transfer.core.TransferErrorCode
import com.betterhv.transfer.core.TransferEvent
import com.betterhv.transfer.core.TransferEventLog
import com.betterhv.transfer.core.TransferFailure
import com.betterhv.transfer.core.TransferLogEntry
import com.betterhv.transfer.core.TransferLogLevel
import com.betterhv.transfer.core.TransferMode
import com.betterhv.transfer.core.TransferModes
import com.betterhv.transfer.core.TransferNetworkSecurity
import com.betterhv.transfer.core.TransferObservable
import com.betterhv.transfer.core.TransferPhase
import com.betterhv.transfer.core.TransferProgressMeter
import com.betterhv.transfer.core.TransferSnapshot
import com.betterhv.transfer.windows.WindowsCapabilities
import com.betterhv.transfer.windows.WindowsFileTransfer
import com.betterhv.transfer.windows.WindowsNativeApi
import com.betterhv.transfer.windows.WindowsReplayCache
import com.betterhv.transfer.windows.WindowsSecureEnvelope
import kotlinx.coroutines.CancellationException
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
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class DesktopTransferStatus(
  val running: Boolean = false,
  val capabilities: WindowsCapabilities? = null,
  val summary: String = noteLinkText("正在检查设备", "Checking device"),
  val detail: String = "",
  val transfer: TransferSnapshot = TransferSnapshot(),
  val transferLog: List<TransferLogEntry> = emptyList(),
)

class DesktopTransferService(
  private val native: WindowsNativeApi,
  private val settings: DesktopSettings,
  private val queue: DesktopQueueRepository,
  private val inbox: DesktopInboxRepository,
  private val onPairingChanged: () -> Unit = {},
) : AutoCloseable,
  TransferObservable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val leases = ConcurrentHashMap<UUID, DesktopSenderLease>()
  private val leaseOwners = ConcurrentHashMap<UUID, String>()
  private val startedLeases = ConcurrentHashMap.newKeySet<UUID>()
  private val replay = WindowsReplayCache()
  private val peerCapabilities = ConcurrentHashMap<String, DeviceCapabilities>()
  private val pushed = ConcurrentHashMap<UUID, PushState>()
  private val pushedOwners = ConcurrentHashMap<UUID, String>()
  private val transferJobs = ConcurrentHashMap<UUID, Job>()
  private val receiveAttemptTokens = ConcurrentHashMap<UUID, Any>()
  private val mutableStatus = MutableStateFlow(DesktopTransferStatus())
  val status: StateFlow<DesktopTransferStatus> = mutableStatus.asStateFlow()
  private val mutableSnapshot = MutableStateFlow(TransferSnapshot())
  private val mutableEvents = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)
  private val eventLog = TransferEventLog()
  override val snapshot: StateFlow<TransferSnapshot> = mutableSnapshot.asStateFlow()
  override val events: SharedFlow<TransferEvent> = mutableEvents.asSharedFlow()
  val eventHistory: StateFlow<List<TransferLogEntry>> = eventLog.entries
  private var pollJob: Job? = null

  @Volatile private var listeningSocket: ServerSocket? = null

  init {
    scope.launch { snapshot.collect { mutableStatus.value = mutableStatus.value.copy(transfer = it) } }
    scope.launch { eventHistory.collect { mutableStatus.value = mutableStatus.value.copy(transferLog = it) } }
  }

  @Synchronized fun start() {
    if (pollJob?.isActive == true) return
    val version = windowsVersion()
    if (!version.supported) {
      DesktopLog.info("transfer.readiness.unsupported-os", version.message)
      mutableStatus.value = DesktopTransferStatus(
        summary = noteLinkText("不支持此 Windows 版本", "Unsupported Windows version"),
        detail = version.message,
        transferLog = eventLog.entries.value,
      )
      return
    }
    val capabilities = runCatching { native.capabilities() }.getOrElse {
      DesktopLog.error("transfer.readiness.capabilities", it)
      mutableStatus.value = DesktopTransferStatus(
        summary = noteLinkText("无线服务不可用", "Wireless services unavailable"),
        detail = it.message.orEmpty(),
        transferLog = eventLog.entries.value,
      )
      return
    }
    if (!capabilities.ready) {
      val missing = buildList {
        if (!capabilities.blePeripheral) add(noteLinkText("BLE 外设模式", "BLE peripheral mode"))
        if (!capabilities.lan) add(noteLinkText("活动 WLAN", "Active Wi-Fi"))
        if (!capabilities.dataProtection) add(noteLinkText("Windows 数据保护", "Windows data protection"))
      }
      mutableStatus.value = DesktopTransferStatus(
        running = false,
        capabilities = capabilities,
        summary = noteLinkText("设备能力不足", "Missing device capabilities"),
        detail = missing.joinToString("、"),
        transferLog = eventLog.entries.value,
      )
      DesktopLog.info("transfer.readiness.missing", missing.joinToString(","))
      return
    }
    val counts = queue.counts()
    runCatching {
      native.startBle(settings.localDeviceId, settings.displayName, counts.images, counts.texts, counts.pdfs)
    }
      .onFailure {
        DesktopLog.error("transfer.ble.start", it)
        mutableStatus.value = DesktopTransferStatus(
          running = false,
          capabilities = capabilities,
          summary = noteLinkText("BLE 启动失败", "BLE failed to start"),
          detail = it.message.orEmpty(),
          transferLog = eventLog.entries.value,
        )
        return
      }
    mutableStatus.value = DesktopTransferStatus(
      true,
      capabilities,
      if (settings.pairing == null) {
        noteLinkText("生成配对码后在 Note 上输入", "Generate a pairing code and enter it on Note")
      } else if (counts.images + counts.texts + counts.pdfs > 0) {
        noteLinkText(
          "LAN 已就绪，正在等待 Note 获取",
          "LAN ready; waiting for Note",
        )
      } else {
        noteLinkText("LAN 已就绪，暂无待发送内容", "LAN ready; nothing queued")
      },
      transferLog = eventLog.entries.value,
    )
    pollJob = scope.launch {
      while (isActive) {
        runCatching { native.pollBleCommand(1_000)?.let(::processCommand) }
          .onFailure {
            if (isActive) {
              DesktopLog.error("transfer.ble.poll", it)
              mutableStatus.value = mutableStatus.value.copy(
                summary = noteLinkText("BLE 通信错误", "BLE communication error"),
                detail = it.message.orEmpty(),
              )
              delay(500)
            }
          }
      }
    }
  }

  @Synchronized fun stop() {
    pollJob?.cancel();
    pollJob = null
    cancel()
    leases.values.forEach { runCatching { it.release() } }
    leases.clear();
    startedLeases.clear()
    runCatching { native.stopBle() }
    mutableStatus.value = mutableStatus.value.copy(
      running = false,
      summary = noteLinkText("接收已暂停", "Receiving paused"),
      detail = "",
    )
  }

  @Synchronized fun restart() {
    stop();
    start()
  }

  fun refreshCounts() {
    if (!mutableStatus.value.running) return
    val counts = queue.counts()
    runCatching { native.updateBleCounts(counts.images, counts.texts, counts.pdfs) }
      .onFailure { mutableStatus.value = mutableStatus.value.copy(detail = it.message.orEmpty()) }
  }

  @Suppress("CyclomaticComplexMethod")
  private fun processCommand(envelope: ByteArray) {
    val senderId = WindowsSecureEnvelope.senderDeviceId(envelope)
    val authenticated = authenticate(envelope, senderId)
    var pairing = authenticated.pairing
    val provisional = pairing.deviceId.startsWith("pending:")
    var command: BleCommand? = null
    val response = runCatching {
      BleQueueProtocol.decodeCommand(authenticated.plaintext).also { command = it }.let {
        handle(it, pairing.deviceId, pairing.sharedKey)
      }.also {
        if (provisional) {
          settings.completeOwnerPairing(senderId ?: "betterhv-note")
          pairing = requireNotNull(settings.pairing)
          onPairingChanged()
        } else if (senderId != null && senderId != pairing.deviceId) {
          pairing = settings.resolveIdentity(pairing.deviceId, senderId)
          onPairingChanged()
        } else {
          settings.markLastUsed(pairing.deviceId)
        }
      }
    }.getOrElse { error ->
      val failure = when (error) {
        is com.betterhv.transfer.core.UnsupportedBleProtocolException -> TransferFailure(
          TransferErrorCode.UNSUPPORTED_PROTOCOL,
          error.message.orEmpty(),
          false,
        )

        is TransferChannelException -> error.failure

        else -> TransferFailure(TransferErrorCode.INTERNAL, error.message ?: "命令失败", true)
      }
      mutableSnapshot.value = mutableSnapshot.value.copy(
        phase = TransferPhase.FAILED,
        lastFailure = failure,
        canRetry = failure.recoverable,
        canCancel = false,
      )
      emitEvent(TransferEvent.Failed(mutableSnapshot.value.operationId, failure))
      BleResponse.Failure(failure)
    }
    val encoded = BleQueueProtocol.encode(response)
    native.respondBle(
      if (senderId == null) {
        WindowsSecureEnvelope.seal(pairing.sharedKey, encoded)
      } else {
        WindowsSecureEnvelope.seal(pairing.sharedKey, settings.localDeviceId, encoded)
      },
    )
    if (command is BleCommand.Commit || command is BleCommand.Release) {
      // Updating counts recreates the Windows GATT provider. Let the final response
      // leave the current connection before replacing its characteristic handles.
      scope.launch {
        delay(BLE_FINAL_RESPONSE_SETTLE_MILLIS)
        refreshCounts()
      }
    }
  }

  private fun authenticate(envelope: ByteArray, senderId: String?): AuthenticatedDesktopCommand {
    val stored = settings.pairings
    val candidates = buildList {
      val identified = senderId?.let { id -> stored.firstOrNull { it.deviceId == id } }
      if (senderId == null) {
        addAll(stored)
        val provisional = settings.pendingOwnerPairing ?: if (stored.isEmpty()) settings.ownerPairing else null
        provisional?.let(::add)
      } else if (identified != null) {
        add(identified)
        settings.pendingOwnerPairing?.let(::add)
      } else {
        settings.pendingOwnerPairing?.let(::add)
      }
    }
    candidates.forEach { pairing ->
      runCatching { WindowsSecureEnvelope.open(pairing.sharedKey, envelope, replay) }
        .getOrNull()?.let { return AuthenticatedDesktopCommand(pairing, it) }
    }
    error("无法验证已配对设备")
  }

  private fun handle(command: BleCommand, peerId: String, key: ByteArray): BleResponse = when (command) {
    BleCommand.Counts -> queue.counts(peerId).let { BleResponse.Counts(it.images, it.texts, it.pdfs) }

    is BleCommand.Lease -> queue.leaseNext(command.kind, peerId)?.let {
      leases[it.item.id] = it
      leaseOwners[it.item.id] = peerId
      BleResponse.Offer(it.item)
    } ?: BleResponse.Empty

    is BleCommand.TextChunk -> {
      val lease = requireLease(command.itemId, peerId)
      val content = requireNotNull(lease.text) { "不是文字项目" }.encodeToByteArray()
      require(command.offset in 0..content.size)
      val end = (command.offset + BleQueueProtocol.TEXT_CHUNK_BYTES).coerceAtMost(content.size)
      lease.heartbeat()
      BleResponse.TextChunk(command.itemId, command.offset, content.size, content.copyOfRange(command.offset, end))
    }

    is BleCommand.Heartbeat -> {
      requireLease(command.itemId, peerId).heartbeat();
      BleResponse.Ok
    }

    is BleCommand.Commit -> {
      val lease = requireLease(command.itemId, peerId)
      startedLeases.remove(command.itemId)
      leases.remove(command.itemId)
      leaseOwners.remove(command.itemId)
      lease.commit()
      complete(command.itemId);
      BleResponse.Ok
    }

    is BleCommand.Release -> {
      val lease = requireLease(command.itemId, peerId)
      transferJobs.remove(command.itemId)?.cancel();
      startedLeases.remove(command.itemId)
      leases.remove(command.itemId)
      leaseOwners.remove(command.itemId)
      lease.release();
      BleResponse.Ok
    }

    is BleCommand.Capabilities -> {
      peerCapabilities[peerId] = command.localCapabilities
      val local = localCapabilities(key)
      val match = TransferNetworkSecurity.compare(local.ssidFingerprint, command.localCapabilities.ssidFingerprint)
      mutableSnapshot.value = mutableSnapshot.value.copy(
        phase = TransferPhase.NEGOTIATING_CAPABILITIES,
        localModes = local.modes,
        remoteModes = command.localCapabilities.modes,
        ssidMatch = match,
      )
      emitEvent(TransferEvent.CapabilityNegotiated(local.modes, command.localCapabilities.modes, match))
      BleResponse.Capabilities(CapabilityNegotiation(local, match))
    }

    is BleCommand.PrepareFileTransfer -> prepare(command, peerId, key)

    is BleCommand.PushOffer -> when (val begin = beginPush(peerId, command)) {
      DesktopInboxBegin.AlreadyReceived -> BleResponse.AlreadyReceived(command.offer.artifactId)

      is DesktopInboxBegin.Receive -> {
        pushed[command.offer.artifactId] = PushState.Waiting(command.offer, begin.partial)
        BleResponse.Ok
      }
    }

    is BleCommand.PushStatus -> pushStatus(command.artifactId, peerId)

    is BleCommand.PushCancel -> cancelPush(command.artifactId, peerId)
  }

  private fun pushStatus(artifactId: UUID, peerId: String): BleResponse = when (val state = pushed[artifactId]) {
    null -> inbox.find(artifactId)?.takeIf {
      it.sourceDeviceId == peerId && it.state == DesktopInboxState.COMPLETE
    }
      ?.let { BleResponse.AlreadyReceived(artifactId) }
      ?: BleResponse.Failure(TransferFailure(TransferErrorCode.INTERNAL, "接收任务不存在", false))

    is PushState.Waiting, is PushState.Receiving -> {
      requirePushOwner(artifactId, peerId)
      BleResponse.Pending
    }

    PushState.Complete -> {
      requirePushOwner(artifactId, peerId)
      BleResponse.PushComplete(artifactId)
    }

    is PushState.Failed -> {
      requirePushOwner(artifactId, peerId)
      BleResponse.Failure(state.failure)
    }
  }

  private fun cancelPush(artifactId: UUID, peerId: String): BleResponse {
    requirePushOwner(artifactId, peerId)
    transferJobs.remove(artifactId)?.cancel()
    listeningSocket?.close()
    listeningSocket = null
    pushed.remove(artifactId)
    pushedOwners.remove(artifactId)
    inbox.find(artifactId)
      ?.takeIf { it.state != DesktopInboxState.COMPLETE }
      ?.let { inbox.delete(artifactId) }
    return BleResponse.Ok
  }

  private fun prepare(command: BleCommand.PrepareFileTransfer, peerId: String, pairingKey: ByteArray): BleResponse {
    if (command.selectedMode != TransferMode.LAN) {
      return BleResponse.Failure(
        TransferFailure(
          TransferErrorCode.UNSUPPORTED_MODE,
          "Windows NoteLink 仅支持 LAN",
          false,
          command.selectedMode,
        )
      )
    }
    val endpoint = runCatching { command.receiverEndpoint.validated() }.getOrElse {
      return BleResponse.Failure(TransferFailure(TransferErrorCode.INVALID_ENDPOINT, it.message.orEmpty(), false))
    }
    val previousReceiving = pushed[command.itemId] as? PushState.Receiving
    if (previousReceiving != null) receiveAttemptTokens[command.itemId] = Any()
    transferJobs.remove(command.itemId)?.cancel()
    listeningSocket?.close()
    listeningSocket = null
    previousReceiving?.let {
      pushed[command.itemId] = PushState.Waiting(it.offer, it.partial)
    }
    val fileKey = TransferNetworkSecurity.sessionKey(pairingKey, command.itemId, command.sessionNonce, "file")
    val probeKey = TransferNetworkSecurity.sessionKey(pairingKey, command.itemId, command.sessionNonce, "probe")
    return when {
      leases.containsKey(command.itemId) -> {
        requireLease(command.itemId, peerId)
        prepareSend(command.itemId, peerId, endpoint, fileKey, probeKey)
      }

      pushed[command.itemId] is PushState.Waiting -> {
        requirePushOwner(command.itemId, peerId)
        prepareReceive(command.itemId, peerId, endpoint, fileKey, probeKey)
      }

      else -> BleResponse.Failure(TransferFailure(TransferErrorCode.INTERNAL, "传输项目不存在", false))
    }
  }

  private fun prepareSend(
    id: UUID,
    peerId: String,
    endpoint: NetworkEndpoint,
    fileKey: ByteArray,
    probeKey: ByteArray,
  ): BleResponse {
    val lease = requireLease(id, peerId)
    val file = requireNotNull(lease.file) { "不是文件项目" }
    begin(id, peerId, file.length(), endpoint)
    runCatching { WindowsFileTransfer.probe(endpoint.host, id, probeKey) }.getOrElse {
      val failure = failure(it)
      fail(id, failure)
      return BleResponse.Failure(failure)
    }
    if (startedLeases.add(id)) lease.markTransferring()
    transferJobs[id] = scope.launch {
      try {
        val meter = TransferProgressMeter(System.currentTimeMillis())
        WindowsFileTransfer.send(endpoint.host, id, file, fileKey) { done, total -> progress(id, done, total, meter) }
        lease.markAwaitingCommit();
        phase(id, TransferPhase.AWAITING_COMMIT)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        fail(id, failure(error))
      } finally {
        transferJobs.remove(id)
      }
    }
    return BleResponse.Prepared(id, TransferMode.LAN, endpoint)
  }

  private fun prepareReceive(
    id: UUID,
    peerId: String,
    endpoint: NetworkEndpoint,
    fileKey: ByteArray,
    probeKey: ByteArray,
  ): BleResponse {
    val waiting = pushed[id] as PushState.Waiting
    val attemptToken = Any().also { receiveAttemptTokens[id] = it }
    val listening = CountDownLatch(1)
    begin(id, peerId, waiting.offer.byteLength, endpoint)
    val receiving = PushState.Receiving(waiting.offer, waiting.partial)
    pushed[id] = receiving
    transferJobs[id] = scope.launch {
      try {
        val meter = TransferProgressMeter(System.currentTimeMillis())
        val received = WindowsFileTransfer.receive(
          waiting.partial,
          id,
          fileKey,
          probeKey,
          progress = { done, total -> progress(id, done, total, meter) },
          onListening = {
            listeningSocket = it;
            listening.countDown()
          },
        )
        if (receiveAttemptTokens[id] !== attemptToken) return@launch
        phase(id, TransferPhase.VERIFYING)
        require(received.byteLength == waiting.offer.byteLength && received.sha256.contentEquals(waiting.offer.sha256))
        inbox.complete(id, waiting.partial);
        pushed[id] = PushState.Complete;
        complete(id)
      } catch (cancelled: CancellationException) {
        if (receiveAttemptTokens[id] === attemptToken) {
          pushed.remove(id, receiving)
          pushedOwners.remove(id)
        }
        throw cancelled
      } catch (error: Throwable) {
        if (receiveAttemptTokens[id] === attemptToken) {
          val failure = failure(error)
          inbox.fail(id, failure.message)
          pushed[id] = PushState.Failed(failure)
          fail(id, failure)
        }
      } finally {
        if (receiveAttemptTokens.remove(id, attemptToken)) {
          listeningSocket = null
          transferJobs.remove(id)
        }
        listening.countDown()
      }
    }
    if (!listening.await(2, TimeUnit.SECONDS)) {
      transferJobs.remove(id)?.cancel()
      return BleResponse.Failure(TransferFailure(TransferErrorCode.CONNECTION_TIMEOUT, "接收端口未能及时启动", true))
    }
    return BleResponse.Prepared(id, TransferMode.LAN, endpoint)
  }

  private fun localCapabilities(key: ByteArray): DeviceCapabilities {
    val lan = native.lanInfo()
    return DeviceCapabilities(
      modes = TransferModes.LAN,
      lanEndpoint = NetworkEndpoint(lan.ipv4),
      ssidFingerprint = TransferNetworkSecurity.ssidFingerprint(key, lan.ssid),
      extensions = BleQueueProtocol.CAPABILITY_EXPORT_PUSH,
    )
  }

  private fun begin(id: UUID, peerId: String, total: Long, endpoint: NetworkEndpoint) {
    val now = System.currentTimeMillis()
    mutableSnapshot.value = TransferSnapshot(
      operationId = id, itemId = id, deviceId = peerId,
      phase = TransferPhase.WAITING_FOR_PEER, mode = TransferMode.LAN,
      localModes = TransferModes.LAN, remoteModes = mutableSnapshot.value.remoteModes,
      ssidMatch = mutableSnapshot.value.ssidMatch, endpoint = endpoint,
      totalBytes = total, attempt = 1, startedAtMillis = now, phaseStartedAtMillis = now, canCancel = true,
    )
    emitEvent(TransferEvent.TransportSelected(id, TransferMode.LAN, endpoint))
  }

  private fun progress(id: UUID, done: Long, total: Long, meter: TransferProgressMeter) {
    val sample = meter.sample(done, total)
    mutableSnapshot.value = mutableSnapshot.value.copy(
      phase = TransferPhase.TRANSFERRING,
      bytesTransferred = done,
      totalBytes = total,
      bytesPerSecond = sample.bytesPerSecond,
      averageBytesPerSecond = sample.averageBytesPerSecond,
      etaMillis = sample.etaMillis,
    )
    emitEvent(TransferEvent.Progress(id, done, total, sample.bytesPerSecond, sample.etaMillis))
  }

  private fun phase(id: UUID, next: TransferPhase) {
    val previous = mutableSnapshot.value.phase
    mutableSnapshot.value = mutableSnapshot.value.copy(phase = next, phaseStartedAtMillis = System.currentTimeMillis())
    emitEvent(TransferEvent.PhaseChanged(id, previous, next))
  }

  private fun complete(id: UUID) {
    phase(id, TransferPhase.COMPLETE);
    emitEvent(TransferEvent.Completed(id, id))
  }

  private fun failure(error: Throwable): TransferFailure = (error as? TransferChannelException)?.failure
    ?: TransferFailure(TransferErrorCode.INTERNAL, error.message ?: "LAN 传输失败", true, TransferMode.LAN)

  private fun fail(id: UUID, failure: TransferFailure) {
    mutableSnapshot.value = mutableSnapshot.value.copy(
      phase = TransferPhase.FAILED,
      lastFailure = failure,
      canRetry = failure.recoverable,
      canCancel = false,
    )
    emitEvent(TransferEvent.Failed(id, failure))
    mutableStatus.value = mutableStatus.value.copy(summary = "LAN 传输失败", detail = failure.message)
  }

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

  private companion object {
    const val BLE_FINAL_RESPONSE_SETTLE_MILLIS = 500L
  }

  private fun requireLease(id: UUID, peerId: String): DesktopSenderLease {
    require(leaseOwners[id] == peerId) { "租约不属于当前设备" }
    return requireNotNull(leases[id]) { "租约不存在" }
  }

  private fun beginPush(peerId: String, command: BleCommand.PushOffer): DesktopInboxBegin {
    val id = command.offer.artifactId
    val storedOwner = inbox.find(id)?.sourceDeviceId
    require(storedOwner == null || storedOwner == peerId) { "导出任务不属于当前设备" }
    val result = inbox.begin(peerId, command.offer)
    if (result is DesktopInboxBegin.Receive) {
      val activeOwner = pushedOwners.putIfAbsent(id, peerId)
      require(activeOwner == null || activeOwner == peerId) { "导出任务不属于当前设备" }
    }
    return result
  }

  private fun requirePushOwner(id: UUID, peerId: String) {
    require(pushedOwners[id] == peerId) { "导出任务不属于当前设备" }
  }

  @Synchronized override fun cancel() {
    listeningSocket?.close();
    listeningSocket = null
    transferJobs.values.forEach(Job::cancel);
    transferJobs.clear()
    val current = snapshot.value
    val operationId = current.operationId
    if (operationId != null && current.canCancel) {
      val failure = TransferFailure(TransferErrorCode.CANCELLED, "传输已取消", true, current.mode)
      fail(operationId, failure)
    }
  }

  override fun close() {
    stop();
    scope.cancel();
    native.close()
  }

  private sealed interface PushState {
    data class Waiting(val offer: com.betterhv.transfer.core.ExportTransferOffer, val partial: File) : PushState
    data class Receiving(val offer: com.betterhv.transfer.core.ExportTransferOffer, val partial: File) : PushState
    data object Complete : PushState
    data class Failed(val failure: TransferFailure) : PushState
  }

  private data class AuthenticatedDesktopCommand(val pairing: DesktopPairing, val plaintext: ByteArray)

  private data class WindowsVersion(val supported: Boolean, val message: String)
  private fun windowsVersion(): WindowsVersion {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
      return WindowsVersion(false, "仅支持 Windows 10 1903+ 和 Windows 11 x64")
    }
    val architecture = System.getProperty("os.arch")
    if (architecture != "amd64" && architecture != "x86_64") return WindowsVersion(false, "仅支持 x64")
    val version = System.getProperty("os.version").split('.').mapNotNull(String::toIntOrNull)
    return if ((version.firstOrNull() ?: 0) < 10) {
      WindowsVersion(false, "需要 Windows 10 1903 或更高版本")
    } else {
      WindowsVersion(true, "")
    }
  }
}
