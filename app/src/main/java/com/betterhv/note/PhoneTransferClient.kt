package com.betterhv.note

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.betterhv.note.export.ExportArtifact
import com.betterhv.transfer.android.AndroidNetworkInfoProvider
import com.betterhv.transfer.android.AndroidPairingController
import com.betterhv.transfer.android.BleGattSession
import com.betterhv.transfer.android.BleIdentity
import com.betterhv.transfer.android.BleReceiverScanner
import com.betterhv.transfer.android.BleReplayCache
import com.betterhv.transfer.android.BleSecureEnvelope
import com.betterhv.transfer.android.DiscoveredSender
import com.betterhv.transfer.android.EncryptedFileTransfer
import com.betterhv.transfer.android.ReceivedLease
import com.betterhv.transfer.android.WifiDirectController
import com.betterhv.transfer.core.BleCommand
import com.betterhv.transfer.core.BleQueueProtocol
import com.betterhv.transfer.core.BleResponse
import com.betterhv.transfer.core.CapabilityNegotiation
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.DeviceCapabilities
import com.betterhv.transfer.core.ExportTransferOffer
import com.betterhv.transfer.core.NetworkEndpoint
import com.betterhv.transfer.core.PairedDevice
import com.betterhv.transfer.core.RemotePayload
import com.betterhv.transfer.core.SsidMatch
import com.betterhv.transfer.core.TransferChannelException
import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.core.TransferErrorCode
import com.betterhv.transfer.core.TransferEvent
import com.betterhv.transfer.core.TransferEventLog
import com.betterhv.transfer.core.TransferFailure
import com.betterhv.transfer.core.TransferLogEntry
import com.betterhv.transfer.core.TransferMode
import com.betterhv.transfer.core.TransferModes
import com.betterhv.transfer.core.TransferNetworkSecurity
import com.betterhv.transfer.core.TransferObservable
import com.betterhv.transfer.core.TransferOffer
import com.betterhv.transfer.core.TransferPhase
import com.betterhv.transfer.core.TransferProgressMeter
import com.betterhv.transfer.core.TransferSnapshot
import com.betterhv.transfer.core.TransferState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
  Result.success(block())
} catch (cancelled: CancellationException) {
  throw cancelled
} catch (error: Throwable) {
  Result.failure(error)
}

