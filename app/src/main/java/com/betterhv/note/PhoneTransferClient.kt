package com.betterhv.note

import android.content.Context
import android.os.Build
import com.betterhv.transfer.android.AndroidPairingController
import com.betterhv.transfer.android.BleCommand
import com.betterhv.transfer.android.BleGattSession
import com.betterhv.transfer.android.BleQueueProtocol
import com.betterhv.transfer.android.BleReceiverScanner
import com.betterhv.transfer.android.BleResponse
import com.betterhv.transfer.android.BleReplayCache
import com.betterhv.transfer.android.BleSecureEnvelope
import com.betterhv.transfer.android.EncryptedFileTransfer
import com.betterhv.transfer.android.ReceivedLease
import com.betterhv.transfer.android.WifiDirectController
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.ExportTransferOffer
import com.betterhv.transfer.core.RemotePayload
import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.core.TransferOffer
import com.betterhv.transfer.core.TransferState
import com.betterhv.note.export.ExportArtifact
import java.io.File
import java.security.MessageDigest
import java.util.UUID
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
    private val scanner = BleReceiverScanner(appContext)
    private val mutableState = MutableStateFlow<TransferState>(TransferState.Idle)
    private val inboundReplay = BleReplayCache()
    val state: StateFlow<TransferState> = mutableState

    /** Uses the sender advertisement counts without leasing an item. */
    suspend fun hasAvailable(kind: ContentKind): Boolean = withContext(Dispatchers.IO) {
        if (pairing.pairedDevice == null) return@withContext false
        runCatching {
            scanner.discoverFirst(5_000L) {
                if (kind == ContentKind.IMAGE) it.imageCount > 0 else it.textCount > 0
            }
        }.getOrNull() != null
    }

    init {
        pairing.pairedDevice?.takeIf {
            it.id == "betterhv-phone" && it.name == "BetterHv Send"
        }?.let { pairing.updatePairedDeviceName("NoteLink") }
    }

    suspend fun requestNext(kind: ContentKind): ReceivedLease? = withContext(Dispatchers.IO) {
        val paired = pairing.pairedDevice ?: error("请先在设置中配对手机")
        mutableState.value = TransferState.Scanning
        val sender = scanner.discoverFirst(5_000L) {
            if (kind == ContentKind.IMAGE) it.imageCount > 0 else it.textCount > 0
        } ?: run { mutableState.value = TransferState.Idle; return@withContext null }
        val session = BleGattSession.connect(appContext, sender.bluetoothAddress)
        var leasedItemId: UUID? = null
        try {
            val response = exchange(session, BleCommand.Lease(kind, "betterhv-note"))
            rememberAuthenticatedSenderName(sender.name)
            val offer = (response as? BleResponse.Offer)?.item
                ?: if (response == BleResponse.Empty) {
                    session.close()
                    mutableState.value = TransferState.Idle
                    return@withContext null
                } else error("手机返回了无效队列响应")
            leasedItemId = offer.id
            val payload = when (kind) {
                ContentKind.TEXT -> receiveText(session, offer)
                ContentKind.IMAGE -> receiveImage(session, offer, paired.id)
            }
            mutableState.value = TransferState.AwaitingPlacement(offer.id)
            NoteReceivedLease(session, TransferOffer(offer), payload) { mutableState.value = TransferState.Idle }
        } catch (t: Throwable) {
            leasedItemId?.let { itemId -> runCatching { exchange(session, BleCommand.Release(itemId)) } }
            session.close()
            mutableState.value = TransferState.Error(t.message ?: "手机传输失败")
            throw t
        }
    }

    suspend fun sendExport(
        artifact: ExportArtifact,
        progress: (Long, Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val paired = pairing.pairedDevice ?: error("请先在设置中配对 NoteLink")
        require(artifact.byteLength <= com.betterhv.transfer.core.TransferLimits.MAX_EXPORT_BYTES) {
            "导出文件超过 512 MiB"
        }
        mutableState.value = TransferState.Scanning
        val sender = scanner.discoverFirst(8_000L)
            ?: error("未发现 NoteLink；请确认手机接收服务正在运行")
        BleGattSession.connect(appContext, sender.bluetoothAddress).use { session ->
            val capabilities = exchange(session, BleCommand.Capabilities) as? BleResponse.Capabilities
                ?: error("NoteLink 版本过旧，请升级后重试")
            rememberAuthenticatedSenderName(sender.name)
            require(capabilities.flags and BleQueueProtocol.CAPABILITY_EXPORT_PUSH != 0) {
                "NoteLink 版本不支持接收导出"
            }
            val offer = ExportTransferOffer(
                artifact.id, artifact.displayName, artifact.mimeType, artifact.byteLength, artifact.sha256
            )
            when (val response = exchange(session, BleCommand.PushOffer(offer))) {
                is BleResponse.AlreadyReceived -> {
                    mutableState.value = TransferState.Idle
                    return@withContext
                }
                BleResponse.Ok -> Unit
                is BleResponse.Error -> error(response.message)
                else -> error("NoteLink 未接受导出文件")
            }
            try {
                val owner = withTimeout(45_000L) {
                    while (true) {
                        when (val response = exchange(session, BleCommand.PushStatus(artifact.id))) {
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
                    val key = requireNotNull(pairing.sharedKey(paired.id)) { "配对密钥不可用" }
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
                            when (val response = exchange(session, BleCommand.PushStatus(artifact.id))) {
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
                runCatching { exchange(session, BleCommand.PushCancel(artifact.id)) }
                mutableState.value = TransferState.Error(t.message ?: "发送导出失败")
                throw t
            }
        }
    }

    private suspend fun receiveText(session: BleGattSession, item: com.betterhv.transfer.core.QueueItem): RemotePayload.Text {
        val output = java.io.ByteArrayOutputStream(item.byteLength.toInt())
        var offset = 0
        while (offset < item.byteLength) {
            val chunk = exchange(session, BleCommand.TextChunk(item.id, offset)) as? BleResponse.TextChunk
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
            return@coroutineScope receiveImageFromPhoneOwner(session, item, key, incoming)
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
        item: com.betterhv.transfer.core.QueueItem,
        key: ByteArray,
        incoming: File
    ): RemotePayload.Image = coroutineScope {
        require(exchange(bleSession, BleCommand.WifiHost(item.id)) == BleResponse.Ok) {
            "手机未接受 Wi-Fi Direct 建组请求"
        }
        val owner = withTimeout(30_000L) {
            while (true) {
                when (val response = exchange(bleSession, BleCommand.WifiHostStatus(item.id))) {
                    is BleResponse.WifiOwnerInfo -> return@withTimeout response
                    BleResponse.Pending -> delay(250L)
                    is BleResponse.Error -> error(response.message)
                    else -> error("手机返回了无效建组状态")
                }
            }
            error("unreachable")
        }
        WifiDirectController(appContext).use { wifi ->
            try {
                val p2p = wifi.connect(
                    owner.deviceAddress,
                    owner.deviceName,
                    owner.networkName,
                    owner.passphrase
                )
                val receiverIp = requireNotNull(p2p.localIpAddress) { "无法读取 Note 的 Wi-Fi Direct 地址" }
                val receiver = async(Dispatchers.IO) {
                    EncryptedFileTransfer.receive(incoming, item.id, key) { done, total ->
                        mutableState.value = TransferState.Transferring(item.id, done, total)
                    }
                }
                val response = exchange(bleSession, BleCommand.WifiSendTo(item.id, receiverIp))
                require(response == BleResponse.Ok) {
                    (response as? BleResponse.Error)?.message ?: "手机未接受图片传输"
                }
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

    private suspend fun exchange(session: BleGattSession, command: BleCommand): BleResponse {
        val phone = requireNotNull(pairing.pairedDevice) { "尚未配对" }
        val key = requireNotNull(pairing.sharedKey(phone.id)) { "配对密钥不可用" }
        val sealed = BleSecureEnvelope.seal(key, BleQueueProtocol.encode(command))
        val response = session.exchange(sealed)
        return BleQueueProtocol.decodeResponse(BleSecureEnvelope.open(key, response, inboundReplay))
    }

    private fun rememberAuthenticatedSenderName(name: String) {
        val normalized = name.trim()
        if (normalized.isNotEmpty() && pairing.pairedDevice?.name != normalized) {
            pairing.updatePairedDeviceName(normalized)
        }
    }

    override fun close() { mutableState.value = TransferState.Idle }

    private inner class NoteReceivedLease(
        private val session: BleGattSession,
        override val offer: TransferOffer,
        override val payload: RemotePayload,
        private val finish: () -> Unit
    ) : ReceivedLease {
        private var finished = false
        override fun heartbeat() { if (!finished) blocking(BleCommand.Heartbeat(offer.item.id)) }
        override fun markTransferring() = Unit
        override fun markAwaitingCommit() = Unit
        override fun commit() { if (!finished) { blocking(BleCommand.Commit(offer.item.id)); finished = true; closeSession() } }
        override fun release() { if (!finished) { blocking(BleCommand.Release(offer.item.id)); finished = true; closeSession() } }
        private fun blocking(command: BleCommand) = runBlocking(Dispatchers.IO) {
            val response = exchange(session, command)
            require(response == BleResponse.Ok) { (response as? BleResponse.Error)?.message ?: "手机拒绝操作" }
        }
        private fun closeSession() { session.close(); finish() }
    }
}
