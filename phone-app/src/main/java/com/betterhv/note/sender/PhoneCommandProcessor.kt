package com.betterhv.note.sender

import android.content.Context
import android.util.Log
import com.betterhv.transfer.android.AndroidNetworkInfoProvider
import com.betterhv.transfer.android.AndroidPairingController
import com.betterhv.transfer.android.HighBandwidthSessionManager
import com.betterhv.transfer.android.SenderLease
import com.betterhv.transfer.core.ContentCounts
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.ExportTransferOffer
import com.betterhv.transfer.core.NOTELINK_LAN_PORT
import com.betterhv.transfer.core.TransferErrorCode
import com.betterhv.transfer.core.TransferEvent
import com.betterhv.transfer.core.TransferFailure
import com.betterhv.transfer.core.TransferLimits
import com.betterhv.transfer.core.TransferMode
import com.betterhv.transfer.core.TransferObservable
import com.betterhv.transfer.core.TransferPhase
import com.betterhv.transfer.core.TransferProgressMeter
import com.betterhv.transfer.core.TransferSnapshot
import com.betterhv.transfer.core.v4.notelink.ByteSink
import com.betterhv.transfer.core.v4.notelink.FileByteSink
import com.betterhv.transfer.core.v4.notelink.HostLease
import com.betterhv.transfer.core.v4.notelink.NoteLinkHost
import com.betterhv.transfer.core.v4.notelink.NoteLinkProto
import com.betterhv.transfer.core.v4.notelink.NoteLinkServer
import com.betterhv.transfer.core.v4.notelink.NoteLinkTransferEvent
import com.betterhv.transfer.core.v4.notelink.PayloadSource
import com.betterhv.transfer.core.v4.notelink.PushDecision
import com.betterhv.transfer.core.v4.pairing.PairingOutcome
import com.betterhv.transfer.core.v4.pairing.PairingResponder
import com.betterhv.transfer.core.v4.session.PairingKeyStore
import com.betterhv.transfer.proto.v4.CapabilitySet
import com.betterhv.transfer.proto.v4.Endpoint
import com.betterhv.transfer.proto.v4.Feature
import com.betterhv.transfer.proto.v4.P2pGroup
import com.betterhv.transfer.proto.v4.P2pState
import com.betterhv.transfer.proto.v4.PushOffer
import com.betterhv.transfer.proto.v4.UpdateCheck
import com.betterhv.transfer.proto.v4.UpdateResult
import com.betterhv.transfer.proto.v4.UpdateState
import com.betterhv.update.NoteAppUpdateCoordinator
import com.betterhv.update.NoteAppUpdateTaskState
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.betterhv.transfer.proto.v4.ContentKind as ProtoContentKind

/**
 * Phone side of NoteLink v4: the [NoteLinkHost] that maps protocol requests onto the outbox
 * queue, the export inbox and the Note update cache. Transport (BLE peripheral, TCP listener)
 * lives in [NoteLinkServer]; this class only answers its callbacks.
 */
