package com.betterhv.note.sender

import com.betterhv.transfer.android.AndroidPairingController
import com.betterhv.transfer.android.BleCommand
import com.betterhv.transfer.android.BleQueueProtocol
import com.betterhv.transfer.android.BleResponse
import com.betterhv.transfer.android.BleReplayCache
import com.betterhv.transfer.android.BleSecureEnvelope
import com.betterhv.transfer.android.EncryptedFileTransfer
import com.betterhv.transfer.android.SenderLease
import com.betterhv.transfer.android.WifiDirectController
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

class PhoneCommandProcessor(
    context: android.content.Context,
    private val queue: PhoneQueueRepository,
    private val inbox: ExportInboxRepository,
    private val pairing: AndroidPairingController,
    private val onQueueChanged: () -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val leases = ConcurrentHashMap<UUID, SenderLease>()
    private val hostedGroups = ConcurrentHashMap<UUID, HostedGroupState>()
    private val pushedExports = ConcurrentHashMap<UUID, PushedExportState>()
    private val pushJobs = ConcurrentHashMap<UUID, Job>()
    private val inboundReplay = BleReplayCache()

    fun handle(bytes: ByteArray): ByteArray {
        val device = requireNotNull(pairing.pairedDevice) { "尚未配对" }
        val key = requireNotNull(pairing.sharedKey(device.id)) { "配对密钥不可用" }
        val commandBytes = BleSecureEnvelope.open(key, bytes, inboundReplay)
        val response = runCatching {
        when (val command = BleQueueProtocol.decodeCommand(commandBytes)) {
            BleCommand.Counts -> queue.counts().let { BleResponse.Counts(it.first, it.second) }
            is BleCommand.Lease -> {
                val lease = queue.leaseNext(command.kind, command.destinationDeviceId)
                if (lease == null) BleResponse.Empty
                else { leases[lease.offer.item.id] = lease; BleResponse.Offer(lease.offer.item) }
            }
            is BleCommand.TextChunk -> {
                val lease = requireNotNull(leases[command.itemId]) { "租约不存在" }
                val content = requireNotNull(lease.text) { "不是文字项目" }.encodeToByteArray()
                require(command.offset in 0..content.size)
                val chunk = content.copyOfRange(command.offset,
                    (command.offset + BleQueueProtocol.TEXT_CHUNK_BYTES).coerceAtMost(content.size))
                lease.heartbeat()
                BleResponse.TextChunk(command.itemId, command.offset, content.size, chunk)
            }
            is BleCommand.Heartbeat -> { requireLease(command.itemId).heartbeat(); BleResponse.Ok }
            is BleCommand.Commit -> {
                leases.remove(command.itemId)?.commit() ?: queue.delete(command.itemId)
                closeHostedGroup(command.itemId)
                onQueueChanged(); BleResponse.Ok
            }
            is BleCommand.Release -> {
                leases.remove(command.itemId)?.release()
                closeHostedGroup(command.itemId)
                onQueueChanged(); BleResponse.Ok
            }
            is BleCommand.WifiSend -> {
                val lease = requireLease(command.itemId)
                val source = requireNotNull(lease.payloadFile) { "不是图片项目" }
                val deviceId = lease.offer.item.destinationDeviceId
                    ?: pairing.pairedDevice?.id ?: error("尚未配对")
                val key = requireNotNull(pairing.sharedKey(deviceId)) { "配对密钥不可用" }
                lease.markTransferring()
                Log.i(TAG, "Accepted Wi-Fi send ${command.itemId}; owner=${command.ownerDeviceAddress}/${command.ownerDeviceName} ip=${command.ownerIp}")
                scope.launch {
                    runCatching {
                        connectAndSend(
                            command.ownerDeviceAddress, command.ownerDeviceName, command.ownerIp,
                            command.itemId, source, key
                        )
                        lease.markAwaitingCommit()
                        Log.i(TAG, "Wi-Fi send completed ${command.itemId}")
                    }.onFailure {
                        Log.e(TAG, "Wi-Fi send failed ${command.itemId}", it)
                        lease.release(); leases.remove(command.itemId); onQueueChanged()
                    }
                }
                BleResponse.Ok
            }
            is BleCommand.WifiHost -> {
                requireLease(command.itemId)
                if (hostedGroups.putIfAbsent(command.itemId, HostedGroupState.Preparing) == null) {
                    scope.launch {
                        val wifi = WifiDirectController(appContext)
                        // Some vendor stacks leave the channel unusable after rejecting a configured
                        // non-persistent group. The phone owns this short-lived group, so start with
                        // the broadly supported default API and remove it when the transfer finishes.
                        runCatching { wifi.createGroup(preferTemporaryConfig = false) }
                            .onSuccess { session ->
                                hostedGroups[command.itemId] = HostedGroupState.Ready(wifi, session)
                                Log.i(TAG, "Phone group ready ${command.itemId}; owner=${session.localDeviceAddress}/${session.ownerDeviceName}")
                            }
                            .onFailure {
                                wifi.close()
                                hostedGroups[command.itemId] = HostedGroupState.Failed(it.message ?: "手机建组失败")
                                Log.e(TAG, "Phone group failed ${command.itemId}", it)
                            }
                    }
                }
                BleResponse.Ok
            }
            is BleCommand.WifiHostStatus -> when (val state = hostedGroups[command.itemId]) {
                null, HostedGroupState.Preparing -> BleResponse.Pending
                is HostedGroupState.Failed -> BleResponse.Error(state.message)
                is HostedGroupState.Ready -> BleResponse.WifiOwnerInfo(
                    requireNotNull(state.session.localDeviceAddress) { "无法读取手机 Wi-Fi Direct 地址" },
                    state.session.ownerDeviceName?.takeIf(String::isNotBlank) ?: android.os.Build.MODEL,
                    state.session.groupOwnerAddress,
                    requireNotNull(state.session.networkName) { "无法读取手机 Wi-Fi Direct 组名" },
                    requireNotNull(state.session.passphrase) { "无法读取手机 Wi-Fi Direct 密码" }
                )
            }
            is BleCommand.WifiSendTo -> {
                val lease = requireLease(command.itemId)
                val source = requireNotNull(lease.payloadFile) { "不是图片项目" }
                val deviceId = lease.offer.item.destinationDeviceId
                    ?: pairing.pairedDevice?.id ?: error("尚未配对")
                val key = requireNotNull(pairing.sharedKey(deviceId)) { "配对密钥不可用" }
                require(hostedGroups[command.itemId] is HostedGroupState.Ready) { "手机 Wi-Fi Direct 组尚未就绪" }
                lease.markTransferring()
                scope.launch {
                    runCatching {
                        Log.i(TAG, "Sending ${command.itemId} to joined Note ${command.receiverIp}")
                        EncryptedFileTransfer.send(command.receiverIp, command.itemId, source, key)
                        lease.markAwaitingCommit()
                        Log.i(TAG, "Wi-Fi send completed ${command.itemId}")
                    }.onFailure {
                        Log.e(TAG, "Wi-Fi send failed ${command.itemId}", it)
                        lease.release(); leases.remove(command.itemId); onQueueChanged()
                    }
                    closeHostedGroup(command.itemId)
                }
                BleResponse.Ok
            }
            BleCommand.Capabilities -> BleResponse.Capabilities(BleQueueProtocol.CAPABILITY_EXPORT_PUSH)
            is BleCommand.PushOffer -> {
                when (val begin = inbox.begin(device.id, command.offer)) {
                    InboxBeginResult.AlreadyReceived -> BleResponse.AlreadyReceived(command.offer.artifactId)
                    is InboxBeginResult.Receive -> {
                        if (pushedExports.putIfAbsent(
                                command.offer.artifactId, PushedExportState.Preparing
                            ) == null
                        ) {
                            pushJobs[command.offer.artifactId] =
                                prepareExportReceiver(command.offer, begin.partialFile, key)
                        }
                        BleResponse.Ok
                    }
                }
            }
            is BleCommand.PushStatus -> when (val state = pushedExports[command.artifactId]) {
                null -> inbox.find(command.artifactId)?.takeIf { it.state == InboxExportState.COMPLETE }
                    ?.let { BleResponse.AlreadyReceived(command.artifactId) }
                    ?: BleResponse.Error("接收任务不存在")
                PushedExportState.Preparing -> BleResponse.Pending
                is PushedExportState.Ready -> BleResponse.WifiOwnerInfo(
                    requireNotNull(state.session.localDeviceAddress) { "无法读取手机 Wi-Fi Direct 地址" },
                    state.session.ownerDeviceName?.takeIf(String::isNotBlank) ?: android.os.Build.MODEL,
                    state.session.groupOwnerAddress,
                    requireNotNull(state.session.networkName) { "无法读取手机 Wi-Fi Direct 组名" },
                    requireNotNull(state.session.passphrase) { "无法读取手机 Wi-Fi Direct 密码" }
                )
                PushedExportState.Complete -> BleResponse.PushComplete(command.artifactId)
                is PushedExportState.Failed -> BleResponse.Error(state.message)
            }
            is BleCommand.PushCancel -> {
                pushJobs.remove(command.artifactId)?.cancel()
                val state = pushedExports.remove(command.artifactId)
                if (state is PushedExportState.Ready) {
                    scope.launch { runCatching { state.wifi.removeGroup() }; state.wifi.close() }
                }
                inbox.cancel(command.artifactId)
                onQueueChanged()
                BleResponse.Ok
            }
        }
        }.getOrElse { BleResponse.Error(it.message ?: "命令失败") }
        return BleSecureEnvelope.seal(key, BleQueueProtocol.encode(response))
    }

    private fun requireLease(id: UUID) = requireNotNull(leases[id]) { "租约不存在" }
    override fun close() {
        leases.values.forEach(SenderLease::release)
        leases.clear()
        hostedGroups.keys.toList().forEach(::closeHostedGroup)
        pushedExports.values.filterIsInstance<PushedExportState.Ready>().forEach {
            runCatching { it.wifi.close() }
        }
        pushedExports.clear()
        pushJobs.values.forEach(Job::cancel)
        pushJobs.clear()
        scope.cancel()
    }

    private fun closeHostedGroup(itemId: UUID) {
        val state = hostedGroups.remove(itemId)
        if (state is HostedGroupState.Ready) {
            scope.launch {
                runCatching { state.wifi.removeGroup() }
                state.wifi.close()
            }
        }
    }

    private suspend fun connectAndSend(
        ownerMac: String,
        ownerName: String,
        ownerIp: String,
        id: UUID,
        file: java.io.File,
        key: ByteArray
    ) {
        WifiDirectController(appContext).use { wifi ->
            try {
                val session = wifi.connect(ownerMac, ownerName)
                EncryptedFileTransfer.send(session.groupOwnerAddress.ifBlank { ownerIp }, id, file, key)
            } finally {
                wifi.removeGroup()
            }
        }
    }

    private fun prepareExportReceiver(
        offer: com.betterhv.transfer.core.ExportTransferOffer,
        partialFile: java.io.File,
        key: ByteArray
    ): Job = scope.launch {
            val wifi = WifiDirectController(appContext)
            try {
                val session = wifi.createGroup(preferTemporaryConfig = false)
                pushedExports[offer.artifactId] = PushedExportState.Ready(wifi, session)
                val received = EncryptedFileTransfer.receive(partialFile, offer.artifactId, key)
                require(received.byteLength == offer.byteLength && received.sha256.contentEquals(offer.sha256)) {
                    "导出文件长度或校验值不一致"
                }
                inbox.complete(offer.artifactId, partialFile)
                pushedExports[offer.artifactId] = PushedExportState.Complete
                onQueueChanged()
            } catch (_: CancellationException) {
                pushedExports.remove(offer.artifactId)
            } catch (t: Throwable) {
                val message = t.message ?: "接收导出失败"
                inbox.fail(offer.artifactId, message)
                pushedExports[offer.artifactId] = PushedExportState.Failed(message)
            } finally {
                pushJobs.remove(offer.artifactId)
                runCatching { wifi.removeGroup() }
                wifi.close()
            }
        }

    private companion object {
        const val TAG = "BetterHvWifi"
    }

    private sealed interface HostedGroupState {
        data object Preparing : HostedGroupState
        data class Ready(val wifi: WifiDirectController, val session: com.betterhv.transfer.android.WifiDirectSession) : HostedGroupState
        data class Failed(val message: String) : HostedGroupState
    }

    private sealed interface PushedExportState {
        data object Preparing : PushedExportState
        data class Ready(
            val wifi: WifiDirectController,
            val session: com.betterhv.transfer.android.WifiDirectSession
        ) : PushedExportState
        data object Complete : PushedExportState
        data class Failed(val message: String) : PushedExportState
    }
}
