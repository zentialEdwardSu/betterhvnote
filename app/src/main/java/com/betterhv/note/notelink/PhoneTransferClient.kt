package com.betterhv.note.notelink

import android.content.Context
import android.util.Log
import com.betterhv.note.export.ExportArtifact
import com.betterhv.transfer.android.AndroidPairingController
import com.betterhv.transfer.android.BleProtocolClient
import com.betterhv.transfer.android.BleReceiverScanner
import com.betterhv.transfer.android.ReceivedLease
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.PairedDevice
import com.betterhv.transfer.core.QueueItem
import com.betterhv.transfer.core.RemotePayload
import com.betterhv.transfer.core.TransferChannelException
import com.betterhv.transfer.core.TransferErrorCode
import com.betterhv.transfer.core.TransferEvent
import com.betterhv.transfer.core.TransferEventLog
import com.betterhv.transfer.core.TransferFailure
import com.betterhv.transfer.core.TransferLogEntry
import com.betterhv.transfer.core.TransferMode
import com.betterhv.transfer.core.TransferObservable
import com.betterhv.transfer.core.TransferOffer
import com.betterhv.transfer.core.TransferPhase
import com.betterhv.transfer.core.TransferProgressMeter
import com.betterhv.transfer.core.TransferSnapshot
import com.betterhv.transfer.core.v4.discovery.PeerAddressBook
import com.betterhv.transfer.core.v4.lan.LanDiscovery
import com.betterhv.transfer.core.v4.lan.TcpAttach
import com.betterhv.transfer.core.v4.notelink.FetchedItem
import com.betterhv.transfer.core.v4.notelink.FileByteSink
import com.betterhv.transfer.core.v4.notelink.InterruptedFetchException
import com.betterhv.transfer.core.v4.notelink.NoteLinkClient
import com.betterhv.transfer.core.v4.notelink.NoteLinkProto
import com.betterhv.transfer.core.v4.notelink.NoteLinkSession
import com.betterhv.transfer.core.v4.pairing.PairingCode
import com.betterhv.transfer.core.v4.pairing.PairingException
import com.betterhv.transfer.core.v4.pairing.PairingInitiator
import com.betterhv.transfer.core.v4.session.ExchangeException
import com.betterhv.transfer.proto.v4.LeaseKind
import com.betterhv.transfer.proto.v4.Offer
import com.betterhv.transfer.proto.v4.PushOffer
import com.betterhv.transfer.proto.v4.UpdateCheck
import com.betterhv.transfer.proto.v4.UpdateState
import com.google.protobuf.ByteString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.betterhv.note.BuildConfig
import com.betterhv.note.EventLog

/**
 * Note-side NoteLink facade used by the UI. Sessions are kept warm by [NoteLinkConnections];
 * every operation here runs on an already-authenticated session and only pays for its own
 * round trips and payload bytes.
 */