class PhoneTransferClient(context: Context) :
  AutoCloseable,
  TransferObservable {
  private val appContext = context.applicationContext
  val pairing = AndroidPairingController(appContext)
  private val transportPreferences = appContext.getSharedPreferences(FAST_PATH_PREFERENCES, Context.MODE_PRIVATE)
  private val mutableState = MutableStateFlow<TransferState>(TransferState.Idle)
  private val mutableSnapshot = MutableStateFlow(TransferSnapshot())
  private val mutableEvents = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)
  private val eventLog = TransferEventLog()
  override val snapshot: StateFlow<TransferSnapshot> = mutableSnapshot.asStateFlow()
  override val events: SharedFlow<TransferEvent> = mutableEvents.asSharedFlow()
  val eventHistory: StateFlow<List<TransferLogEntry>> = eventLog.entries
  private val network = AndroidNetworkInfoProvider(appContext)
  private val wifiDirect = WifiDirectController(appContext)

  @Volatile private var activeOperationJob: Job? = null

  @Volatile private var activeListeningSocket: ServerSocket? = null

  @Volatile private var inboundReplay = BleReplayCache()
  private val operationActive = AtomicBoolean(false)
  private val activeBleSessions = Collections.synchronizedSet(mutableSetOf<BleGattSession>())
  private val recentSenders = ConcurrentHashMap<String, RecentSender>()
  val state: StateFlow<TransferState> = mutableState

  data class AvailableNoteLink(
    val client: PairedDevice,
    val advertisedName: String,
    val imageCount: Int,
    val textCount: Int,
    val pdfCount: Int,
  )

  data class PairingCandidate(
    val deviceId: String,
    val name: String,
    val identityHash: String,
    val bluetoothAddress: String,
  )

  val pairedClients: List<PairedDevice> get() = pairing.pairedClients

  /** Uses the sender advertisement counts without leasing an item. */
  suspend fun hasAvailable(kind: ContentKind): Boolean = withContext(Dispatchers.IO) {
    discoverAvailable(kind, 5_000L).isNotEmpty()
  }

  suspend fun discoverAvailable(
    kind: ContentKind? = null,
    timeoutMillis: Long = 5_000L,
    onUpdate: (List<AvailableNoteLink>) -> Unit = {},
  ): List<AvailableNoteLink> = withContext(Dispatchers.IO) {
    val clients = pairing.pairedClients
    if (clients.isEmpty()) return@withContext emptyList()
    val senders = (
      try {
        newScanner().discover(timeoutMillis, onUpdate = { partial ->
          onUpdate(matchAvailable(clients, partial, kind))
        })
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        EventLog.log("NoteLink", "Discovery failed: ${error.stackTraceToString()}")
        throw IllegalStateException("NoteLink discovery failed: ${error.message ?: error.javaClass.simpleName}", error)
      }
      ).mapNotNull { resolveAdvertisementIdentity(it) }
    matchAvailable(clients, senders, kind).also(onUpdate)
  }

  private fun matchAvailable(
    clients: List<PairedDevice>,
    senders: List<DiscoveredSender>,
    kind: ContentKind?,
  ): List<AvailableNoteLink> = clients.mapNotNull { client ->
    val sender = senders.firstOrNull { candidate ->
      val hasContent = when (kind) {
        ContentKind.IMAGE -> candidate.imageCount > 0
        ContentKind.TEXT -> candidate.textCount > 0
        ContentKind.PDF -> candidate.pdfCount > 0
        null -> true
      }
      hasContent && if (client.legacy) {
        candidate.name == client.name || clients.count(PairedDevice::legacy) == 1 && senders.size == 1
      } else {
        candidate.identityHash.equals(client.identityHash, ignoreCase = true)
      }
    } ?: return@mapNotNull null
    recentSenders[client.id] = RecentSender(sender, SystemClock.elapsedRealtime())
    AvailableNoteLink(client, sender.name, sender.imageCount, sender.textCount, sender.pdfCount)
  }.sortedByDescending { it.client.lastUsedAt }

  suspend fun discoverPairingCandidates(timeoutMillis: Long = 8_000L): List<PairingCandidate> =
    withContext(Dispatchers.IO) {
      val pairedIds = pairing.pairedClients.map(PairedDevice::id).toSet()
      newScanner().discover(timeoutMillis).mapNotNull { sender ->
        try {
          withBleSession(sender.bluetoothAddress) { session ->
            val identity = session.readIdentity()
            val hash = AndroidPairingController.identityHash(identity.deviceId)
            require(sender.identityHash.isBlank() || hash.equals(sender.identityHash, ignoreCase = true)) {
              "NoteLink advertised identity does not match its full identity"
            }
            PairingCandidate(identity.deviceId, identity.deviceName, hash, sender.bluetoothAddress)
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Throwable) {
          null
        }?.takeUnless { it.deviceId in pairedIds }
      }.distinctBy(PairingCandidate::deviceId).sortedBy(PairingCandidate::name)
    }

  /** Manual codes are not considered paired until an encrypted round trip succeeds. */
  suspend fun pair(candidate: PairingCandidate, sixDigitCode: String): PairedDevice = withContext(Dispatchers.IO) {
    val paired = pairing.confirmManual(candidate.deviceId, candidate.name, sixDigitCode)
    try {
      withBleSession(candidate.bluetoothAddress) { session ->
        val identity = session.readIdentity()
        require(identity.deviceId == candidate.deviceId) { "NoteLink identity changed during pairing" }
        require(
          AndroidPairingController.identityHash(identity.deviceId)
            .equals(candidate.identityHash, ignoreCase = true),
        ) { "NoteLink advertised identity does not match its full identity" }
        val key = requireNotNull(pairing.sharedKey(paired.id)) { "Pairing key is unavailable" }
        require(
          exchange(session, paired.id, BleCommand.Capabilities(localCapabilities(key))) is BleResponse.Capabilities,
        ) {
          "NoteLink did not confirm pairing"
        }
      }
      pairing.markLastUsed(paired.id)
    } catch (cancelled: CancellationException) {
      pairing.unpair(paired.id)
      throw cancelled
    } catch (t: Throwable) {
      pairing.unpair(paired.id)
      throw IllegalStateException("Pairing handshake failed: ${t.message ?: t.javaClass.simpleName}", t)
    }
  }

  init {
    pairing.pairedDevice?.takeIf {
      it.id == "betterhv-phone" && it.name == "BetterHv Send"
    }?.let { pairing.updatePairedDeviceName("NoteLink") }
  }

  suspend fun requestNext(kind: ContentKind): ReceivedLease? {
    val paired = pairing.pairedDevice ?: error("Pair with NoteLink in Settings first")
    return requestNext(paired.id, kind)
  }

  /** Authenticates a paired NoteLink and performs a v2 capability round trip without leasing queue content. */
  suspend fun verifyConnection(clientId: String): CapabilityNegotiation = withContext(Dispatchers.IO) {
    val paired = pairing.pairedClient(clientId) ?: error("Paired NoteLink does not exist")
    val sender = findSender(paired, null, 8_000L)
      ?: error("Could not find ${paired.name}; make sure NoteLink is running")
    withBleSession(sender.bluetoothAddress) { session ->
      val identity = verifyIdentity(session, sender, paired)
      var authenticatedId = paired.id
      if (paired.legacy) authenticatedId = pairing.resolveLegacyIdentity(paired.id, identity).id
      val key = requireNotNull(pairing.sharedKey(authenticatedId)) { "Pairing key is unavailable" }
      negotiate(session, authenticatedId, key).also { pairing.markLastUsed(authenticatedId) }
    }
  }

  suspend fun requestNext(clientId: String, kind: ContentKind): ReceivedLease? = withContext(Dispatchers.IO) {
    check(operationActive.compareAndSet(false, true)) { "A NoteLink transfer is already in progress" }
    activeOperationJob = currentCoroutineContext()[Job]
    var keepActive = false
    val paired = pairing.pairedClient(clientId) ?: run {
      operationActive.set(false)
      error("Paired NoteLink does not exist")
    }
    mutableState.value = TransferState.Scanning
    startOperation(UUID.randomUUID(), null, paired.id, 0, TransferPhase.DISCOVERING)
    val startedAt = SystemClock.elapsedRealtime()
    val sender = findSender(paired, kind, 5_000L) ?: run {
      operationActive.set(false)
      mutableState.value = TransferState.Idle
      return@withContext null
    }
    var session: BleGattSession? = null
    var authenticatedId = paired.id
    var leasedItemId: UUID? = null
    try {
      Log.i(TAG, "sender ready after ${elapsed(startedAt)} ms; connecting GATT")
      val activeSession = openBleSession(sender.bluetoothAddress).also { session = it }
      Log.i(TAG, "GATT ready after ${elapsed(startedAt)} ms")
      phase(TransferPhase.AUTHENTICATING)
      val identity = verifyIdentity(activeSession, sender, paired)
      if (paired.legacy) {
        authenticatedId = pairing.resolveLegacyIdentity(paired.id, identity).id
      }
      val key = requireNotNull(pairing.sharedKey(authenticatedId)) { "Pairing key is unavailable" }
      val negotiation = if (kind == ContentKind.TEXT || prefersLan(authenticatedId)) {
        null
      } else {
        negotiate(activeSession, authenticatedId, key)
      }
      pairing.markLastUsed(authenticatedId)
      val response = exchange(activeSession, authenticatedId, BleCommand.Lease(kind, "betterhv-note"))
      rememberAuthenticatedSenderName(authenticatedId, identity.deviceName)
      val offer = (response as? BleResponse.Offer)?.item
        ?: if (response == BleResponse.Empty) {
          closeBleSession(activeSession)
          mutableState.value = TransferState.Idle
          return@withContext null
        } else {
          error("Phone returned an invalid queue response")
        }
      leasedItemId = offer.id
      mutableSnapshot.value = mutableSnapshot.value.copy(itemId = offer.id, totalBytes = offer.byteLength)
      val payload = when (kind) {
        ContentKind.TEXT -> receiveText(activeSession, authenticatedId, offer)

        ContentKind.IMAGE -> receiveImage(activeSession, offer, authenticatedId, negotiation, key)

        ContentKind.PDF -> receiveImage(activeSession, offer, authenticatedId, negotiation, key).let {
          RemotePayload.Pdf(it.item, it.stagedFile)
        }
      }
      Log.i(TAG, "${kind.name.lowercase()} payload ready after ${elapsed(startedAt)} ms")
      mutableState.value = TransferState.AwaitingPlacement(offer.id)
      phase(TransferPhase.AWAITING_COMMIT)
      keepActive = true
      NoteReceivedLease(activeSession, authenticatedId, TransferOffer(offer), payload) {
        mutableState.value = TransferState.Idle
        activeOperationJob = null
        operationActive.set(false)
      }
    } catch (t: Throwable) {
      val failedSession = session
      leasedItemId?.let { itemId ->
        if (failedSession != null) {
          runCatchingCancellable {
            exchange(
              failedSession,
              authenticatedId,
              BleCommand.Release(itemId),
            )
          }
        }
      }
      failedSession?.let(::closeBleSession)
      mutableState.value = TransferState.Error(t.message ?: "Phone transfer failed")
      fail(t)
      throw t
    } finally {
      if (!keepActive) {
        activeOperationJob = null
        operationActive.set(false)
      }
    }
  }

  suspend fun sendExport(artifact: ExportArtifact, progress: (Long, Long) -> Unit = { _, _ -> }) {
    val paired = pairing.pairedDevice ?: error("Pair with NoteLink in Settings first")
    sendExport(paired.id, artifact, progress)
  }

  suspend fun sendExport(clientId: String, artifact: ExportArtifact, progress: (Long, Long) -> Unit = { _, _ -> }) =
    withContext(Dispatchers.IO) {
      check(operationActive.compareAndSet(false, true)) { "A NoteLink transfer is already in progress" }
      activeOperationJob = currentCoroutineContext()[Job]
      val paired = pairing.pairedClient(clientId) ?: run {
        operationActive.set(false)
        error("Paired NoteLink does not exist")
      }
      require(artifact.byteLength <= com.betterhv.transfer.core.TransferLimits.MAX_EXPORT_BYTES) {
        "Export file exceeds 512 MiB"
      }
      mutableState.value = TransferState.Scanning
      startOperation(artifact.id, artifact.id, paired.id, artifact.byteLength, TransferPhase.DISCOVERING)
      try {
        val sender = findSender(paired, null, 8_000L)
          ?: error("Could not find ${paired.name}; make sure NoteLink is running")
        withBleSession(sender.bluetoothAddress) { session ->
          phase(TransferPhase.AUTHENTICATING)
          val identity = verifyIdentity(session, sender, paired)
          var authenticatedId = paired.id
          if (paired.legacy) authenticatedId = pairing.resolveLegacyIdentity(paired.id, identity).id
          val key = requireNotNull(pairing.sharedKey(authenticatedId)) { "Pairing key is unavailable" }
          val fastLanEndpoint = rememberedRemoteLanEndpoint(authenticatedId)
          val negotiation = if (fastLanEndpoint == null) negotiate(session, authenticatedId, key) else null
          pairing.markLastUsed(authenticatedId)
          rememberAuthenticatedSenderName(authenticatedId, identity.deviceName)
          negotiation?.let {
            require(it.remote.extensions and BleQueueProtocol.CAPABILITY_EXPORT_PUSH != 0) {
              "This NoteLink version does not support receiving exports"
            }
          }
          val offer = ExportTransferOffer(
            artifact.id,
            artifact.displayName,
            artifact.mimeType,
            artifact.byteLength,
            artifact.sha256,
          )
          when (val response = exchange(session, authenticatedId, BleCommand.PushOffer(offer))) {
            is BleResponse.AlreadyReceived -> {
              complete(artifact.id);
              return@withBleSession
            }
            BleResponse.Ok -> Unit
            is BleResponse.Failure -> throw TransferChannelException(response.failure)
            is BleResponse.Error -> error(response.message)
            else -> error("NoteLink did not accept the exported file")
          }
          try {
            sendExportWithSelection(
              ExportTransferContext(session, authenticatedId, artifact, key, progress),
              TransportSelection(negotiation, fastLanEndpoint),
            )
            withTimeout(30_000L) {
              while (true) {
                when (val response = exchange(session, authenticatedId, BleCommand.PushStatus(artifact.id))) {
                  is BleResponse.PushComplete, is BleResponse.AlreadyReceived -> return@withTimeout
                  BleResponse.Pending -> delay(250L)
                  is BleResponse.Failure -> throw TransferChannelException(response.failure)
                  is BleResponse.Error -> error(response.message)
                  else -> error("NoteLink did not confirm the exported file")
                }
              }
            }
            complete(artifact.id)
            mutableState.value = TransferState.Idle
          } catch (t: Throwable) {
            runCatchingCancellable { exchange(session, authenticatedId, BleCommand.PushCancel(artifact.id)) }
            throw t
          }
        }
      } catch (error: Throwable) {
        fail(error)
        mutableState.value = TransferState.Error(error.message ?: "Could not send export")
        throw error
      } finally {
        activeOperationJob = null
        operationActive.set(false)
      }
    }

  private suspend fun receiveText(
    session: BleGattSession,
    clientId: String,
    item: com.betterhv.transfer.core.QueueItem,
  ): RemotePayload.Text {
    val output = java.io.ByteArrayOutputStream(item.byteLength.toInt())
    var offset = 0
    while (offset < item.byteLength) {
      val chunk = exchange(session, clientId, BleCommand.TextChunk(item.id, offset)) as? BleResponse.TextChunk
        ?: error("Invalid phone text chunk response")
      require(chunk.itemId == item.id && chunk.offset == offset && chunk.total.toLong() == item.byteLength)
      output.write(chunk.bytes);
      offset += chunk.bytes.size
    }
    val bytes = output.toByteArray()
    require(MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(item.sha256)) {
      "Text verification failed"
    }
    return RemotePayload.Text(item, bytes.decodeToString())
  }

  private suspend fun receiveImage(
    session: BleGattSession,
    item: com.betterhv.transfer.core.QueueItem,
    pairedPhoneId: String,
    negotiation: CapabilityNegotiation?,
    key: ByteArray,
  ): RemotePayload.Image = coroutineScope {
    val incoming = File(appContext.filesDir, "documents/incoming/remote-${item.id}.part")
    val local = localCapabilities(key)
    val lanEndpoint = local.lanEndpoint
    var firstFailure: Throwable? = null
    var fastLanAttempted = false
    if (negotiation == null && prefersLan(pairedPhoneId) && lanEndpoint != null) {
      fastLanAttempted = true
      runCatchingCancellable {
        receiveImageAttempt(session, pairedPhoneId, item, incoming, key, TransferMode.LAN, lanEndpoint)
      }.fold(
        onSuccess = {
          rememberLanSuccess(pairedPhoneId)
          return@coroutineScope it
        },
        onFailure = {
          firstFailure = it
          forgetLanSuccess(pairedPhoneId)
        },
      )
    }
    val resolvedNegotiation = negotiation ?: negotiate(session, pairedPhoneId, key)
    val canLan = !fastLanAttempted && resolvedNegotiation.ssidMatch == SsidMatch.MATCH && lanEndpoint != null &&
      TransferModes.contains(local.modes, TransferMode.LAN) &&
      TransferModes.contains(resolvedNegotiation.remote.modes, TransferMode.LAN)
    if (canLan) {
      runCatchingCancellable {
        receiveImageAttempt(session, pairedPhoneId, item, incoming, key, TransferMode.LAN, lanEndpoint)
      }.fold(
        onSuccess = {
          rememberLanSuccess(pairedPhoneId, resolvedNegotiation.remote.lanEndpoint)
          return@coroutineScope it
        },
        onFailure = { firstFailure = it },
      )
    }
    val wifiEndpoint = resolvedNegotiation.remote.wifiDirectEndpoint
    if (wifiEndpoint != null &&
      TransferModes.contains(resolvedNegotiation.remote.modes, TransferMode.WIFI_DIRECT)
    ) {
      firstFailure?.let { fallback(item.id, it) }
      phase(TransferPhase.JOINING_WIFI_DIRECT)
      val joined = wifiDirect.joinHostedGroup(
        wifiEndpoint.deviceAddress,
        "NoteLink",
        wifiEndpoint.networkName,
        wifiEndpoint.passphrase,
      )
      val receiverEndpoint = NetworkEndpoint(
        requireNotNull(joined.localIpAddress) {
          "Could not read the Note device's Wi-Fi Direct address"
        }
      )
      forgetLanSuccess(pairedPhoneId)
      return@coroutineScope receiveImageAttempt(
        session,
        pairedPhoneId,
        item,
        incoming,
        key,
        TransferMode.WIFI_DIRECT,
        receiverEndpoint,
      )
    }
    throw firstFailure ?: TransferChannelException(
      TransferFailure(
        TransferErrorCode.UNSUPPORTED_MODE,
        "No large-file channel is available",
        false,
      )
    )
  }

  private suspend fun kotlinx.coroutines.CoroutineScope.receiveImageAttempt(
    session: BleGattSession,
    clientId: String,
    item: com.betterhv.transfer.core.QueueItem,
    incoming: File,
    pairingKey: ByteArray,
    mode: TransferMode,
    receiverEndpoint: NetworkEndpoint,
  ): RemotePayload.Image {
    val nonce = TransferCrypto.randomBytes(TransferNetworkSecurity.SESSION_NONCE_BYTES)
    val fileKey = TransferNetworkSecurity.sessionKey(pairingKey, item.id, nonce, "file")
    val probeKey = TransferNetworkSecurity.sessionKey(pairingKey, item.id, nonce, "probe")
    val meter = TransferProgressMeter(System.currentTimeMillis())
    selectTransport(item.id, mode, receiverEndpoint)
    val receiver = async(Dispatchers.IO) {
      try {
        EncryptedFileTransfer.receive(
          incoming,
          item.id,
          fileKey,
          probeKey = if (mode == TransferMode.LAN) probeKey else null,
          progress = { done, total -> updateProgress(item.id, done, total, meter) },
          onListening = { activeListeningSocket = it },
        )
      } finally {
        activeListeningSocket = null
      }
    }
    val response = try {
      exchange(
        session,
        clientId,
        BleCommand.PrepareFileTransfer(item.id, mode, receiverEndpoint, nonce),
      )
    } catch (error: Throwable) {
      activeListeningSocket?.close()
      receiver.cancelAndJoin()
      throw error
    }
    if (response !is BleResponse.Prepared) {
      activeListeningSocket?.close()
      receiver.cancelAndJoin()
      if (response is BleResponse.Failure) throw TransferChannelException(response.failure)
      error((response as? BleResponse.Error)?.message ?: "NoteLink did not prepare the file transfer")
    }
    val received = receiver.await()
    activeListeningSocket = null
    phase(TransferPhase.VERIFYING)
    require(received.byteLength == item.byteLength && received.sha256.contentEquals(item.sha256)) {
      "Image length or checksum does not match"
    }
    return RemotePayload.Image(item, incoming)
  }

  private suspend fun sendExportWithSelection(context: ExportTransferContext, selection: TransportSelection) {
    val local = localCapabilities(context.pairingKey)
    var firstFailure: Throwable? = null
    val fastLanEndpoint = selection.fastLanEndpoint
    if (fastLanEndpoint != null) {
      val fastResult = tryExportLan(context, fastLanEndpoint)
      if (fastResult.isSuccess) {
        rememberLanSuccess(context.clientId, fastLanEndpoint)
        return
      }
      forgetLanSuccess(context.clientId)
      firstFailure = fastResult.exceptionOrNull()
    }
    val resolvedNegotiation = selection.negotiation ?: negotiate(
      context.session,
      context.clientId,
      context.pairingKey,
    )
    val remoteLan = resolvedNegotiation.remote.lanEndpoint
    val canLan = resolvedNegotiation.ssidMatch == SsidMatch.MATCH && remoteLan != null &&
      remoteLan != fastLanEndpoint &&
      TransferModes.contains(local.modes, TransferMode.LAN) &&
      TransferModes.contains(resolvedNegotiation.remote.modes, TransferMode.LAN)
    if (canLan) {
      val lanResult = tryExportLan(context, remoteLan)
      if (lanResult.isSuccess) {
        rememberLanSuccess(context.clientId, remoteLan)
        return
      }
      firstFailure = lanResult.exceptionOrNull()
    }
    val wifi = resolvedNegotiation.remote.wifiDirectEndpoint
    if (wifi != null && TransferModes.contains(resolvedNegotiation.remote.modes, TransferMode.WIFI_DIRECT)) {
      firstFailure?.let { fallback(context.artifact.id, it) }
      phase(TransferPhase.JOINING_WIFI_DIRECT)
      val joined = wifiDirect.joinHostedGroup(wifi.deviceAddress, "NoteLink", wifi.networkName, wifi.passphrase)
      sendExportAttempt(
        context.session,
        context.clientId,
        context.artifact,
        context.pairingKey,
        TransferMode.WIFI_DIRECT,
        NetworkEndpoint(joined.groupOwnerAddress, wifi.port),
        context.progressCallback,
      )
      forgetLanSuccess(context.clientId)
    } else {
      throw firstFailure ?: TransferChannelException(
        TransferFailure(
          TransferErrorCode.UNSUPPORTED_MODE,
          "No large-file channel is available",
          false,
        )
      )
    }
  }

  private suspend fun sendExportAttempt(
    session: BleGattSession,
    clientId: String,
    artifact: ExportArtifact,
    pairingKey: ByteArray,
    mode: TransferMode,
    receiverEndpoint: NetworkEndpoint,
    progressCallback: (Long, Long) -> Unit,
  ) {
    val nonce = TransferCrypto.randomBytes(TransferNetworkSecurity.SESSION_NONCE_BYTES)
    val fileKey = TransferNetworkSecurity.sessionKey(pairingKey, artifact.id, nonce, "file")
    val probeKey = TransferNetworkSecurity.sessionKey(pairingKey, artifact.id, nonce, "probe")
    selectTransport(artifact.id, mode, receiverEndpoint)
    when (
      val response = exchange(
        session,
        clientId,
        BleCommand.PrepareFileTransfer(artifact.id, mode, receiverEndpoint, nonce),
      )
    ) {
      is BleResponse.Prepared -> Unit
      is BleResponse.Failure -> throw TransferChannelException(response.failure)
      is BleResponse.Error -> error(response.message)
      else -> error("NoteLink is not ready to receive the file")
    }
    if (mode == TransferMode.LAN) {
      phase(TransferPhase.PROBING_LAN)
      EncryptedFileTransfer.probe(receiverEndpoint.host, artifact.id, probeKey)
    }
    val meter = TransferProgressMeter(System.currentTimeMillis())
    EncryptedFileTransfer.send(receiverEndpoint.host, artifact.id, artifact.file, fileKey) { done, total ->
      updateProgress(artifact.id, done, total, meter)
      progressCallback(done, total)
    }
  }

  private suspend fun exchange(session: BleGattSession, clientId: String, command: BleCommand): BleResponse {
    val key = requireNotNull(pairing.sharedKey(clientId)) { "Pairing key is unavailable" }
    val sealed = BleSecureEnvelope.seal(key, pairing.localDeviceId, BleQueueProtocol.encode(command))
    val response = session.exchange(sealed)
    return BleQueueProtocol.decodeResponse(BleSecureEnvelope.open(key, response, inboundReplay))
  }

  private fun localCapabilities(key: ByteArray): DeviceCapabilities {
    val base = network.capabilities(key)
    return base.copy(modes = base.modes or TransferModes.WIFI_DIRECT)
  }

  private suspend fun negotiate(session: BleGattSession, clientId: String, key: ByteArray): CapabilityNegotiation {
    phase(TransferPhase.NEGOTIATING_CAPABILITIES)
    val local = localCapabilities(key)
    val response = exchange(session, clientId, BleCommand.Capabilities(local))
    val negotiation = (response as? BleResponse.Capabilities)?.negotiation
      ?: if (response is BleResponse.Failure) {
        throw TransferChannelException(response.failure)
      } else {
        error("NoteLink is outdated; update it and try again")
      }
    mutableSnapshot.value = mutableSnapshot.value.copy(
      localModes = local.modes,
      remoteModes = negotiation.remote.modes,
      ssidMatch = negotiation.ssidMatch,
      wifiDirectGroupReady = negotiation.remote.wifiDirectEndpoint != null,
    )
    emitEvent(TransferEvent.CapabilityNegotiated(local.modes, negotiation.remote.modes, negotiation.ssidMatch))
    return negotiation
  }

  private fun startOperation(
    operationId: UUID,
    itemId: UUID?,
    deviceId: String,
    total: Long,
    initialPhase: TransferPhase,
  ) {
    val now = System.currentTimeMillis()
    mutableSnapshot.value = TransferSnapshot(
      operationId = operationId,
      itemId = itemId,
      deviceId = deviceId,
      phase = initialPhase,
      totalBytes = total,
      startedAtMillis = now,
      phaseStartedAtMillis = now,
      canCancel = true,
    )
  }

  private fun phase(next: TransferPhase) {
    val current = mutableSnapshot.value
    mutableSnapshot.value = current.copy(
      phase = next,
      phaseStartedAtMillis = System.currentTimeMillis(),
      canCancel = next !in setOf(TransferPhase.IDLE, TransferPhase.COMPLETE, TransferPhase.FAILED),
    )
    emitEvent(TransferEvent.PhaseChanged(current.operationId, current.phase, next))
  }

  private fun selectTransport(itemId: UUID, mode: TransferMode, endpoint: NetworkEndpoint) {
    mutableSnapshot.value = mutableSnapshot.value.copy(
      itemId = itemId,
      mode = mode,
      endpoint = endpoint,
      attempt = mutableSnapshot.value.attempt + 1,
      phase = TransferPhase.WAITING_FOR_PEER,
    )
    mutableSnapshot.value.operationId?.let { emitEvent(TransferEvent.TransportSelected(it, mode, endpoint)) }
  }

  private fun updateProgress(itemId: UUID, done: Long, total: Long, meter: TransferProgressMeter) {
    val sample = meter.sample(done, total)
    mutableState.value = TransferState.Transferring(itemId, done, total)
    mutableSnapshot.value = mutableSnapshot.value.copy(
      phase = TransferPhase.TRANSFERRING,
      bytesTransferred = done,
      totalBytes = total,
      bytesPerSecond = sample.bytesPerSecond,
      averageBytesPerSecond = sample.averageBytesPerSecond,
      etaMillis = sample.etaMillis,
    )
    mutableSnapshot.value.operationId?.let {
      emitEvent(TransferEvent.Progress(it, done, total, sample.bytesPerSecond, sample.etaMillis))
    }
  }

  private fun fallback(itemId: UUID, error: Throwable) {
    val reason = (error as? TransferChannelException)?.failure ?: TransferFailure(
      TransferErrorCode.INTERNAL,
      error.message ?: "LAN transfer failed",
      true,
      TransferMode.LAN,
    )
    val operationId = mutableSnapshot.value.operationId ?: itemId
    mutableSnapshot.value = mutableSnapshot.value.copy(
      phase = TransferPhase.FALLING_BACK,
      fallbackCount = 1,
      fallbackReason = reason,
      phaseStartedAtMillis = System.currentTimeMillis(),
    )
    emitEvent(TransferEvent.Fallback(operationId, TransferMode.LAN, TransferMode.WIFI_DIRECT, reason))
  }

  private fun complete(itemId: UUID) {
    phase(TransferPhase.COMPLETE)
    mutableSnapshot.value.operationId?.let { emitEvent(TransferEvent.Completed(it, itemId)) }
  }

  private fun fail(error: Throwable) {
    val failure = when (error) {
      is TransferChannelException -> error.failure

      is TimeoutCancellationException -> TransferFailure(
        TransferErrorCode.CONNECTION_TIMEOUT,
        error.message ?: "Connection timed out",
        true,
        mutableSnapshot.value.mode,
      )

      else -> TransferFailure(
        TransferErrorCode.INTERNAL,
        error.message ?: "Transfer failed",
        true,
        mutableSnapshot.value.mode,
      )
    }
    mutableSnapshot.value = mutableSnapshot.value.copy(
      phase = TransferPhase.FAILED,
      lastFailure = failure,
      canRetry = failure.recoverable,
      canCancel = false,
    )
    emitEvent(TransferEvent.Failed(mutableSnapshot.value.operationId, failure))
  }

  private suspend fun findSender(paired: PairedDevice, kind: ContentKind?, timeoutMillis: Long): DiscoveredSender? {
    recentSenders[paired.id]?.takeIf { recent ->
      SystemClock.elapsedRealtime() - recent.observedAt <= RECENT_SENDER_MILLIS &&
        senderMatches(recent.sender, paired, kind)
    }?.let { return it.sender }
    val sender = newScanner().discoverFirst(timeoutMillis) { senderMatches(it, paired, kind) }
      ?.let { resolveAdvertisementIdentity(it) }
      ?: return null
    recentSenders[paired.id] = RecentSender(sender, SystemClock.elapsedRealtime())
    return sender
  }

  private fun senderMatches(sender: DiscoveredSender, paired: PairedDevice, kind: ContentKind?): Boolean {
    val contentMatches = when (kind) {
      ContentKind.IMAGE -> sender.imageCount > 0
      ContentKind.TEXT -> sender.textCount > 0
      ContentKind.PDF -> sender.pdfCount > 0
      null -> true
    }
    return contentMatches && (paired.legacy || sender.identityHash.equals(paired.identityHash, ignoreCase = true))
  }

  private suspend fun resolveAdvertisementIdentity(sender: DiscoveredSender): DiscoveredSender? {
    if (sender.identityHash.isNotBlank()) return sender
    return runCatchingCancellable {
      withBleSession(sender.bluetoothAddress) { session ->
        val identity = session.readIdentity()
        DiscoveredSender(
          bluetoothAddress = sender.bluetoothAddress,
          deviceId = AndroidPairingController.identityHash(identity.deviceId),
          name = identity.deviceName,
          imageCount = identity.imageCount,
          textCount = identity.textCount,
          pdfCount = identity.pdfCount,
        )
      }
    }.getOrNull()
  }

  private suspend fun verifyIdentity(
    session: BleGattSession,
    sender: DiscoveredSender,
    paired: PairedDevice,
  ): BleIdentity {
    val identity = session.readIdentity()
    val actualHash = AndroidPairingController.identityHash(identity.deviceId)
    require(sender.identityHash.isBlank() || actualHash.equals(sender.identityHash, ignoreCase = true)) {
      "NoteLink advertised identity does not match its full identity"
    }
    if (!paired.legacy) {
      require(identity.deviceId == paired.id) { "Connected NoteLink does not match the selected client" }
    }
    return identity
  }

  private fun rememberAuthenticatedSenderName(clientId: String, name: String) {
    val normalized = name.trim()
    if (normalized.isNotEmpty() && pairing.pairedClient(clientId)?.name != normalized) {
      pairing.updatePairedDeviceName(clientId, normalized)
    }
  }

  private suspend fun tryExportLan(context: ExportTransferContext, endpoint: NetworkEndpoint): Result<Unit> =
    runCatchingCancellable {
      sendExportAttempt(
        context.session,
        context.clientId,
        context.artifact,
        context.pairingKey,
        TransferMode.LAN,
        endpoint,
        context.progressCallback,
      )
    }

  private fun prefersLan(clientId: String): Boolean =
    transportPreferences.getBoolean(fastPathKey(clientId, LAN_SUCCESS_SUFFIX), false)

  private fun rememberedRemoteLanEndpoint(clientId: String): NetworkEndpoint? = if (!prefersLan(clientId)) {
    null
  } else {
    val host = transportPreferences.getString(fastPathKey(clientId, LAN_HOST_SUFFIX), null)
    val port = transportPreferences.getInt(fastPathKey(clientId, LAN_PORT_SUFFIX), 0)
    host?.let { runCatching { NetworkEndpoint(it, port).validated() }.getOrNull() }
  }

  private fun rememberLanSuccess(clientId: String, remoteEndpoint: NetworkEndpoint? = null) {
    transportPreferences.edit {
      putBoolean(fastPathKey(clientId, LAN_SUCCESS_SUFFIX), true)
      remoteEndpoint?.let {
        putString(fastPathKey(clientId, LAN_HOST_SUFFIX), it.host)
        putInt(fastPathKey(clientId, LAN_PORT_SUFFIX), it.port)
      }
    }
  }

  private fun forgetLanSuccess(clientId: String) {
    transportPreferences.edit {
      remove(fastPathKey(clientId, LAN_SUCCESS_SUFFIX))
      remove(fastPathKey(clientId, LAN_HOST_SUFFIX))
      remove(fastPathKey(clientId, LAN_PORT_SUFFIX))
    }
  }

  private fun fastPathKey(clientId: String, suffix: String): String = "$clientId:$suffix"

  /** Drops radio objects that Android commonly invalidates while the tablet sleeps. */
  suspend fun recoverAfterWake() = withContext(Dispatchers.IO) {
    val sessions = synchronized(activeBleSessions) { activeBleSessions.toList() }
    sessions.forEach(::closeBleSession)
    runCatchingCancellable { wifiDirect.disconnectJoinedGroup() }
    inboundReplay = BleReplayCache()
    activeOperationJob?.cancel();
    activeOperationJob = null
    activeListeningSocket?.close();
    activeListeningSocket = null
    operationActive.set(false)
    mutableState.value = TransferState.Idle
    mutableSnapshot.value = TransferSnapshot()
  }

  private fun newScanner() = BleReceiverScanner(appContext)

  private suspend fun openBleSession(bluetoothAddress: String): BleGattSession {
    // Every connection in this client follows a scan. Some vendor Android
    // controllers report status 133 when connectGatt starts in the same
    // radio timeslice as stopScan, even though the advertisement was valid.
    delay(SCAN_TO_GATT_SETTLE_MILLIS)
    return BleGattSession.connect(appContext, bluetoothAddress).also(activeBleSessions::add)
  }

  private fun closeBleSession(session: BleGattSession) {
    activeBleSessions.remove(session)
    session.close()
  }

  private suspend fun <T> withBleSession(bluetoothAddress: String, block: suspend (BleGattSession) -> T): T {
    val session = openBleSession(bluetoothAddress)
    return try {
      block(session)
    } finally {
      closeBleSession(session)
    }
  }

  override fun cancel() {
    activeListeningSocket?.close();
    activeListeningSocket = null
    activeOperationJob?.cancel()
    activeOperationJob = null
    wifiDirect.requestCloseGroup()
    operationActive.set(false)
    val failure = TransferFailure(TransferErrorCode.CANCELLED, "Transfer cancelled", true, mutableSnapshot.value.mode)
    mutableSnapshot.value = mutableSnapshot.value.copy(
      phase = TransferPhase.FAILED,
      lastFailure = failure,
      canRetry = true,
      canCancel = false,
    )
    emitEvent(TransferEvent.Failed(mutableSnapshot.value.operationId, failure))
  }

  override fun close() {
    val sessions = synchronized(activeBleSessions) { activeBleSessions.toList() }
    sessions.forEach(::closeBleSession)
    activeOperationJob?.cancel();
    activeOperationJob = null
    activeListeningSocket?.close();
    activeListeningSocket = null
    wifiDirect.requestCloseGroup()
    wifiDirect.close()
    mutableState.value = TransferState.Idle
    mutableSnapshot.value = TransferSnapshot()
    operationActive.set(false)
  }

  private fun emitEvent(event: TransferEvent) {
    if (!mutableEvents.tryEmit(event)) Log.w(TAG, "Transfer event buffer full: ${event.javaClass.simpleName}")
    val entry = eventLog.record(event)
    EventLog.log(TAG, "${entry.category} ${entry.detail}")
  }

  private inner class NoteReceivedLease(
    private val session: BleGattSession,
    private val clientId: String,
    override val offer: TransferOffer,
    override val payload: RemotePayload,
    private val finish: () -> Unit,
  ) : ReceivedLease {
    @Volatile private var finished = false
    private val finishMutex = Mutex()
    override fun heartbeat() {
      if (!finished) blockingExchange(BleCommand.Heartbeat(offer.item.id))
    }
    override fun markTransferring() = Unit
    override fun markAwaitingCommit() = Unit
    override fun commit() = finishWith(BleCommand.Commit(offer.item.id))
    override fun release() = finishWith(BleCommand.Release(offer.item.id))
    override suspend fun heartbeatAndAwait() {
      if (!finished) exchangeAndRequireOk(BleCommand.Heartbeat(offer.item.id))
    }
    override suspend fun commitAndAwait() = finishWithAwait(BleCommand.Commit(offer.item.id))
    override suspend fun releaseAndAwait() = finishWithAwait(BleCommand.Release(offer.item.id))
    private suspend fun exchangeAndRequireOk(command: BleCommand) {
      val response = exchange(session, clientId, command)
      require(response == BleResponse.Ok) {
        (response as? BleResponse.Error)?.message ?: "Phone rejected the operation"
      }
    }
    private fun blockingExchange(command: BleCommand) {
      requireWorkerThread()
      runBlocking(Dispatchers.IO) { exchangeAndRequireOk(command) }
    }
    private fun finishWith(command: BleCommand) {
      requireWorkerThread()
      runBlocking(Dispatchers.IO) { finishWithAwait(command) }
    }
    private fun requireWorkerThread() = check(Looper.myLooper() != Looper.getMainLooper()) {
      "ReceivedLease must be finalized asynchronously"
    }
    private suspend fun finishWithAwait(command: BleCommand) = finishMutex.withLock {
      if (finished) return@withLock
      try {
        exchangeAndRequireOk(command)
        if (command is BleCommand.Commit) complete(offer.item.id)
      } catch (error: Throwable) {
        if (command is BleCommand.Commit) fail(error)
        throw error
      } finally {
        finished = true
        closeSession()
      }
    }
    private fun closeSession() {
      closeBleSession(session);
      finish()
    }
  }

  private companion object {
    const val FAST_PATH_PREFERENCES = "notelink_fast_path"
    const val LAN_HOST_SUFFIX = "lan_host"
    const val LAN_PORT_SUFFIX = "lan_port"
    const val LAN_SUCCESS_SUFFIX = "lan_success"
    const val TAG = "NoteLinkTransfer"
    const val SCAN_TO_GATT_SETTLE_MILLIS = 150L
    const val RECENT_SENDER_MILLIS = 30_000L
  }

  private data class RecentSender(val sender: DiscoveredSender, val observedAt: Long)

  private data class TransportSelection(val negotiation: CapabilityNegotiation?, val fastLanEndpoint: NetworkEndpoint?)

  private data class ExportTransferContext(
    val session: BleGattSession,
    val clientId: String,
    val artifact: ExportArtifact,
    val pairingKey: ByteArray,
    val progressCallback: (Long, Long) -> Unit,
  )

  private fun elapsed(startedAt: Long): Long = SystemClock.elapsedRealtime() - startedAt
}