class PhoneCommandProcessor(
  context: Context,
  private val queue: PhoneQueueRepository,
  private val inbox: ExportInboxRepository,
  private val pairing: AndroidPairingController,
  private val highBandwidth: HighBandwidthSessionManager,
  private val onQueueChanged: () -> Unit,
) : AutoCloseable,
  TransferObservable,
  NoteLinkHost {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val network = AndroidNetworkInfoProvider(appContext)
  private val leases = ConcurrentHashMap<UUID, Pair<String, SenderLease>>()
  private val meters = ConcurrentHashMap<UUID, TransferProgressMeter>()
  private val updateOperations = ConcurrentHashMap<String, UUID>()
  private val mutableSnapshot = MutableStateFlow(TransferSnapshot())
  private val mutableEvents = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)
  private val noteUpdate = NoteAppUpdateCoordinator(
    scope,
    File(appContext.filesDir, "note-update-cache"),
  ) { file, descriptor, peerId ->
    val item = queue.enqueueAppPackage(file, descriptor, peerId)
    onQueueChanged()
    item.id
  }
  val noteUpdateState = noteUpdate.state
  override val snapshot: StateFlow<TransferSnapshot> = mutableSnapshot.asStateFlow()
  override val events: SharedFlow<TransferEvent> = mutableEvents.asSharedFlow()

  @Volatile var server: NoteLinkServer? = null

  override val localDeviceId: String get() = pairing.localDeviceId
  override val localDeviceName: String get() = NoteLinkSettings(appContext).displayName

  override fun capabilities(): CapabilitySet {
    val addresses = network.lanAddresses()
    val port = server?.lanBoundPort?.takeIf { it > 0 } ?: NOTELINK_LAN_PORT
    val builder = CapabilitySet.newBuilder()
      .setTransports(TRANSPORT_LAN or TRANSPORT_P2P)
      .setMaxRecordBytes(64 * 1024)
      .addAllKinds(
        listOf(
          ProtoContentKind.CONTENT_IMAGE,
          ProtoContentKind.CONTENT_TEXT,
          ProtoContentKind.CONTENT_PDF,
          ProtoContentKind.CONTENT_APP_PACKAGE,
        ),
      )
      .setFeatures(Feature.FEATURE_LAN_TICKET_VALUE or Feature.FEATURE_P2P_CREDENTIAL_JOIN_VALUE)
      .setDeviceName(localDeviceName)
      .setAppVersion(BuildConfig.VERSION_NAME)
    addresses.firstOrNull()?.let { builder.setLan(Endpoint.newBuilder().setHost(it).setPort(port)) }
    addresses.forEach { builder.addLanCandidates(Endpoint.newBuilder().setHost(it).setPort(port)) }
    return builder.build()
  }

  override fun pairings(): PairingKeyStore = pairing.pairingKeyStore()

  override fun pairingResponder(): PairingResponder? = PhonePairingMode.active()

  override fun onPaired(outcome: PairingOutcome) {
    val firstPairing = pairing.pairedClients.isEmpty()
    val stored = pairing.store(outcome)
    // Items shared before the first pairing had no destination; they belong to this Note.
    if (firstPairing) queue.reassignPending(stored.id)
    // Items for a Note that was since unpaired would otherwise be invisible to every Note.
    queue.adoptOrphans(pairing.pairedClients.map { it.id }.toSet(), stored.id)
    PhonePairingMode.paired(stored.name)
    ShareShortcutPublisher.update(appContext, stored)
    onQueueChanged()
  }

  override fun onPairingFailed(attemptsLeft: Int) = PhonePairingMode.failedAttempt(attemptsLeft)

  override fun advertisedCounts(): ContentCounts = queue.counts()

  override fun counts(peerDeviceId: String): ContentCounts {
    queue.releaseExpired()
    return queue.counts(peerDeviceId)
  }

  override fun lease(peerDeviceId: String, kind: ContentKind): HostLease? {
    val lease = queue.leaseNext(kind, peerDeviceId) ?: return null
    val item = lease.offer.item
    leases[item.id] = peerDeviceId to lease
    runCatching { lease.markTransferring() }
    return hostLease(lease)
  }

  private fun hostLease(lease: SenderLease): HostLease {
    val item = lease.offer.item
    val text = lease.text?.encodeToByteArray()
    val inline = text?.takeIf { it.size <= NoteLinkProto.INLINE_TEXT_BYTES }
    val payload = when {
      text != null -> PayloadSource.of(text)
      else -> lease.payloadFile?.takeIf(File::isFile)?.let { PayloadSource.of(it, item.sha256) }
    }
    val offer = NoteLinkProto.offer(
      id = item.id,
      kind = item.kind,
      mimeType = item.mimeType,
      byteLength = payload?.length ?: item.byteLength,
      sha256 = payload?.sha256 ?: item.sha256,
      displayName = item.displayName,
      leaseTtlMillis = TransferLimits.LEASE_TIMEOUT_MILLIS,
      inlineData = inline,
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
    if (owner != peerDeviceId) return false
    return runCatching { lease.heartbeat() }.isSuccess
  }

  override fun commit(peerDeviceId: String, itemId: UUID): Boolean {
    val entry = leases[itemId] ?: return false
    if (entry.first != peerDeviceId) return false
    leases.remove(itemId)
    return runCatching { entry.second.commit() }
      .onSuccess {
        pairing.markLastUsed(peerDeviceId)
        onQueueChanged()
        emit(TransferEvent.Completed(itemId, itemId))
      }
      .isSuccess
  }

  override fun release(peerDeviceId: String, itemId: UUID): Boolean {
    val entry = leases[itemId] ?: return false
    if (entry.first != peerDeviceId) return false
    leases.remove(itemId)
    return runCatching { entry.second.release() }.onSuccess { onQueueChanged() }.isSuccess
  }

  override fun push(peerDeviceId: String, offer: PushOffer): PushDecision {
    val id = NoteLinkProto.uuid(offer.itemId)
    val export = runCatching {
      ExportTransferOffer(id, offer.displayName, offer.mimeType, offer.byteLength, offer.sha256.toByteArray())
    }.getOrElse { return PushDecision.Reject(it.message ?: "Invalid export") }
    return when (val begin = inbox.begin(peerDeviceId, export)) {
      InboxBeginResult.AlreadyReceived -> PushDecision.AlreadyReceived
      is InboxBeginResult.Receive -> {
        val file = FileByteSink(begin.partialFile)
        val sink = object : ByteSink by file {
          override fun abort() {
            file.abort()
            inbox.fail(id, "Transfer interrupted")
          }
        }
        PushDecision.Receive(sink) { inbox.complete(id, begin.partialFile) }
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
        leases[lease.offer.item.id] = peerDeviceId to lease
        result.setState(UpdateState.UPDATE_AVAILABLE)
          .setLatestVersion(state.version)
          .setOffer(hostLease(lease).offer)
      }
    }
    return result.build()
  }

  override suspend fun prepareP2p(peerDeviceId: String): P2pGroup? {
    val key = pairing.sharedKey(peerDeviceId) ?: return null
    highBandwidth.start(key)
    val endpoint = highBandwidth.awaitReady(P2P_READY_MILLIS) ?: return null
    return P2pGroup.newBuilder()
      .setSsid(endpoint.networkName)
      .setPassphrase(endpoint.passphrase)
      .setGroupOwnerIp(ByteString.copyFrom(java.net.InetAddress.getByName(endpoint.ownerIp).address))
      .setPort(server?.lanBoundPort?.takeIf { it > 0 } ?: NOTELINK_LAN_PORT)
      .setState(P2pState.P2P_STATE_READY)
      .build()
  }

  override fun onSessionChanged(peerDeviceId: String, links: List<String>) {
    Log.i(TAG, "session peer=$peerDeviceId links=$links")
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
          mode =
          if (event.link == "ble") null else if (event.link == "p2p") TransferMode.WIFI_DIRECT else TransferMode.LAN,
          totalBytes = event.totalBytes,
          startedAtMillis = now,
          phaseStartedAtMillis = now,
          canCancel = false,
        )
        Log.i(TAG, "transfer start item=${event.itemId} link=${event.link} bytes=${event.totalBytes}")
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
        emit(
          TransferEvent.Progress(event.itemId, event.bytes, event.totalBytes, sample.bytesPerSecond, sample.etaMillis)
        )
      }
      is NoteLinkTransferEvent.Finished -> {
        meters.remove(event.itemId)
        mutableSnapshot.value = mutableSnapshot.value.copy(
          phase = TransferPhase.COMPLETE,
          bytesTransferred = mutableSnapshot.value.totalBytes,
          phaseStartedAtMillis = System.currentTimeMillis(),
        )
        Log.i(TAG, "transfer done item=${event.itemId} bytes=${event.bytes} in ${event.millis} ms")
      }
      is NoteLinkTransferEvent.Failed -> {
        meters.remove(event.itemId)
        val failure = TransferFailure(TransferErrorCode.INTERNAL, event.message, true)
        mutableSnapshot.value = mutableSnapshot.value.copy(
          phase = TransferPhase.FAILED,
          lastFailure = failure,
          canRetry = true,
        )
        emit(TransferEvent.Failed(event.itemId, failure))
      }
    }
  }

  override fun log(message: String) {
    Log.i(TAG, message)
  }

  private fun emit(event: TransferEvent) {
    if (!mutableEvents.tryEmit(event)) Log.w(TAG, "Transfer event buffer full: ${event.javaClass.simpleName}")
  }

  override fun cancel() {
    mutableSnapshot.value = TransferSnapshot()
  }

  override fun close() {
    noteUpdate.close()
    leases.values.forEach { (_, lease) -> runCatching { lease.release() } }
    leases.clear()
    scope.cancel()
  }

  private companion object {
    const val TAG = "NoteLinkHost"
    const val TRANSPORT_LAN = 1
    const val TRANSPORT_P2P = 2
    const val P2P_READY_MILLIS = 40_000L
  }
}