class PhoneTransferClient(context: Context) :
  AutoCloseable,
  TransferObservable {
  private val appContext = context.applicationContext
  val connections = NoteLinkConnections.get(appContext)
  val pairing: AndroidPairingController get() = connections.pairing
  private val addressBook: PeerAddressBook get() = connections.addressBook
  private val scanGate = ScanGate(BleReceiverScanner(appContext))
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutableSnapshot = MutableStateFlow(TransferSnapshot())
  private val mutableEvents = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)
  private val eventLog = TransferEventLog()
  private val operation = Mutex()
  @Volatile private var activeJob: Job? = null
  override val snapshot: StateFlow<TransferSnapshot> = mutableSnapshot.asStateFlow()
  override val events: SharedFlow<TransferEvent> = mutableEvents.asSharedFlow()
  val eventHistory: StateFlow<List<TransferLogEntry>> = eventLog.entries

  data class AvailableNoteLink(
    val client: PairedDevice,
    val advertisedName: String,
    val imageCount: Int,
    val textCount: Int,
    val pdfCount: Int,
    /** Control-plane transport, e.g. "LAN · UDP discovery"; null when unknown. */
    val mode: String? = null,
  ) {
    /** "FLOWX13SU - LAN · UDP discovery" */
    val label: String get() = mode?.let { "${client.name} - $it" } ?: client.name
  }

  /** A NoteLink that is advertising pairing mode (the user typed the code on it). */
  data class PairingCandidate(
    val deviceId: String,
    val name: String,
    val identityHash: String,
    val bluetoothAddress: String,
  )

  sealed interface NoteAppUpdateProgress {
    data object Resolving : NoteAppUpdateProgress
    data class Downloading(val version: String, val bytesDownloaded: Long, val totalBytes: Long) :
      NoteAppUpdateProgress
    data class Transferring(val version: String, val bytes: Long = 0, val totalBytes: Long = 0) :
      NoteAppUpdateProgress
  }

  sealed interface NoteAppUpdateOutcome {
    data class UpToDate(val latestVersion: String?) : NoteAppUpdateOutcome
    data class Package(val version: String, val lease: ReceivedLease) : NoteAppUpdateOutcome
  }

  val pairedClients: List<PairedDevice> get() = pairing.pairedClients

  /** Starts the warm links; call when the Note UI becomes visible. */
  fun startLinks() = connections.start()

  /** Paired NoteLinks that are connected right now, with their announced queue counts. */
  fun onlineNoteLinks(kind: ContentKind? = null): List<AvailableNoteLink> {
    val states = connections.states.value
    return pairing.pairedClients.mapNotNull { client ->
      val state = states[client.id]?.takeIf { it.online } ?: return@mapNotNull null
      val counts = state.counts ?: com.betterhv.transfer.core.ContentCounts(0, 0)
      val has = when (kind) {
        null, ContentKind.APP_PACKAGE -> true
        else -> counts.forKind(kind) > 0
      }
      if (!has) return@mapNotNull null
      AvailableNoteLink(client, client.name, counts.images, counts.texts, counts.pdfs, state.modeLabel)
    }.sortedByDescending { it.client.lastUsedAt }
  }

  /**
   * NoteLinks that can serve [kind]. Waits until every paired NoteLink has either connected and
   * announced its queue counts or failed, so a phone and a PC that both have content are both
   * offered instead of whichever answered first. Bounded by [timeoutMillis].
   */
  suspend fun discoverAvailable(
    kind: ContentKind? = null,
    timeoutMillis: Long = 1_500L,
    onUpdate: (List<AvailableNoteLink>) -> Unit = {},
  ): List<AvailableNoteLink> = withContext(Dispatchers.IO) {
    val paired = pairing.pairedClients.filterNot { it.legacy }.map { it.id }
    if (paired.isEmpty()) return@withContext emptyList()
    connections.start()
    kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
      connections.states.first { states -> paired.all { id -> states[id]?.settled == true } }
    }
    onlineNoteLinks(kind).also(onUpdate)
  }

  suspend fun hasAvailable(kind: ContentKind): Boolean = discoverAvailable(kind).isNotEmpty()

  /** Scans for NoteLinks in pairing mode, i.e. where the user entered this Note's code. */
  suspend fun discoverPairingCandidates(timeoutMillis: Long = 8_000L): List<PairingCandidate> =
    withContext(Dispatchers.IO) {
      scanGate.discover(timeoutMillis).filter { it.pairing }.map { sender ->
        PairingCandidate(sender.identityHash, sender.name, sender.identityHash, sender.bluetoothAddress)
      }.distinctBy(PairingCandidate::bluetoothAddress).sortedBy(PairingCandidate::name)
    }

  /** A fresh six-digit code for the pairing screen; the NoteLink user types it in. */
  fun newPairingCode(): String = PairingCode.generate()

  /**
   * Pairs with the first NoteLink that advertises pairing mode and accepts [code]. The SRP run
   * yields the peer's full identity and the PSK, so no placeholder identity is ever stored.
   */
  suspend fun pair(
    code: String,
    timeoutMillis: Long = PAIRING_SCAN_MILLIS,
  ): PairedDevice = withContext(Dispatchers.IO) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    var lastError: Throwable? = null
    val tried = HashSet<String>()
    while (System.currentTimeMillis() < deadline) {
      // LAN discovery answers in milliseconds and works for a NoteLink without Bluetooth; BLE
      // covers phones on another network. Whichever finds a host in pairing mode first wins.
      val candidate = findPairingHost((deadline - System.currentTimeMillis()).coerceAtLeast(1_000L), tried) ?: break
      tried += candidate.key
      try {
        return@withContext candidate.pair(code)
      } catch (error: PairingException) {
        // A wrong code on this NoteLink is final for it; another one may be the intended peer.
        lastError = error
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        lastError = error
      }
    }
    throw IllegalStateException(
      lastError?.message ?: "No NoteLink in pairing mode was found. Enter the code on NoteLink first.",
      lastError,
    )
  }

  private inner class PairingHost(val key: String, val pair: suspend (String) -> PairedDevice)

  private suspend fun findPairingHost(timeoutMillis: Long, tried: Set<String>): PairingHost? = coroutineScope {
    val lan = async {
      LanDiscovery.find(timeoutMillis) { it.pairing && "lan:${it.host}" !in tried }?.let { peer ->
        PairingHost("lan:${peer.host}") { code -> pairOverLan(peer.host, peer.tcpPort, code) }
      }
    }
    val ble = async {
      if (!bluetoothOn()) return@async null
      runCatching {
        scanGate.find(timeoutMillis) { it.pairing && "ble:${it.bluetoothAddress}" !in tried }
      }.getOrNull()?.let { sender ->
        PairingHost("ble:${sender.bluetoothAddress}") { code -> pairWith(sender.bluetoothAddress, code) }
      }
    }
    val winner = select {
      lan.onAwait { it ?: ble.await() }
      ble.onAwait { it ?: lan.await() }
    }
    lan.cancel()
    ble.cancel()
    winner
  }

  private suspend fun pairOverLan(host: String, port: Int, code: String): PairedDevice {
    val started = System.nanoTime()
    val outcome = TcpAttach.dialControl(host, port).use { link ->
      NoteLinkClient.pair(link, PairingInitiator(code, pairing.localDeviceId, android.os.Build.MODEL ?: "Note"))
    }
    val stored = pairing.store(outcome)
    EventLog.log(
      TAG,
      "paired ${stored.name} over LAN $host id=${stored.id} in ${(System.nanoTime() - started) / 1_000_000} ms",
    )
    connections.start()
    return stored
  }

  private fun bluetoothOn(): Boolean =
    appContext.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true

  /** Pairs with a NoteLink chosen in the list. */
  suspend fun pair(candidate: PairingCandidate, code: String): PairedDevice = withContext(Dispatchers.IO) {
    pairWith(candidate.bluetoothAddress, code)
  }

  private suspend fun pairWith(address: String, code: String): PairedDevice {
    val started = System.nanoTime()
    val link = BleProtocolClient.connect(appContext, address)
    val outcome = try {
      NoteLinkClient.pair(link, PairingInitiator(code, pairing.localDeviceId, android.os.Build.MODEL ?: "Note"))
    } finally {
      link.close()
    }
    val stored = pairing.store(outcome)
    addressBook.recordSuccess(stored.id, address, com.betterhv.transfer.core.v4.discovery.PeerProtocol.V4)
    EventLog.log(TAG, "paired ${stored.name} id=${stored.id} in ${(System.nanoTime() - started) / 1_000_000} ms")
    connections.start()
    return stored
  }

  fun unpair(deviceId: String) {
    connections.forget(deviceId)
    pairing.unpair(deviceId)
  }

  suspend fun requestNext(kind: ContentKind): ReceivedLease? {
    val paired = pairing.pairedDevice ?: error("Pair with NoteLink in Settings first")
    return requestNext(paired.id, kind)
  }

  /**
   * Leases and downloads the next [kind] item from [clientId]. The returned lease is committed
   * by the caller once the content was placed, or released when the user cancels.
   */
  suspend fun requestNext(clientId: String, kind: ContentKind): ReceivedLease? = exclusive {
    val paired = pairing.pairedClient(clientId) ?: error("Paired NoteLink does not exist")
    val tap = System.nanoTime()
    val session = connections.await(clientId, SESSION_WAIT_MILLIS)
      ?: throw failure(TransferErrorCode.CONNECTION_TIMEOUT, "${paired.name} is not reachable")
    if (kind != ContentKind.TEXT) {
      // Large payloads over BLE take seconds; Wi-Fi Direct is used only when LAN cannot attach.
      connections.ensureP2p(session)
    }
    val operationId = UUID.randomUUID()
    start(operationId, clientId, kind)
    val part = File(appContext.filesDir, "documents/incoming/remote-$operationId.part").apply { parentFile?.mkdirs() }
    val meter = TransferProgressMeter(System.currentTimeMillis())
    val fetched: FetchedItem = try {
      fetchWithResume(clientId, session, kind, part, operationId, meter)
        ?: run {
          phase(TransferPhase.COMPLETE)
          return@exclusive null
        }
    } catch (error: Throwable) {
      part.delete()
      fail(error)
      throw error
    }
    val itemId = NoteLinkProto.uuid(fetched.offer.itemId)
    val item = queueItem(fetched.offer, itemId, clientId, kind)
    val payload = when (kind) {
      ContentKind.TEXT -> RemotePayload.Text(
        item,
        (fetched.inline ?: part.readBytes().also { part.delete() }).decodeToString(),
      )
      ContentKind.IMAGE -> RemotePayload.Image(item, part)
      ContentKind.PDF -> RemotePayload.Pdf(item, part)
      ContentKind.APP_PACKAGE -> RemotePayload.AppPackage(item, part)
    }
    val timing = fetched.timing
    Log.i(
      TIMING_TAG,
      "insert kind=$kind bytes=${timing.bytes} link=${timing.link} lease=${timing.leasedMillis}ms " +
        "first_byte=${timing.firstByteMillis}ms last_byte=${timing.lastByteMillis}ms " +
        "tap_to_payload=${(System.nanoTime() - tap) / 1_000_000}ms",
    )
    complete(operationId, itemId)
    SessionLease(session, payload, TransferOffer(item), scope)
  }

  /**
   * Fetches the next item. If the link drops mid-stream (the phone's Wi-Fi went to sleep, the
   * LAN link was reset), the leased item is re-requested once on a fresh session instead of
   * failing the insert; the lease is still held for this Note, so no other item is taken.
   */
  private suspend fun fetchWithResume(
    clientId: String,
    first: NoteLinkSession,
    kind: ContentKind,
    part: File,
    operationId: UUID,
    meter: TransferProgressMeter,
  ): FetchedItem? {
    val onProgress = { done: Long, total: Long -> progress(operationId, done, total, meter) }
    val interrupted = try {
      return NoteLinkClient.fetch(first, kind, sink = { FileByteSink(part) }, onProgress = onProgress)
    } catch (error: InterruptedFetchException) {
      error
    }
    Log.i(TAG, "resuming ${NoteLinkProto.uuid(interrupted.offer.itemId)} after: ${interrupted.cause?.message}")
    val retry = connections.await(clientId, SESSION_WAIT_MILLIS) ?: throw interrupted
    if (kind != ContentKind.TEXT) connections.ensureP2p(retry)
    val started = System.nanoTime()
    val timing = NoteLinkClient.download(retry, interrupted.offer, FileByteSink(part), onProgress)
    Log.i(TAG, "resume finished in ${(System.nanoTime() - started) / 1_000_000} ms over ${timing.link}")
    return FetchedItem(interrupted.offer, null, timing)
  }

  /** Pushes one export artifact to [clientId]'s inbox. */
  suspend fun sendExport(
    clientId: String,
    artifact: ExportArtifact,
    progress: (Long, Long) -> Unit = { _, _ -> },
  ) = exclusive {
    val paired = pairing.pairedClient(clientId) ?: error("Paired NoteLink does not exist")
    val session = connections.await(clientId, SESSION_WAIT_MILLIS)
      ?: throw failure(TransferErrorCode.CONNECTION_TIMEOUT, "${paired.name} is not reachable")
    connections.ensureP2p(session)
    start(artifact.id, clientId, null, artifact.byteLength)
    val meter = TransferProgressMeter(System.currentTimeMillis())
    val offer = PushOffer.newBuilder()
      .setItemId(ByteString.copyFrom(NoteLinkProto.uuidBytes(artifact.id)))
      .setKind(
        if (artifact.mimeType == "application/pdf") {
          com.betterhv.transfer.proto.v4.ContentKind.CONTENT_PDF
        } else {
          com.betterhv.transfer.proto.v4.ContentKind.CONTENT_IMAGE
        },
      )
      .setMimeType(artifact.mimeType)
      .setByteLength(artifact.byteLength)
      .setSha256(ByteString.copyFrom(artifact.sha256))
      .setDisplayName(artifact.displayName)
      .build()
    val started = System.nanoTime()
    try {
      val sent = NoteLinkClient.push(session, offer, { artifact.file.inputStream() }) { done, total ->
        progress(artifact.id, done, total, meter)
        progress(done, total)
      }
      Log.i(
        TIMING_TAG,
        "export bytes=${artifact.byteLength} sent=$sent in ${(System.nanoTime() - started) / 1_000_000}ms",
      )
      complete(artifact.id, artifact.id)
    } catch (error: Throwable) {
      fail(error)
      throw error
    }
  }

  suspend fun sendExport(artifact: ExportArtifact, progress: (Long, Long) -> Unit = { _, _ -> }) {
    val paired = pairing.pairedDevice ?: error("Pair with NoteLink in Settings first")
    sendExport(paired.id, artifact, progress)
  }

  /**
   * Asks [clientId] for a newer Note build. The peer resolves and downloads the release while
   * this call relays its progress; the package then streams like any other item.
   */
  suspend fun requestNoteAppUpdate(
    clientId: String,
    currentVersion: String,
    onProgress: (NoteAppUpdateProgress) -> Unit = {},
  ): NoteAppUpdateOutcome = exclusive {
    val paired = pairing.pairedClient(clientId) ?: error("Paired NoteLink does not exist")
    val session = connections.await(clientId, SESSION_WAIT_MILLIS)
      ?: throw failure(TransferErrorCode.CONNECTION_TIMEOUT, "${paired.name} is not reachable")
    onProgress(NoteAppUpdateProgress.Resolving)
    val check = UpdateCheck.newBuilder()
      .setPackageName(appContext.packageName)
      .setVersionCode(BuildConfig.VERSION_CODE.toLong())
      .setVersionName(currentVersion)
      .setOperationId(ByteString.copyFromUtf8(UUID.randomUUID().toString()))
      .build()
    val result = NoteLinkClient.checkUpdate(session, check) { preparing ->
      onProgress(
        if (preparing.totalBytes > 0) {
          NoteAppUpdateProgress.Downloading(preparing.latestVersion, preparing.bytesPrepared, preparing.totalBytes)
        } else {
          NoteAppUpdateProgress.Resolving
        },
      )
    }
    when (result.state) {
      UpdateState.UPDATE_UP_TO_DATE -> NoteAppUpdateOutcome.UpToDate(result.latestVersion.ifBlank { null })
      UpdateState.UPDATE_AVAILABLE -> {
        connections.ensureP2p(session)
        val offer = result.offer
        val itemId = NoteLinkProto.uuid(offer.itemId)
        val part = File(appContext.cacheDir, "note-update-$itemId.apk.part")
        onProgress(NoteAppUpdateProgress.Transferring(result.latestVersion, 0, offer.byteLength))
        start(itemId, clientId, ContentKind.APP_PACKAGE, offer.byteLength)
        val meter = TransferProgressMeter(System.currentTimeMillis())
        try {
          NoteLinkClient.download(
            session,
            offer,
            FileByteSink(part),
            onProgress = { done, total ->
              progress(itemId, done, total, meter)
              onProgress(NoteAppUpdateProgress.Transferring(result.latestVersion, done, total))
            },
          )
        } catch (error: Throwable) {
          part.delete()
          runCatching { NoteLinkClient.lease(session, itemId, LeaseKind.LEASE_RELEASE) }
          fail(error)
          throw error
        }
        complete(itemId, itemId)
        val item = queueItem(offer, itemId, clientId, ContentKind.APP_PACKAGE)
        NoteAppUpdateOutcome.Package(
          result.latestVersion,
          SessionLease(session, RemotePayload.AppPackage(item, part), TransferOffer(item), scope),
        )
      }
      else -> throw failure(
        TransferErrorCode.INTERNAL,
        result.message.ifBlank { "NoteLink could not prepare the update" },
      )
    }
  }

  private fun queueItem(offer: Offer, itemId: UUID, clientId: String, kind: ContentKind) = QueueItem(
    id = itemId,
    destinationDeviceId = clientId,
    kind = kind,
    mimeType = offer.mimeType,
    byteLength = offer.byteLength,
    sha256 = offer.sha256.toByteArray(),
    createdAt = System.currentTimeMillis(),
    position = 0,
    displayName = offer.displayName.ifBlank { null },
  )

  /** Runs one user-visible transfer at a time; [cancel] interrupts it. */
  private suspend fun <T> exclusive(block: suspend () -> T): T = withContext(Dispatchers.IO) {
    if (!operation.tryLock()) error("A NoteLink transfer is already in progress")
    activeJob = currentCoroutineContext()[Job]
    try {
      block()
    } finally {
      activeJob = null
      operation.unlock()
    }
  }

  suspend fun recoverAfterWake() = withContext(Dispatchers.IO) {
    activeJob?.cancel()
    connections.stop()
    connections.start()
    mutableSnapshot.value = TransferSnapshot()
  }

  fun releaseWarmLinks() = connections.stop()

  private fun start(operationId: UUID, deviceId: String, kind: ContentKind?, total: Long = 0) {
    val now = System.currentTimeMillis()
    mutableSnapshot.value = TransferSnapshot(
      operationId = operationId,
      itemId = operationId,
      deviceId = deviceId,
      phase = TransferPhase.TRANSFERRING,
      totalBytes = total,
      startedAtMillis = now,
      phaseStartedAtMillis = now,
      canCancel = true,
    )
    emit(TransferEvent.PhaseChanged(operationId, TransferPhase.IDLE, TransferPhase.TRANSFERRING))
    Log.d(TAG, "operation $operationId kind=$kind peer=$deviceId")
  }

  private fun progress(operationId: UUID, done: Long, total: Long, meter: TransferProgressMeter) {
    val sample = meter.sample(done, total)
    mutableSnapshot.value = mutableSnapshot.value.copy(
      bytesTransferred = done,
      totalBytes = total,
      bytesPerSecond = sample.bytesPerSecond,
      averageBytesPerSecond = sample.averageBytesPerSecond,
      etaMillis = sample.etaMillis,
    )
    if (done == total || done % PROGRESS_EVENT_BYTES < 32 * 1024) {
      emit(TransferEvent.Progress(operationId, done, total, sample.bytesPerSecond, sample.etaMillis))
    }
  }

  private fun phase(next: TransferPhase) {
    val current = mutableSnapshot.value
    mutableSnapshot.value = current.copy(
      phase = next,
      phaseStartedAtMillis = System.currentTimeMillis(),
      canCancel = false,
    )
    emit(TransferEvent.PhaseChanged(current.operationId, current.phase, next))
  }

  private fun complete(operationId: UUID, itemId: UUID) {
    phase(TransferPhase.COMPLETE)
    emit(TransferEvent.Completed(operationId, itemId))
  }

  private fun fail(error: Throwable) {
    if (error is CancellationException) return
    val failure = (error as? TransferChannelException)?.failure ?: TransferFailure(
      when ((error as? ExchangeException)?.code) {
        com.betterhv.transfer.proto.v4.ErrorCode.ERROR_STALLED,
        com.betterhv.transfer.proto.v4.ErrorCode.ERROR_CONNECTION_TIMEOUT -> TransferErrorCode.CONNECTION_TIMEOUT
        com.betterhv.transfer.proto.v4.ErrorCode.ERROR_INTEGRITY_FAILED -> TransferErrorCode.INTEGRITY_FAILED
        else -> TransferErrorCode.INTERNAL
      },
      error.message ?: "Transfer failed",
      true,
      mutableSnapshot.value.mode ?: TransferMode.LAN,
    )
    mutableSnapshot.value = mutableSnapshot.value.copy(
      phase = TransferPhase.FAILED,
      lastFailure = failure,
      canRetry = true,
      canCancel = false,
    )
    emit(TransferEvent.Failed(mutableSnapshot.value.operationId, failure))
  }

  private fun failure(code: TransferErrorCode, message: String) =
    TransferChannelException(TransferFailure(code, message, true))

  override fun cancel() {
    activeJob?.cancel()
    val failure = TransferFailure(TransferErrorCode.CANCELLED, "Transfer cancelled", true)
    mutableSnapshot.value = mutableSnapshot.value.copy(
      phase = TransferPhase.FAILED,
      lastFailure = failure,
      canRetry = true,
      canCancel = false,
    )
    emit(TransferEvent.Failed(mutableSnapshot.value.operationId, failure))
  }

  /** Cancels this facade's operation; the process-wide warm links stay up (see [releaseWarmLinks]). */
  override fun close() {
    activeJob?.cancel()
    scope.coroutineContext[Job]?.cancel()
    mutableSnapshot.value = TransferSnapshot()
  }

  private fun emit(event: TransferEvent) {
    mutableEvents.tryEmit(event)
    val entry = eventLog.record(event)
    EventLog.log(TAG, "${entry.category} ${entry.detail}")
  }

  /**
   * Lease of an item that is already on this device. Commit and release are one request each
   * on the warm session; they never block the UI thread.
   */
  private class SessionLease(
    private val session: NoteLinkSession,
    override val payload: RemotePayload,
    override val offer: TransferOffer,
    private val scope: CoroutineScope,
  ) : ReceivedLease {
    @Volatile private var finished = false

    override fun heartbeat() = Unit
    override fun markTransferring() = Unit
    override fun markAwaitingCommit() = Unit

    override fun commit() = finish(LeaseKind.LEASE_COMMIT)

    override fun release() = finish(LeaseKind.LEASE_RELEASE)

    override suspend fun commitAndAwait() = withContext(Dispatchers.IO + NonCancellable) {
      send(LeaseKind.LEASE_COMMIT)
    }

    override suspend fun releaseAndAwait() = withContext(Dispatchers.IO + NonCancellable) {
      send(LeaseKind.LEASE_RELEASE)
    }

    private fun finish(action: LeaseKind) {
      scope.launch { send(action) }
    }

    private suspend fun send(action: LeaseKind) {
      if (finished) return
      finished = true
      val ok = runCatching { NoteLinkClient.lease(session, offer.item.id, action) }
        .onFailure { Log.w(TAG, "lease $action failed for ${offer.item.id}: ${it.message}") }
        .getOrDefault(false)
      // A release that did not reach the peer expires with the lease TTL there.
      if (!ok && action == LeaseKind.LEASE_COMMIT) {
        throw IllegalStateException("NoteLink did not confirm the transfer; it may be sent again")
      }
    }
  }

  private companion object {
    const val TAG = "NoteLinkTransfer"
    const val TIMING_TAG = "NoteLinkTiming"
    const val SESSION_WAIT_MILLIS = 10_000L
    const val PAIRING_SCAN_MILLIS = 30_000L
    const val PROGRESS_EVENT_BYTES = 512 * 1024L
  }
}
