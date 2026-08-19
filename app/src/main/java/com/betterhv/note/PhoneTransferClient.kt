package com.betterhv.note

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.betterhv.transfer.android.AndroidPairingController
import com.betterhv.transfer.android.BleGattSession
import com.betterhv.transfer.android.BleIdentity
import com.betterhv.transfer.android.BleReceiverScanner
import com.betterhv.transfer.android.BleReplayCache
import com.betterhv.transfer.android.BleSecureEnvelope
import com.betterhv.transfer.android.EncryptedFileTransfer
import com.betterhv.transfer.android.DiscoveredSender
import com.betterhv.transfer.android.ReceivedLease
import com.betterhv.transfer.android.WifiDirectController
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.BleCommand
import com.betterhv.transfer.core.BleQueueProtocol
import com.betterhv.transfer.core.BleResponse
import com.betterhv.transfer.core.ExportTransferOffer
import com.betterhv.transfer.core.RemotePayload
import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.core.TransferOffer
import com.betterhv.transfer.core.TransferState
import com.betterhv.transfer.core.PairedDevice
import com.betterhv.note.export.ExportArtifact
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class PhoneTransferClient(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    val pairing = AndroidPairingController(appContext)
    private val mutableState = MutableStateFlow<TransferState>(TransferState.Idle)
    @Volatile private var inboundReplay = BleReplayCache()
    private val operationActive = AtomicBoolean(false)
    private val activeBleSessions = Collections.synchronizedSet(mutableSetOf<BleGattSession>())
    private val recentSenders = ConcurrentHashMap<String, RecentSender>()
    val state: StateFlow<TransferState> = mutableState

    data class AvailableNoteLink(
        val client: PairedDevice,
        val advertisedName: String,
        val imageCount: Int,
        val textCount: Int
    )

    data class PairingCandidate(
        val deviceId: String,
        val name: String,
        val identityHash: String,
        val bluetoothAddress: String
    )

    val pairedClients: List<PairedDevice> get() = pairing.pairedClients

    /** Uses the sender advertisement counts without leasing an item. */
    suspend fun hasAvailable(kind: ContentKind): Boolean = withContext(Dispatchers.IO) {
        discoverAvailable(kind, 5_000L).isNotEmpty()
    }

    suspend fun discoverAvailable(
        kind: ContentKind? = null,
        timeoutMillis: Long = 5_000L,
        onUpdate: (List<AvailableNoteLink>) -> Unit = {}
    ): List<AvailableNoteLink> = withContext(Dispatchers.IO) {
        val clients = pairing.pairedClients
        if (clients.isEmpty()) return@withContext emptyList()
        val senders = runCatching {
            newScanner().discover(timeoutMillis, onUpdate = { partial ->
                onUpdate(matchAvailable(clients, partial, kind))
            })
        }.getOrElse { return@withContext emptyList() }
            .mapNotNull { resolveAdvertisementIdentity(it) }
        matchAvailable(clients, senders, kind).also(onUpdate)
    }

    private fun matchAvailable(
        clients: List<PairedDevice>,
        senders: List<DiscoveredSender>,
        kind: ContentKind?
    ): List<AvailableNoteLink> =
        clients.mapNotNull { client ->
            val sender = senders.firstOrNull { candidate ->
                val hasContent = when (kind) {
                    ContentKind.IMAGE -> candidate.imageCount > 0
                    ContentKind.TEXT -> candidate.textCount > 0
                    null -> true
                }
                hasContent && if (client.legacy) {
                    candidate.name == client.name || clients.count(PairedDevice::legacy) == 1 && senders.size == 1
                } else candidate.identityHash.equals(client.identityHash, ignoreCase = true)
            } ?: return@mapNotNull null
            recentSenders[client.id] = RecentSender(sender, SystemClock.elapsedRealtime())
            AvailableNoteLink(client, sender.name, sender.imageCount, sender.textCount)
        }.sortedByDescending { it.client.lastUsedAt }

    suspend fun discoverPairingCandidates(timeoutMillis: Long = 8_000L): List<PairingCandidate> =
        withContext(Dispatchers.IO) {
            val pairedIds = pairing.pairedClients.map(PairedDevice::id).toSet()
            newScanner().discover(timeoutMillis).mapNotNull { sender ->
                runCatching {
                    withBleSession(sender.bluetoothAddress) { session ->
                        val identity = session.readIdentity()
                        val hash = AndroidPairingController.identityHash(identity.deviceId)
                        require(sender.identityHash.isBlank() || hash.equals(sender.identityHash, ignoreCase = true)) {
                            "NoteLink 广播身份与完整身份不一致"
                        }
                        PairingCandidate(identity.deviceId, identity.deviceName, hash, sender.bluetoothAddress)
                    }
                }.getOrNull()?.takeUnless { it.deviceId in pairedIds }
            }.distinctBy(PairingCandidate::deviceId).sortedBy(PairingCandidate::name)
        }

    /** Manual codes are not considered paired until an encrypted round trip succeeds. */
    suspend fun pair(candidate: PairingCandidate, sixDigitCode: String): PairedDevice =
        withContext(Dispatchers.IO) {
            val paired = pairing.confirmManual(candidate.deviceId, candidate.name, sixDigitCode)
            try {
                withBleSession(candidate.bluetoothAddress) { session ->
                    val identity = session.readIdentity()
                    require(identity.deviceId == candidate.deviceId) { "NoteLink 身份在配对期间发生变化" }
                    require(
                        AndroidPairingController.identityHash(identity.deviceId)
                            .equals(candidate.identityHash, ignoreCase = true)
                    ) { "NoteLink 广播身份与完整身份不一致" }
                    require(exchange(session, paired.id, BleCommand.Capabilities) is BleResponse.Capabilities) {
                        "NoteLink 未确认配对"
                    }
                }
                pairing.markLastUsed(paired.id)
            } catch (t: Throwable) {
                pairing.unpair(paired.id)
                throw IllegalStateException("配对握手失败：${t.message ?: t.javaClass.simpleName}", t)
            }
        }

    init {
        pairing.pairedDevice?.takeIf {
            it.id == "betterhv-phone" && it.name == "BetterHv Send"
        }?.let { pairing.updatePairedDeviceName("NoteLink") }
    }

    suspend fun requestNext(kind: ContentKind): ReceivedLease? {
        val paired = pairing.pairedDevice ?: error("请先在设置中配对 NoteLink")
        return requestNext(paired.id, kind)
    }

    suspend fun requestNext(clientId: String, kind: ContentKind): ReceivedLease? = withContext(Dispatchers.IO) {
        check(operationActive.compareAndSet(false, true)) { "已有 NoteLink 传输正在进行" }
        var keepActive = false
        val paired = pairing.pairedClient(clientId) ?: run {
            operationActive.set(false)
            error("配对的 NoteLink 不存在")
        }
        mutableState.value = TransferState.Scanning
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
            val identity = verifyIdentity(activeSession, sender, paired)
            exchange(activeSession, authenticatedId, BleCommand.Capabilities)
            if (paired.legacy) {
                authenticatedId = pairing.resolveLegacyIdentity(paired.id, identity).id
            }
            pairing.markLastUsed(authenticatedId)
            val response = exchange(activeSession, authenticatedId, BleCommand.Lease(kind, "betterhv-note"))
            rememberAuthenticatedSenderName(authenticatedId, identity.deviceName)
            val offer = (response as? BleResponse.Offer)?.item
                ?: if (response == BleResponse.Empty) {
                    closeBleSession(activeSession)
                    mutableState.value = TransferState.Idle
                    return@withContext null
                } else error("手机返回了无效队列响应")
            leasedItemId = offer.id
            val payload = when (kind) {
                ContentKind.TEXT -> receiveText(activeSession, authenticatedId, offer)
                ContentKind.IMAGE -> receiveImage(activeSession, offer, authenticatedId)
            }
            Log.i(TAG, "${kind.name.lowercase()} payload ready after ${elapsed(startedAt)} ms")
            mutableState.value = TransferState.AwaitingPlacement(offer.id)
            keepActive = true
            NoteReceivedLease(activeSession, authenticatedId, TransferOffer(offer), payload) {
                mutableState.value = TransferState.Idle
                operationActive.set(false)
            }
        } catch (t: Throwable) {
            val failedSession = session
            leasedItemId?.let { itemId ->
                if (failedSession != null) runCatching { exchange(failedSession, authenticatedId, BleCommand.Release(itemId)) }
            }
            failedSession?.let(::closeBleSession)
            mutableState.value = TransferState.Error(t.message ?: "手机传输失败")
            throw t
        } finally {
            if (!keepActive) operationActive.set(false)
        }
    }

    suspend fun sendExport(
        artifact: ExportArtifact,
        progress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        val paired = pairing.pairedDevice ?: error("请先在设置中配对 NoteLink")
        sendExport(paired.id, artifact, progress)
    }

    suspend fun sendExport(
        clientId: String,
        artifact: ExportArtifact,
        progress: (Long, Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        check(operationActive.compareAndSet(false, true)) { "已有 NoteLink 传输正在进行" }
        val paired = pairing.pairedClient(clientId) ?: run {
            operationActive.set(false)
            error("配对的 NoteLink 不存在")
        }
        require(artifact.byteLength <= com.betterhv.transfer.core.TransferLimits.MAX_EXPORT_BYTES) {
            "导出文件超过 512 MiB"
        }
        mutableState.value = TransferState.Scanning
        try {
        val sender = findSender(paired, null, 8_000L)
            ?: error("未发现 ${paired.name}；请确认 NoteLink 正在运行")
        withBleSession(sender.bluetoothAddress) { session ->
            val identity = verifyIdentity(session, sender, paired)
            var authenticatedId = paired.id
            val capabilities = exchange(session, authenticatedId, BleCommand.Capabilities) as? BleResponse.Capabilities
                ?: error("NoteLink 版本过旧，请升级后重试")
            if (paired.legacy) authenticatedId = pairing.resolveLegacyIdentity(paired.id, identity).id
            pairing.markLastUsed(authenticatedId)
            rememberAuthenticatedSenderName(authenticatedId, identity.deviceName)
            require(capabilities.flags and BleQueueProtocol.CAPABILITY_EXPORT_PUSH != 0) {
                "NoteLink 版本不支持接收导出"
            }
            val offer = ExportTransferOffer(
                artifact.id, artifact.displayName, artifact.mimeType, artifact.byteLength, artifact.sha256
            )
            when (val response = exchange(session, authenticatedId, BleCommand.PushOffer(offer))) {
                is BleResponse.AlreadyReceived -> {
                    mutableState.value = TransferState.Idle
                    return@withBleSession
                }
                BleResponse.Ok -> Unit
                is BleResponse.Error -> error(response.message)
                else -> error("NoteLink 未接受导出文件")
            }
            try {
                val owner = withTimeout(45_000L) {
                    while (true) {
                        when (val response = exchange(session, authenticatedId, BleCommand.PushStatus(artifact.id))) {
                            is BleResponse.WifiOwnerInfo -> return@withTimeout response
                            is BleResponse.AlreadyReceived, is BleResponse.PushComplete -> return@withTimeout null
                            BleResponse.Pending -> delay(250L)
                            is BleResponse.Error -> error(response.message)
                            else -> error("NoteLink 返回了无效接收状态")
                        }
                    }
                    error("unreachable")
                }
                if (owner != null) {
                    val key = requireNotNull(pairing.sharedKey(authenticatedId)) { "配对密钥不可用" }
                    WifiDirectController(appContext).use { wifi ->
                        try {
                            wifi.connect(owner.deviceAddress, owner.deviceName, owner.networkName, owner.passphrase)
                            EncryptedFileTransfer.send(owner.ownerIp, artifact.id, artifact.file, key) { done, total ->
                                mutableState.value = TransferState.Transferring(artifact.id, done, total)
                                progress(done, total)
                            }
                        } finally {
                            wifi.removeGroup()
                        }
                    }
                    withTimeout(30_000L) {
                        while (true) {
                            when (val response = exchange(session, authenticatedId, BleCommand.PushStatus(artifact.id))) {
                                is BleResponse.PushComplete, is BleResponse.AlreadyReceived -> return@withTimeout
                                BleResponse.Pending, is BleResponse.WifiOwnerInfo -> delay(250L)
                                is BleResponse.Error -> error(response.message)
                                else -> error("NoteLink 未确认导出文件")
                            }
                        }
                    }
                }
                mutableState.value = TransferState.Idle
            } catch (t: Throwable) {
                runCatching { exchange(session, authenticatedId, BleCommand.PushCancel(artifact.id)) }
                mutableState.value = TransferState.Error(t.message ?: "发送导出失败")
                throw t
            }
        }
        } finally {
            operationActive.set(false)
        }
    }

    private suspend fun receiveText(
        session: BleGattSession,
        clientId: String,
        item: com.betterhv.transfer.core.QueueItem
    ): RemotePayload.Text {
        val output = java.io.ByteArrayOutputStream(item.byteLength.toInt())
        var offset = 0
        while (offset < item.byteLength) {
            val chunk = exchange(session, clientId, BleCommand.TextChunk(item.id, offset)) as? BleResponse.TextChunk
                ?: error("手机文字分片响应无效")
            require(chunk.itemId == item.id && chunk.offset == offset && chunk.total.toLong() == item.byteLength)
            output.write(chunk.bytes); offset += chunk.bytes.size
        }
        val bytes = output.toByteArray()
        require(MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(item.sha256)) { "文字校验失败" }
        return RemotePayload.Text(item, bytes.decodeToString())
    }

    private suspend fun receiveImage(
        session: BleGattSession,
        item: com.betterhv.transfer.core.QueueItem,
        pairedPhoneId: String
    ): RemotePayload.Image = coroutineScope {
        val key = requireNotNull(pairing.sharedKey(pairedPhoneId)) { "配对密钥不可用" }
        val incoming = File(appContext.filesDir, "documents/incoming/remote-${item.id}.part")
        if (Build.MODEL.equals("N10Pro", ignoreCase = true)) {
            return@coroutineScope receiveImageFromPhoneOwner(session, pairedPhoneId, item, key, incoming)
        }
        WifiDirectController(appContext).use { wifi ->
            try {
                val p2p = wifi.createGroup()
                val ownerMac = requireNotNull(p2p.localDeviceAddress) { "无法读取 Note 的 Wi-Fi Direct 地址" }
                val ownerName = requireNotNull(p2p.ownerDeviceName) { "无法读取 Note 的 Wi-Fi Direct 名称" }
                val receiver = async(Dispatchers.IO) {
                    EncryptedFileTransfer.receive(incoming, item.id, key) { done, total ->
                        mutableState.value = TransferState.Transferring(item.id, done, total)
                    }
                }
                val response = exchange(
                    session,
                    pairedPhoneId,
                    BleCommand.WifiSend(item.id, ownerMac, ownerName, p2p.groupOwnerAddress)
                )
                require(response == BleResponse.Ok) { (response as? BleResponse.Error)?.message ?: "手机未接受图片传输" }
                val received = receiver.await()
                require(received.byteLength == item.byteLength && received.sha256.contentEquals(item.sha256)) {
                    "图片长度或校验值不一致"
                }
                RemotePayload.Image(item, incoming)
            } finally {
                wifi.removeGroup()
            }
        }
    }

    private suspend fun receiveImageFromPhoneOwner(
        bleSession: BleGattSession,
        clientId: String,
        item: com.betterhv.transfer.core.QueueItem,
        key: ByteArray,
        incoming: File
    ): RemotePayload.Image = coroutineScope {
        val wifiStartedAt = SystemClock.elapsedRealtime()
        require(exchange(bleSession, clientId, BleCommand.WifiHost(item.id)) == BleResponse.Ok) {
            "手机未接受 Wi-Fi Direct 建组请求"
        }
        val owner = withTimeout(30_000L) {
            while (true) {
                when (val response = exchange(bleSession, clientId, BleCommand.WifiHostStatus(item.id))) {
                    is BleResponse.WifiOwnerInfo -> return@withTimeout response
                    BleResponse.Pending -> delay(250L)
                    is BleResponse.Error -> error(response.message)
                    else -> error("手机返回了无效建组状态")
                }
            }
            error("unreachable")
        }
        Log.i(TAG, "Wi-Fi Direct owner ready after ${elapsed(wifiStartedAt)} ms")
        // N10Pro's vendor Wi-Fi P2P driver reports a successful setup but
        // fails to create the p2p interface (ioctl ENODEV). The tablet and
        // Windows host are already on the same WLAN, so use that interface as
        // a transport fallback instead of waiting for P2P discovery forever.
        if (Build.MODEL.equals("N10Pro", ignoreCase = true)) {
            val receiverIp = localWifiIpv4Address()
                ?: error("无法读取 N10 Pro 的 Wi-Fi 地址")
            val receiver = async(Dispatchers.IO) {
                EncryptedFileTransfer.receive(incoming, item.id, key) { done, total ->
                    mutableState.value = TransferState.Transferring(item.id, done, total)
                }
            }
            val response = exchange(bleSession, clientId, BleCommand.WifiSendTo(item.id, receiverIp))
            require(response == BleResponse.Ok) {
                (response as? BleResponse.Error)?.message ?: "手机未接受图片传输"
            }
            val received = receiver.await()
            Log.i(TAG, "LAN image TCP transfer finished after ${elapsed(wifiStartedAt)} ms")
            require(received.byteLength == item.byteLength && received.sha256.contentEquals(item.sha256)) {
                "图片长度或校验值不一致"
            }
            return@coroutineScope RemotePayload.Image(item, incoming)
        }
        WifiDirectController(appContext).use { wifi ->
            try {
                val p2p = wifi.connect(
                    owner.deviceAddress,
                    owner.deviceName,
                    owner.networkName,
                    owner.passphrase
                )
                Log.i(TAG, "Wi-Fi Direct joined after ${elapsed(wifiStartedAt)} ms")
                val receiverIp = requireNotNull(p2p.localIpAddress) { "无法读取 Note 的 Wi-Fi Direct 地址" }
                val receiver = async(Dispatchers.IO) {
                    EncryptedFileTransfer.receive(incoming, item.id, key) { done, total ->
                        mutableState.value = TransferState.Transferring(item.id, done, total)
                    }
                }
                val response = exchange(bleSession, clientId, BleCommand.WifiSendTo(item.id, receiverIp))
                require(response == BleResponse.Ok) {
                    (response as? BleResponse.Error)?.message ?: "手机未接受图片传输"
                }
                val received = receiver.await()
                Log.i(TAG, "image TCP transfer finished after ${elapsed(wifiStartedAt)} ms")
                require(received.byteLength == item.byteLength && received.sha256.contentEquals(item.sha256)) {
                    "图片长度或校验值不一致"
                }
                RemotePayload.Image(item, incoming)
            } finally {
                wifi.removeGroup()
            }
        }
    }

    private suspend fun exchange(session: BleGattSession, clientId: String, command: BleCommand): BleResponse {
        val key = requireNotNull(pairing.sharedKey(clientId)) { "配对密钥不可用" }
        val sealed = BleSecureEnvelope.seal(key, BleQueueProtocol.encode(command))
        val response = session.exchange(sealed)
        return BleQueueProtocol.decodeResponse(BleSecureEnvelope.open(key, response, inboundReplay))
    }

    private suspend fun findSender(
        paired: PairedDevice,
        kind: ContentKind?,
        timeoutMillis: Long
    ): DiscoveredSender? {
        recentSenders[paired.id]?.takeIf { recent ->
            SystemClock.elapsedRealtime() - recent.observedAt <= RECENT_SENDER_MILLIS &&
                senderMatches(recent.sender, paired, kind)
        }?.let { return it.sender }
        newScanner().discover(timeoutMillis).forEach { advertised ->
            val sender = resolveAdvertisementIdentity(advertised) ?: return@forEach
            if (senderMatches(sender, paired, kind)) {
                recentSenders[paired.id] = RecentSender(sender, SystemClock.elapsedRealtime())
                return sender
            }
        }
        return null
    }

    private fun senderMatches(sender: DiscoveredSender, paired: PairedDevice, kind: ContentKind?): Boolean {
        val contentMatches = when (kind) {
            ContentKind.IMAGE -> sender.imageCount > 0
            ContentKind.TEXT -> sender.textCount > 0
            null -> true
        }
        return contentMatches && (paired.legacy || sender.identityHash.equals(paired.identityHash, ignoreCase = true))
    }

    private suspend fun resolveAdvertisementIdentity(sender: DiscoveredSender): DiscoveredSender? {
        if (sender.identityHash.isNotBlank()) return sender
        return runCatching {
            withBleSession(sender.bluetoothAddress) { session ->
                val identity = session.readIdentity()
                DiscoveredSender(
                    bluetoothAddress = sender.bluetoothAddress,
                    deviceId = AndroidPairingController.identityHash(identity.deviceId),
                    name = identity.deviceName,
                    imageCount = identity.imageCount,
                    textCount = identity.textCount
                )
            }
        }.getOrNull()
    }

    private suspend fun verifyIdentity(
        session: BleGattSession,
        sender: DiscoveredSender,
        paired: PairedDevice
    ): BleIdentity {
        val identity = session.readIdentity()
        val actualHash = AndroidPairingController.identityHash(identity.deviceId)
        require(sender.identityHash.isBlank() || actualHash.equals(sender.identityHash, ignoreCase = true)) {
            "NoteLink 广播身份与完整身份不一致"
        }
        if (!paired.legacy) {
            require(identity.deviceId == paired.id) { "连接到的 NoteLink 与所选客户端不一致" }
        }
        return identity
    }

    private fun rememberAuthenticatedSenderName(clientId: String, name: String) {
        val normalized = name.trim()
        if (normalized.isNotEmpty() && pairing.pairedClient(clientId)?.name != normalized) {
            pairing.updatePairedDeviceName(clientId, normalized)
        }
    }

    /** Drops radio objects that Android commonly invalidates while the tablet sleeps. */
    suspend fun recoverAfterWake() = withContext(Dispatchers.IO) {
        val sessions = synchronized(activeBleSessions) { activeBleSessions.toList() }
        sessions.forEach(::closeBleSession)
        runCatching {
            WifiDirectController(appContext).use { wifi -> wifi.removeGroup() }
        }
        inboundReplay = BleReplayCache()
        operationActive.set(false)
        mutableState.value = TransferState.Idle
    }

    private fun newScanner() = BleReceiverScanner(appContext)

    private fun localWifiIpv4Address(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull { address ->
                !address.startsWith("127.") && !address.startsWith("169.254.")
            }
    }.getOrNull()

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

    private suspend fun <T> withBleSession(
        bluetoothAddress: String,
        block: suspend (BleGattSession) -> T
    ): T {
        val session = openBleSession(bluetoothAddress)
        return try {
            block(session)
        } finally {
            closeBleSession(session)
        }
    }

    override fun close() {
        val sessions = synchronized(activeBleSessions) { activeBleSessions.toList() }
        sessions.forEach(::closeBleSession)
        mutableState.value = TransferState.Idle
        operationActive.set(false)
    }

    private inner class NoteReceivedLease(
        private val session: BleGattSession,
        private val clientId: String,
        override val offer: TransferOffer,
        override val payload: RemotePayload,
        private val finish: () -> Unit
    ) : ReceivedLease {
        private var finished = false
        override fun heartbeat() { if (!finished) blocking(BleCommand.Heartbeat(offer.item.id)) }
        override fun markTransferring() = Unit
        override fun markAwaitingCommit() = Unit
        override fun commit() = finishWith(BleCommand.Commit(offer.item.id))
        override fun release() = finishWith(BleCommand.Release(offer.item.id))
        private fun blocking(command: BleCommand) = runBlocking(Dispatchers.IO) {
            val response = exchange(session, clientId, command)
            require(response == BleResponse.Ok) { (response as? BleResponse.Error)?.message ?: "手机拒绝操作" }
        }
        private fun finishWith(command: BleCommand) {
            if (finished) return
            try {
                blocking(command)
            } finally {
                finished = true
                closeSession()
            }
        }
        private fun closeSession() { closeBleSession(session); finish() }
    }

    private companion object {
        const val TAG = "NoteLinkTransfer"
        const val SCAN_TO_GATT_SETTLE_MILLIS = 350L
        const val RECENT_SENDER_MILLIS = 30_000L
    }

    private data class RecentSender(val sender: DiscoveredSender, val observedAt: Long)

    private fun elapsed(startedAt: Long): Long = SystemClock.elapsedRealtime() - startedAt
}
