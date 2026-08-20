package com.betterhv.note.sender

import android.content.Context
import android.util.Log
import com.betterhv.transfer.android.AndroidNetworkInfoProvider
import com.betterhv.transfer.android.AndroidPairingController
import com.betterhv.transfer.android.BleReplayCache
import com.betterhv.transfer.android.BleSecureEnvelope
import com.betterhv.transfer.android.EncryptedFileTransfer
import com.betterhv.transfer.android.HighBandwidthSessionManager
import com.betterhv.transfer.android.SenderLease
import com.betterhv.transfer.core.BleCommand
import com.betterhv.transfer.core.BleQueueProtocol
import com.betterhv.transfer.core.BleResponse
import com.betterhv.transfer.core.CapabilityNegotiation
import com.betterhv.transfer.core.DeviceCapabilities
import com.betterhv.transfer.core.TransferChannelException
import com.betterhv.transfer.core.TransferErrorCode
import com.betterhv.transfer.core.TransferEvent
import com.betterhv.transfer.core.TransferFailure
import com.betterhv.transfer.core.TransferMode
import com.betterhv.transfer.core.TransferNetworkSecurity
import com.betterhv.transfer.core.TransferObservable
import com.betterhv.transfer.core.TransferPhase
import com.betterhv.transfer.core.TransferProgressMeter
import com.betterhv.transfer.core.TransferSnapshot
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PhoneCommandProcessor(
    context: Context,
    private val queue: PhoneQueueRepository,
    private val inbox: ExportInboxRepository,
    private val pairing: AndroidPairingController,
    private val highBandwidth: HighBandwidthSessionManager,
    private val onQueueChanged: () -> Unit
) : AutoCloseable, TransferObservable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val network = AndroidNetworkInfoProvider(context)
    private val leases = ConcurrentHashMap<UUID, SenderLease>()
    private val startedLeases = ConcurrentHashMap.newKeySet<UUID>()
    private val peerCapabilities = ConcurrentHashMap<String, DeviceCapabilities>()
    private val pushedExports = ConcurrentHashMap<UUID, PushState>()
    private val transferJobs = ConcurrentHashMap<UUID, Job>()
    private val listeningSockets = ConcurrentHashMap<UUID, ServerSocket>()
    private val inboundReplay = BleReplayCache()
    private val mutableSnapshot = MutableStateFlow(TransferSnapshot())
    private val mutableEvents = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)
    override val snapshot: StateFlow<TransferSnapshot> = mutableSnapshot.asStateFlow()
    override val events: SharedFlow<TransferEvent> = mutableEvents.asSharedFlow()

    fun handle(bytes: ByteArray): ByteArray {
        val device = requireNotNull(pairing.pairedDevice) { "尚未配对" }
        val key = requireNotNull(pairing.sharedKey(device.id)) { "配对密钥不可用" }
        val response = runCatching {
            val commandBytes = BleSecureEnvelope.open(key, bytes, inboundReplay)
            dispatch(BleQueueProtocol.decodeCommand(commandBytes), device.id, key)
        }.getOrElse { error ->
            val failure = when (error) {
                is com.betterhv.transfer.core.UnsupportedBleProtocolException -> TransferFailure(
                    TransferErrorCode.UNSUPPORTED_PROTOCOL, error.message.orEmpty(), false
                )
                is TransferChannelException -> error.failure
                else -> TransferFailure(TransferErrorCode.INTERNAL, error.message ?: "命令失败", true)
            }
            mutableSnapshot.value = mutableSnapshot.value.copy(
                phase = TransferPhase.FAILED, lastFailure = failure,
                canRetry = failure.recoverable, canCancel = false
            )
            emitEvent(TransferEvent.Failed(mutableSnapshot.value.operationId, failure))
            BleResponse.Failure(failure)
        }
        return BleSecureEnvelope.seal(key, BleQueueProtocol.encode(response))
    }

    private fun dispatch(command: BleCommand, peerId: String, key: ByteArray): BleResponse = when (command) {
        BleCommand.Counts -> queue.counts().let { BleResponse.Counts(it.first, it.second) }
        is BleCommand.Lease -> queue.leaseNext(command.kind, command.destinationDeviceId)?.let { lease ->
            leases[lease.offer.item.id] = lease
            BleResponse.Offer(lease.offer.item)
        } ?: BleResponse.Empty
        is BleCommand.TextChunk -> {
            val lease = requireLease(command.itemId)
            val content = requireNotNull(lease.text) { "不是文字项目" }.encodeToByteArray()
            require(command.offset in 0..content.size)
            val end = (command.offset + BleQueueProtocol.TEXT_CHUNK_BYTES).coerceAtMost(content.size)
            lease.heartbeat()
            BleResponse.TextChunk(command.itemId, command.offset, content.size, content.copyOfRange(command.offset, end))
        }
        is BleCommand.Heartbeat -> { requireLease(command.itemId).heartbeat(); BleResponse.Ok }
        is BleCommand.Commit -> {
            startedLeases.remove(command.itemId)
            leases.remove(command.itemId)?.commit() ?: queue.delete(command.itemId)
            onQueueChanged()
            phase(command.itemId, TransferPhase.COMPLETE)
            BleResponse.Ok
        }
        is BleCommand.Release -> {
            startedLeases.remove(command.itemId)
            transferJobs.remove(command.itemId)?.cancel()
            leases.remove(command.itemId)?.release()
            onQueueChanged()
            BleResponse.Ok
        }
        is BleCommand.Capabilities -> {
            peerCapabilities[peerId] = command.localCapabilities
            val local = localCapabilities(key)
            val match = TransferNetworkSecurity.compare(local.ssidFingerprint, command.localCapabilities.ssidFingerprint)
            emitEvent(TransferEvent.CapabilityNegotiated(local.modes, command.localCapabilities.modes, match))
            mutableSnapshot.value = mutableSnapshot.value.copy(
                phase = TransferPhase.NEGOTIATING_CAPABILITIES,
                localModes = local.modes,
                remoteModes = command.localCapabilities.modes,
                ssidMatch = match,
                wifiDirectGroupReady = highBandwidth.endpoint() != null
            )
            BleResponse.Capabilities(CapabilityNegotiation(local, match))
        }
        is BleCommand.PrepareFileTransfer -> prepare(command, peerId, key)
        is BleCommand.PushOffer -> when (val begin = inbox.begin(peerId, command.offer)) {
            InboxBeginResult.AlreadyReceived -> BleResponse.AlreadyReceived(command.offer.artifactId)
            is InboxBeginResult.Receive -> {
                pushedExports[command.offer.artifactId] = PushState.Waiting(command.offer, begin.partialFile)
                BleResponse.Ok
            }
        }
        is BleCommand.PushStatus -> when (val state = pushedExports[command.artifactId]) {
            null -> inbox.find(command.artifactId)?.takeIf { it.state == InboxExportState.COMPLETE }
                ?.let { BleResponse.AlreadyReceived(command.artifactId) }
                ?: BleResponse.Failure(TransferFailure(TransferErrorCode.INTERNAL, "接收任务不存在", false))
            is PushState.Waiting, is PushState.Receiving -> BleResponse.Pending
            PushState.Complete -> BleResponse.PushComplete(command.artifactId)
            is PushState.Failed -> BleResponse.Failure(state.failure)
        }
        is BleCommand.PushCancel -> {
            transferJobs.remove(command.artifactId)?.cancel()
            listeningSockets.remove(command.artifactId)?.close()
            pushedExports.remove(command.artifactId)
            inbox.cancel(command.artifactId)
            onQueueChanged()
            BleResponse.Ok
        }
    }

    private fun prepare(command: BleCommand.PrepareFileTransfer, peerId: String, pairingKey: ByteArray): BleResponse {
        val local = localCapabilities(pairingKey)
        val remote = requireNotNull(peerCapabilities[peerId]) { "必须先协商能力" }
        val supported = local.modes and remote.modes and command.selectedMode.bit != 0
        if (!supported) return BleResponse.Failure(TransferFailure(
            TransferErrorCode.UNSUPPORTED_MODE, "双方不支持 ${command.selectedMode}", false, command.selectedMode
        ))
        if (command.selectedMode == TransferMode.LAN &&
            TransferNetworkSecurity.compare(local.ssidFingerprint, remote.ssidFingerprint) != com.betterhv.transfer.core.SsidMatch.MATCH
        ) return BleResponse.Failure(TransferFailure(
            TransferErrorCode.SSID_MISMATCH, "LAN SSID 指纹不匹配", true, TransferMode.LAN
        ))
        val endpoint = runCatching { command.receiverEndpoint.validated() }.getOrElse {
            return BleResponse.Failure(TransferFailure(TransferErrorCode.INVALID_ENDPOINT, it.message.orEmpty(), false))
        }
        transferJobs.remove(command.itemId)?.cancel()
        listeningSockets.remove(command.itemId)?.close()
        val fileKey = TransferNetworkSecurity.sessionKey(pairingKey, command.itemId, command.sessionNonce, "file")
        val probeKey = TransferNetworkSecurity.sessionKey(pairingKey, command.itemId, command.sessionNonce, "probe")
        return when {
            leases.containsKey(command.itemId) -> prepareSend(command, endpoint, fileKey, probeKey)
            pushedExports[command.itemId] is PushState.Waiting -> prepareReceive(command, endpoint, fileKey, probeKey)
            else -> BleResponse.Failure(TransferFailure(TransferErrorCode.INTERNAL, "传输项目不存在", false))
        }
    }

    private fun prepareSend(
        command: BleCommand.PrepareFileTransfer,
        endpoint: com.betterhv.transfer.core.NetworkEndpoint,
        fileKey: ByteArray,
        probeKey: ByteArray
    ): BleResponse {
        val lease = requireLease(command.itemId)
        val source = requireNotNull(lease.payloadFile) { "不是图片项目" }
        if (command.selectedMode == TransferMode.LAN) {
            phase(command.itemId, TransferPhase.PROBING_LAN)
            runCatching { EncryptedFileTransfer.probe(endpoint.host, command.itemId, probeKey) }
                .onFailure { fail(command.itemId, it) }
                .getOrElse { error ->
                    return BleResponse.Failure(failure(error, TransferMode.LAN))
                }
        }
        if (startedLeases.add(command.itemId)) lease.markTransferring()
        begin(command.itemId, lease.offer.item.byteLength, command.selectedMode, endpoint)
        transferJobs[command.itemId] = scope.launch {
            try {
                val meter = TransferProgressMeter(System.currentTimeMillis())
                EncryptedFileTransfer.send(endpoint.host, command.itemId, source, fileKey) { done, total ->
                    progress(command.itemId, done, total, meter)
                }
                lease.markAwaitingCommit()
                phase(command.itemId, TransferPhase.AWAITING_COMMIT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(command.itemId, error)
            } finally {
                transferJobs.remove(command.itemId)
            }
        }
        return BleResponse.Prepared(command.itemId, command.selectedMode, endpoint)
    }

    private fun prepareReceive(
        command: BleCommand.PrepareFileTransfer,
        endpoint: com.betterhv.transfer.core.NetworkEndpoint,
        fileKey: ByteArray,
        probeKey: ByteArray
    ): BleResponse {
        val waiting = pushedExports[command.itemId] as PushState.Waiting
        val listening = CountDownLatch(1)
        begin(command.itemId, waiting.offer.byteLength, command.selectedMode, endpoint)
        pushedExports[command.itemId] = PushState.Receiving(waiting.offer, waiting.partial)
        transferJobs[command.itemId] = scope.launch {
            try {
                val meter = TransferProgressMeter(System.currentTimeMillis())
                val received = EncryptedFileTransfer.receive(
                    waiting.partial, command.itemId, fileKey,
                    probeKey = if (command.selectedMode == TransferMode.LAN) probeKey else null,
                    progress = { done, total -> progress(command.itemId, done, total, meter) },
                    onListening = { server -> listeningSockets[command.itemId] = server; listening.countDown() }
                )
                phase(command.itemId, TransferPhase.VERIFYING)
                require(received.byteLength == waiting.offer.byteLength && received.sha256.contentEquals(waiting.offer.sha256)) {
                    "导出文件长度或校验值不一致"
                }
                inbox.complete(command.itemId, waiting.partial)
                pushedExports[command.itemId] = PushState.Complete
                onQueueChanged()
                phase(command.itemId, TransferPhase.COMPLETE)
                emitEvent(TransferEvent.Completed(command.itemId, command.itemId))
            } catch (cancelled: CancellationException) {
                pushedExports.remove(command.itemId)
                throw cancelled
            } catch (error: Throwable) {
                val failure = failure(error, command.selectedMode)
                inbox.fail(command.itemId, failure.message)
                pushedExports[command.itemId] = PushState.Failed(failure)
                fail(command.itemId, error)
            } finally {
                listeningSockets.remove(command.itemId)
                transferJobs.remove(command.itemId)
                listening.countDown()
            }
        }
        if (!listening.await(2, TimeUnit.SECONDS)) {
            transferJobs.remove(command.itemId)?.cancel()
            return BleResponse.Failure(TransferFailure(
                TransferErrorCode.CONNECTION_TIMEOUT, "接收端口未能及时启动", true, command.selectedMode
            ))
        }
        return BleResponse.Prepared(command.itemId, command.selectedMode, endpoint)
    }

    private fun localCapabilities(key: ByteArray) = network.capabilities(
        key,
        highBandwidth.endpoint(),
        BleQueueProtocol.CAPABILITY_EXPORT_PUSH
    )

    private fun begin(id: UUID, total: Long, mode: TransferMode, endpoint: com.betterhv.transfer.core.NetworkEndpoint) {
        val now = System.currentTimeMillis()
        mutableSnapshot.value = TransferSnapshot(
            operationId = id, itemId = id, deviceId = pairing.pairedDevice?.id,
            phase = TransferPhase.WAITING_FOR_PEER, mode = mode,
            localModes = mutableSnapshot.value.localModes, remoteModes = mutableSnapshot.value.remoteModes,
            ssidMatch = mutableSnapshot.value.ssidMatch, endpoint = endpoint,
            totalBytes = total, attempt = 1, startedAtMillis = now, phaseStartedAtMillis = now,
            canCancel = true, wifiDirectGroupReady = highBandwidth.endpoint() != null
        )
        emitEvent(TransferEvent.TransportSelected(id, mode, endpoint))
    }

    private fun progress(id: UUID, done: Long, total: Long, meter: TransferProgressMeter) {
        val sample = meter.sample(done, total)
        mutableSnapshot.value = mutableSnapshot.value.copy(
            phase = TransferPhase.TRANSFERRING, bytesTransferred = done, totalBytes = total,
            bytesPerSecond = sample.bytesPerSecond, averageBytesPerSecond = sample.averageBytesPerSecond,
            etaMillis = sample.etaMillis, canCancel = true
        )
        emitEvent(TransferEvent.Progress(id, done, total, sample.bytesPerSecond, sample.etaMillis))
    }

    private fun phase(id: UUID, next: TransferPhase) {
        val old = mutableSnapshot.value.phase
        mutableSnapshot.value = mutableSnapshot.value.copy(
            operationId = id, phase = next, phaseStartedAtMillis = System.currentTimeMillis(),
            canCancel = next !in setOf(TransferPhase.IDLE, TransferPhase.COMPLETE, TransferPhase.FAILED)
        )
        emitEvent(TransferEvent.PhaseChanged(id, old, next))
    }

    private fun fail(id: UUID, error: Throwable) {
        val failure = failure(error, mutableSnapshot.value.mode)
        mutableSnapshot.value = mutableSnapshot.value.copy(
            operationId = id, phase = TransferPhase.FAILED, lastFailure = failure,
            canRetry = failure.recoverable, canCancel = false
        )
        emitEvent(TransferEvent.Failed(id, failure))
        Log.e(TAG, "Transfer failed $id: ${failure.code}", error)
    }

    private fun failure(error: Throwable, mode: TransferMode?): TransferFailure =
        (error as? TransferChannelException)?.failure ?: TransferFailure(
            TransferErrorCode.INTERNAL, error.message ?: "传输失败", true, mode
        )

    private fun requireLease(id: UUID) = requireNotNull(leases[id]) { "租约不存在" }

    override fun cancel() {
        snapshot.value.operationId?.let { id ->
            transferJobs.remove(id)?.cancel()
            listeningSockets.remove(id)?.close()
            phase(id, TransferPhase.FAILED)
        }
    }

    private fun emitEvent(event: TransferEvent) {
        if (!mutableEvents.tryEmit(event)) Log.w(TAG, "Transfer event buffer full: ${event.javaClass.simpleName}")
    }

    override fun close() {
        cancel()
        leases.values.forEach { runCatching { it.release() } }
        leases.clear()
        startedLeases.clear()
        transferJobs.values.forEach(Job::cancel)
        transferJobs.clear()
        listeningSockets.values.forEach { runCatching { it.close() } }
        listeningSockets.clear()
        scope.cancel()
    }

    private sealed interface PushState {
        data class Waiting(val offer: com.betterhv.transfer.core.ExportTransferOffer, val partial: File) : PushState
        data class Receiving(val offer: com.betterhv.transfer.core.ExportTransferOffer, val partial: File) : PushState
        data object Complete : PushState
        data class Failed(val failure: TransferFailure) : PushState
    }

    private companion object { const val TAG = "BetterHvTransferV2" }
}
