package com.betterhv.note.sender.desktop

import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.BleCommand
import com.betterhv.transfer.core.BleQueueProtocol
import com.betterhv.transfer.core.BleResponse
import com.betterhv.transfer.windows.WindowsCapabilities
import com.betterhv.transfer.windows.WindowsFileTransfer
import com.betterhv.transfer.windows.WindowsNativeApi
import com.betterhv.transfer.windows.WindowsReplayCache
import com.betterhv.transfer.windows.WindowsSecureEnvelope
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DesktopTransferStatus(
    val running: Boolean = false,
    val capabilities: WindowsCapabilities? = null,
    val summary: String = "正在检查设备",
    val detail: String = ""
)

class DesktopTransferService(
    private val native: WindowsNativeApi,
    private val settings: DesktopSettings,
    private val queue: DesktopQueueRepository,
    private val inbox: DesktopInboxRepository,
    private val onPairingChanged: () -> Unit = {}
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val leases = ConcurrentHashMap<UUID, DesktopSenderLease>()
    private val replay = WindowsReplayCache()
    private val transferActive = AtomicBoolean(false)
    private val mutableStatus = MutableStateFlow(DesktopTransferStatus())
    val status: StateFlow<DesktopTransferStatus> = mutableStatus
    private var pollJob: Job? = null
    private var transferJob: Job? = null
    private var prewarmJob: Job? = null
    @Volatile private var listeningSocket: ServerSocket? = null
    @Volatile private var hosted: HostedGroup? = null
    private val pushed = ConcurrentHashMap<UUID, PushState>()

    @Synchronized fun start() {
        if (pollJob?.isActive == true) return
        DesktopLog.info("service.start")
        val version = windowsVersion()
        if (!version.supported) {
            DesktopLog.info("service.unsupported", version.message)
            mutableStatus.value = DesktopTransferStatus(summary = "不支持此 Windows 版本", detail = version.message)
            return
        }
        val capabilities = runCatching { native.capabilities() }.getOrElse {
            DesktopLog.error("capabilities", it)
            mutableStatus.value = DesktopTransferStatus(summary = "无线服务不可用", detail = it.message.orEmpty())
            return
        }
        DesktopLog.info(
            "capabilities",
            "blePeripheral=${capabilities.blePeripheral} wifiDirect=${capabilities.wifiDirect} dpapi=${capabilities.dataProtection}"
        )
        if (!capabilities.ready) {
            val missing = buildList {
                if (!capabilities.blePeripheral) add("BLE 外设模式")
                if (!capabilities.wifiDirect) add("Wi-Fi Direct")
                if (!capabilities.dataProtection) add("Windows 数据保护")
            }
            mutableStatus.value = DesktopTransferStatus(false, capabilities, "设备能力不足", missing.joinToString("、"))
            return
        }
        runCatching {
            val suffix = SecureRandom().nextInt(0x10000).toString(16).padStart(4, '0')
            native.startWifiDirect("DIRECT-BH-check-$suffix", "BHv-check-${UUID.randomUUID().toString().take(8)}")
            native.stopWifiDirect()
            DesktopLog.info("wifi.probe", "ok")
        }.onFailure {
            DesktopLog.error("wifi.probe", it)
            runCatching { native.stopWifiDirect() }
            mutableStatus.value = DesktopTransferStatus(
                false, capabilities, "Wi-Fi Direct 不可用",
                "${it.message.orEmpty()}；请打开无线适配器并关闭移动热点"
            )
            return
        }
        val counts = queue.counts()
        runCatching { native.startBle(settings.localDeviceId, settings.displayName, counts.first, counts.second) }
            .onFailure {
                DesktopLog.error("ble.start", it)
                mutableStatus.value = DesktopTransferStatus(false, capabilities, "BLE 启动失败", radioHint(it.message))
                return
            }
        DesktopLog.info(
            "ble.start",
            "deviceId=${settings.localDeviceId.take(8)} name=${settings.displayName} images=${counts.first} texts=${counts.second}"
        )
        mutableStatus.value = DesktopTransferStatus(
            true,
            capabilities,
            if (settings.pairing == null) "生成配对码后在 Note 上输入"
            else if (counts.first + counts.second > 0) "已就绪，正在等待 Note 获取"
            else "已就绪，暂无待发送内容",
            ""
        )
        pollJob = scope.launch {
            while (isActive) {
                runCatching {
                    native.pollBleCommand(1_000)?.let { envelope ->
                        DesktopLog.info("ble.command.received", "bytes=${envelope.size}")
                        processCommand(envelope)
                    }
                }
                    .onFailure {
                        if (isActive) {
                            DesktopLog.error("ble.command", it)
                            mutableStatus.value = mutableStatus.value.copy(summary = "BLE 通信错误", detail = it.message.orEmpty())
                            delay(500)
                        }
                    }
            }
        }
        prewarmWifiForImages(counts.first)
    }

    @Synchronized fun stop() {
        DesktopLog.info("service.stop")
        pollJob?.cancel(); pollJob = null
        prewarmJob?.cancel(); prewarmJob = null
        cancelTransfer()
        leases.values.forEach { runCatching { it.release() } }
        leases.clear()
        runCatching { native.stopBle() }
        mutableStatus.value = mutableStatus.value.copy(running = false, summary = "接收已暂停", detail = "")
    }

    @Synchronized fun restart() { stop(); start() }

    fun refreshCounts() {
        if (!mutableStatus.value.running) return
        val counts = queue.counts()
        runCatching { native.updateBleCounts(counts.first, counts.second) }
            .onSuccess {
                DesktopLog.info("ble.counts", "images=${counts.first} texts=${counts.second}")
                prewarmWifiForImages(counts.first)
            }
            .onFailure { mutableStatus.value = mutableStatus.value.copy(detail = it.message.orEmpty()) }
    }

    private fun processCommand(envelope: ByteArray) {
        val pairing = settings.pairing ?: settings.ownerPairing
        val provisionalPairing = settings.pairing == null
        val response = runCatching {
            val bytes = WindowsSecureEnvelope.open(pairing.sharedKey, envelope, replay)
            val command = BleQueueProtocol.decodeCommand(bytes)
            DesktopLog.info(
                "ble.command.authenticated",
                "type=${command.javaClass.simpleName} provisionalPairing=$provisionalPairing"
            )
            handle(command, pairing.deviceId, pairing.sharedKey).also {
                if (settings.pairing == null) {
                    settings.completeOwnerPairing()
                    DesktopLog.info("pairing.completed", "peerId=${pairing.deviceId}")
                    onPairingChanged()
                    mutableStatus.value = mutableStatus.value.copy(
                        summary = if (queue.counts().let { it.first + it.second } > 0) "已就绪，正在等待 Note 获取"
                        else "已就绪，暂无待发送内容",
                        detail = ""
                    )
                }
            }
        }.getOrElse {
            DesktopLog.error("ble.command.authenticate", it)
            BleResponse.Error(it.message ?: "命令失败")
        }
        val sealed = WindowsSecureEnvelope.seal(pairing.sharedKey, BleQueueProtocol.encode(response))
        native.respondBle(sealed)
        DesktopLog.info("ble.response.sent", "type=${response.javaClass.simpleName} bytes=${sealed.size}")
    }

    private fun handle(command: BleCommand, peerId: String, key: ByteArray): BleResponse = when (command) {
        BleCommand.Counts -> queue.counts().let { BleResponse.Counts(it.first, it.second) }
        is BleCommand.Lease -> queue.leaseNext(command.kind, command.destinationDeviceId)?.let {
            leases[it.item.id] = it
            BleResponse.Offer(it.item)
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
            leases.remove(command.itemId)?.commit() ?: queue.delete(command.itemId)
            finishHosted(command.itemId); refreshCounts(); BleResponse.Ok
        }
        is BleCommand.Release -> {
            leases.remove(command.itemId)?.release(); finishHosted(command.itemId); refreshCounts(); BleResponse.Ok
        }
        is BleCommand.WifiHost -> {
            requireLease(command.itemId)
            prepareHosted(command.itemId)
            BleResponse.Ok
        }
        is BleCommand.WifiHostStatus -> hosted?.takeIf { it.id == command.itemId }?.let(::ownerResponse)
            ?: BleResponse.Pending
        is BleCommand.WifiSendTo -> {
            val lease = requireLease(command.itemId)
            val file = requireNotNull(lease.file) { "不是图片项目" }
            require(hosted?.id == command.itemId) { "Wi-Fi Direct 组尚未就绪" }
            require(transferActive.compareAndSet(false, true)) { "已有传输任务正在进行" }
            lease.markTransferring()
            transferJob = scope.launch {
                try {
                    WindowsFileTransfer.send(command.receiverIp, command.itemId, file, key)
                    lease.markAwaitingCommit()
                } catch (_: CancellationException) {
                    lease.release()
                } catch (error: Throwable) {
                    lease.release()
                    mutableStatus.value = mutableStatus.value.copy(summary = "文件发送失败", detail = error.message.orEmpty())
                } finally {
                    transferActive.set(false)
                    finishHosted(command.itemId)
                    refreshCounts()
                }
            }
            BleResponse.Ok
        }
        is BleCommand.WifiSend -> BleResponse.Error("Windows NoteLink 仅使用自主 Wi-Fi Direct 组")
        BleCommand.Capabilities -> BleResponse.Capabilities(BleQueueProtocol.CAPABILITY_EXPORT_PUSH)
        is BleCommand.PushOffer -> when (val begin = inbox.begin(peerId, command.offer)) {
            DesktopInboxBegin.AlreadyReceived -> BleResponse.AlreadyReceived(command.offer.artifactId)
            is DesktopInboxBegin.Receive -> {
                if (pushed.putIfAbsent(command.offer.artifactId, PushState.Preparing) == null) {
                    try {
                        startPush(command.offer, begin.partial, key)
                    } catch (error: Throwable) {
                        pushed.remove(command.offer.artifactId)
                        inbox.fail(command.offer.artifactId, error.message ?: "接收导出失败")
                        throw error
                    }
                }
                BleResponse.Ok
            }
        }
        is BleCommand.PushStatus -> when (val state = pushed[command.artifactId]) {
            null -> inbox.find(command.artifactId)?.takeIf { it.state == DesktopInboxState.COMPLETE }
                ?.let { BleResponse.AlreadyReceived(command.artifactId) }
                ?: BleResponse.Error("接收任务不存在")
            PushState.Preparing -> BleResponse.Pending
            is PushState.Ready -> ownerResponse(state.group)
            PushState.Complete -> BleResponse.PushComplete(command.artifactId)
            is PushState.Failed -> BleResponse.Error(state.message)
        }
        is BleCommand.PushCancel -> {
            cancelTransfer()
            pushed.remove(command.artifactId)
            inbox.find(command.artifactId)?.takeIf { it.state != DesktopInboxState.COMPLETE }?.let { inbox.delete(command.artifactId) }
            BleResponse.Ok
        }
    }

    private fun startPush(offer: com.betterhv.transfer.core.ExportTransferOffer, partial: java.io.File, key: ByteArray) {
        require(transferActive.compareAndSet(false, true)) { "已有传输任务正在进行" }
        transferJob = scope.launch {
            try {
                val group = createGroup(offer.artifactId)
                pushed[offer.artifactId] = PushState.Ready(group)
                val received = WindowsFileTransfer.receive(partial, offer.artifactId, key, onListening = { listeningSocket = it })
                require(received.byteLength == offer.byteLength && received.sha256.contentEquals(offer.sha256))
                inbox.complete(offer.artifactId, partial)
                pushed[offer.artifactId] = PushState.Complete
            } catch (_: CancellationException) {
                pushed.remove(offer.artifactId)
            } catch (error: Throwable) {
                val message = error.message ?: "接收导出失败"
                inbox.fail(offer.artifactId, message)
                pushed[offer.artifactId] = PushState.Failed(message)
            } finally {
                listeningSocket = null
                transferActive.set(false)
                finishHosted(offer.artifactId)
            }
        }
    }

    @Synchronized private fun prepareHosted(id: UUID): HostedGroup {
        hosted?.let {
            require(it.id == null || it.id == id) { "另一个 Wi-Fi Direct 传输正在准备" }
            it.id = id
            DesktopLog.info("wifi.group.reused", "transferId=$id network=${it.networkName}")
            return it
        }
        require(!transferActive.get()) { "已有传输任务正在进行" }
        return createGroup(id)
    }

    @Synchronized private fun createGroup(id: UUID?): HostedGroup {
        hosted?.let { runCatching { native.stopWifiDirect() } }
        val random = SecureRandom()
        val network = "DIRECT-BH-${random.nextInt(0x10000).toString(16).padStart(4, '0')}"
        val passphrase = "BHv${UUID.randomUUID().toString().replace("-", "").take(13)}"
        val ip = native.startWifiDirect(network, passphrase)
        DesktopLog.info("wifi.group.started", "transferId=${id ?: "warm"} network=$network ownerIp=$ip")
        return HostedGroup(id, network, passphrase, ip).also { hosted = it }
    }

    @Synchronized private fun prewarmWifiForImages(imageCount: Int) {
        if (imageCount <= 0 || hosted != null || prewarmJob?.isActive == true) return
        prewarmJob = scope.launch {
            runCatching {
                synchronized(this@DesktopTransferService) {
                    if (mutableStatus.value.running && hosted == null && !transferActive.get()) createGroup(null)
                }
            }.onFailure { error ->
                if (isActive) DesktopLog.error("wifi.prewarm", error)
            }
        }
    }

    @Synchronized private fun finishHosted(id: UUID?) {
        if (hosted?.id != id) return
        runCatching { native.stopWifiDirect() }
        DesktopLog.info("wifi.group.stopped", "transferId=$id")
        hosted = null
    }

    private fun ownerResponse(group: HostedGroup) = BleResponse.WifiOwnerInfo(
        deviceAddress = "",
        deviceName = settings.displayName,
        ownerIp = group.ownerIp,
        networkName = group.networkName,
        passphrase = group.passphrase
    )

    private fun requireLease(id: UUID) = requireNotNull(leases[id]) { "租约不存在" }

    @Synchronized private fun cancelTransfer() {
        runCatching { listeningSocket?.close() }
        listeningSocket = null
        transferJob?.cancel(); transferJob = null
        transferActive.set(false)
        hosted?.let { finishHosted(it.id) }
    }

    override fun close() {
        stop()
        scope.cancel()
        native.close()
    }

    private data class HostedGroup(var id: UUID?, val networkName: String, val passphrase: String, val ownerIp: String)
    private sealed interface PushState {
        data object Preparing : PushState
        data class Ready(val group: HostedGroup) : PushState
        data object Complete : PushState
        data class Failed(val message: String) : PushState
    }

    private data class WindowsVersion(val supported: Boolean, val message: String)
    private fun windowsVersion(): WindowsVersion {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            return WindowsVersion(false, "仅支持 Windows 10 1903+ 和 Windows 11 x64")
        }
        val architecture = System.getProperty("os.arch")
        if (architecture != "amd64" && architecture != "x86_64") return WindowsVersion(false, "仅支持 x64")
        val version = System.getProperty("os.version").split('.').mapNotNull(String::toIntOrNull)
        if ((version.firstOrNull() ?: 0) < 10) return WindowsVersion(false, "需要 Windows 10 1903 或更高版本")
        return WindowsVersion(true, "")
    }

    private fun radioHint(message: String?): String {
        val detail = message.orEmpty()
        return if (detail.contains("Wi-Fi Direct", true)) "$detail；请关闭移动热点并检查无线开关" else detail
    }
}
