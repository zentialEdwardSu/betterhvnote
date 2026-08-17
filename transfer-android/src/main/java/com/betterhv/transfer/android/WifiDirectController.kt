package com.betterhv.transfer.android

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WifiDirectSession(
    val groupOwnerAddress: String,
    val localDeviceAddress: String?,
    val ownerDeviceName: String?,
    val isGroupOwner: Boolean,
    val localIpAddress: String? = null,
    val networkName: String? = null,
    val passphrase: String? = null
)

class WifiDirectController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(WifiP2pManager::class.java)
        ?: error("Wi-Fi Direct is unsupported")
    private val channel = manager.initialize(appContext, appContext.mainLooper, null)
    private var connectionWaiter: CompletableDeferred<WifiP2pInfo>? = null
    private var peerWaiter: CompletableDeferred<WifiP2pDevice>? = null
    private var expectedPeerAddress: String? = null
    private var activeNetworkId: Int? = null
    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                manager.requestConnectionInfo(channel) { info ->
                    if (info.groupFormed) connectionWaiter?.complete(info)
                }
            } else if (intent.action == WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
                requestExpectedPeer()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") appContext.registerReceiver(receiver, filter)
    }

    @SuppressLint("MissingPermission")
    suspend fun createGroup(
        timeoutMillis: Long = 30_000L,
        preferTemporaryConfig: Boolean = true
    ): WifiDirectSession {
        prepareForOperation()
        connectionWaiter = CompletableDeferred()
        val configured = if (
            preferTemporaryConfig && Build.VERSION.SDK_INT >= 29 &&
            !Build.MODEL.equals("N10Pro", ignoreCase = true)
        ) runCatching {
            action { listener -> manager.createGroup(channel, ephemeralGroupConfig(), listener) }
        }.onFailure { Log.w(TAG, "Configured temporary group unsupported; using system group", it) }.isSuccess else false
        if (!configured) {
            actionWithBusyRetry { listener -> manager.createGroup(channel, listener) }
        }
        val info = awaitConnectionInfo(timeoutMillis)
        val group = requestGroupInfo()
        activeNetworkId = group?.networkId?.takeIf { it >= 0 }
        val localDevice = requestDeviceInfo()
        val ownerAddress = group?.owner?.deviceAddress
            ?.takeUnless(::isRedactedAddress)
            ?: localDevice?.deviceAddress
            ?: group?.owner?.deviceAddress
        val ownerName = group?.owner?.deviceName?.takeIf(String::isNotBlank)
            ?: localDevice?.deviceName?.takeIf(String::isNotBlank)
        Log.i(TAG, "Created group ${group?.networkName}; owner=$ownerAddress ip=${info.groupOwnerAddress}")
        return WifiDirectSession(
            requireNotNull(info.groupOwnerAddress).hostAddress ?: "192.168.49.1",
            ownerAddress,
            ownerName,
            true,
            info.groupOwnerAddress?.hostAddress,
            group?.networkName,
            group?.passphrase
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(
        ownerDeviceAddress: String,
        ownerDeviceName: String,
        networkName: String? = null,
        passphrase: String? = null,
        timeoutMillis: Long = 30_000L
    ): WifiDirectSession {
        prepareForOperation()
        val canJoinWithCredentials = Build.VERSION.SDK_INT >= 29 &&
            !networkName.isNullOrBlank() && !passphrase.isNullOrBlank()
        val owner = if (canJoinWithCredentials) null else {
            discoverPeer(ownerDeviceAddress, ownerDeviceName, (timeoutMillis / 2).coerceAtLeast(5_000L))
        }
        connectionWaiter = CompletableDeferred()
        val config = if (Build.VERSION.SDK_INT >= 29) {
            WifiP2pConfig.Builder().apply {
                if (canJoinWithCredentials) {
                    setNetworkName(requireNotNull(networkName))
                    setPassphrase(requireNotNull(passphrase))
                } else {
                    setDeviceAddress(android.net.MacAddress.fromString(requireNotNull(owner).deviceAddress))
                }
                setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                enablePersistentMode(false)
            }.build()
        } else {
            @Suppress("DEPRECATION") WifiP2pConfig().apply {
                deviceAddress = requireNotNull(owner).deviceAddress
                groupOwnerIntent = 0
            }
        }
        if (canJoinWithCredentials) {
            Log.i(TAG, "Joining authenticated group $networkName without WPS approval")
        } else {
            Log.i(TAG, "Connecting to discovered owner ${requireNotNull(owner).deviceAddress} (${owner.deviceName})")
        }
        actionWithBusyRetry { listener -> manager.connect(channel, config, listener) }
        val info = awaitConnectionInfo(timeoutMillis)
        val group = requestGroupInfo()
        Log.i(TAG, "Joined group owner=${info.groupOwnerAddress}")
        return WifiDirectSession(
            requireNotNull(info.groupOwnerAddress).hostAddress ?: "192.168.49.1",
            requestDeviceInfo()?.deviceAddress,
            owner?.deviceName ?: ownerDeviceName,
            info.isGroupOwner,
            localIpv4Address(group?.`interface`),
            group?.networkName,
            null
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun discoverPeer(
        ownerDeviceAddress: String,
        ownerDeviceName: String,
        timeoutMillis: Long
    ): WifiP2pDevice {
        expectedPeerAddress = ownerDeviceAddress
        peerWaiter = CompletableDeferred()
        var discovered = false
        try {
            actionWithBusyRetry { listener -> manager.discoverPeers(channel, listener) }
            return withTimeout(timeoutMillis) {
                while (true) {
                    requestExpectedPeer()
                    val peer = requestPeers().firstOrNull {
                        it.deviceAddress.equals(ownerDeviceAddress, ignoreCase = true) ||
                            (ownerDeviceName.isNotBlank() && it.deviceName == ownerDeviceName)
                    }
                    if (peer != null) {
                        discovered = true
                        Log.i(TAG, "Discovered expected owner ${peer.deviceAddress}")
                        return@withTimeout peer
                    }
                    delay(250L)
                }
                error("unreachable")
            }
        } finally {
            // connect() stops discovery itself. Some vendor implementations flush the
            // peer table in stopPeerDiscovery(), making the just-discovered config invalid.
            if (!discovered) {
                runCatching { action { listener -> manager.stopPeerDiscovery(channel, listener) } }
            }
            expectedPeerAddress = null
            peerWaiter = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestExpectedPeer() {
        val expected = expectedPeerAddress ?: return
        val waiter = peerWaiter ?: return
        manager.requestPeers(channel) { peers ->
            peers.deviceList.firstOrNull { it.deviceAddress.equals(expected, ignoreCase = true) }
                ?.let {
                    Log.i(TAG, "Discovered expected owner ${it.deviceAddress}")
                    waiter.complete(it)
                }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestPeers(): Collection<WifiP2pDevice> = suspendCancellableCoroutine { continuation ->
        manager.requestPeers(channel) { peers -> continuation.resume(peers.deviceList) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitConnectionInfo(timeoutMillis: Long): WifiP2pInfo = withTimeout(timeoutMillis) {
        while (true) {
            val info = requestConnectionInfo()
            if (info.groupFormed && info.groupOwnerAddress != null) return@withTimeout info
            delay(150L)
        }
        error("unreachable")
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestConnectionInfo(): WifiP2pInfo = suspendCancellableCoroutine { continuation ->
        manager.requestConnectionInfo(channel) { info -> continuation.resume(info) }
    }

    @SuppressLint("MissingPermission")
    suspend fun removeGroup() {
        runCatching { action { listener -> manager.removeGroup(channel, listener) } }
        activeNetworkId = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun prepareForOperation() {
        runCatching { action { listener -> manager.stopPeerDiscovery(channel, listener) } }
        runCatching { action { listener -> manager.cancelConnect(channel, listener) } }
        runCatching { action { listener -> manager.removeGroup(channel, listener) } }
        delay(750L)
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestDeviceInfo(): WifiP2pDevice? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= 29) {
            manager.requestDeviceInfo(channel) { device -> continuation.resume(device) }
        } else continuation.resume(null)
    }

    private fun isRedactedAddress(address: String): Boolean =
        address.equals("02:00:00:00:00:00", ignoreCase = true)

    @SuppressLint("MissingPermission")
    private suspend fun requestGroupInfo(): WifiP2pGroup? = suspendCancellableCoroutine { continuation ->
        manager.requestGroupInfo(channel) { group -> continuation.resume(group) }
    }

    private fun localIpv4Address(interfaceName: String?): String? = interfaceName?.let { name ->
        runCatching {
            java.net.NetworkInterface.getByName(name)?.inetAddresses?.toList()
                ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress
        }.getOrNull()
    }

    private fun ephemeralGroupConfig(): WifiP2pConfig {
        val random = ByteArray(8).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }
        return WifiP2pConfig.Builder()
            .setNetworkName("DIRECT-BH-BetterHv-${random.take(6)}")
            .setPassphrase("BetterHv-$random")
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
            .enablePersistentMode(false)
            .build()
    }

    private suspend fun action(call: (WifiP2pManager.ActionListener) -> Unit): Unit =
        suspendCancellableCoroutine { continuation ->
            call(object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onFailure(reason: Int) {
                    if (continuation.isActive) continuation.resumeWithException(WifiP2pOperationException(reason))
                }
            })
        }

    private suspend fun actionWithBusyRetry(call: (WifiP2pManager.ActionListener) -> Unit) {
        repeat(4) { attempt ->
            try {
                action(call)
                return
            } catch (failure: WifiP2pOperationException) {
                val transient = failure.reason == WifiP2pManager.BUSY || failure.reason == WifiP2pManager.ERROR
                if (!transient || attempt == 3) throw failure
                Log.w(TAG, "Transient Wi-Fi Direct failure ${failure.reason}; retry ${attempt + 1}/3")
                delay(750L * (attempt + 1))
            }
        }
    }

    override fun close() {
        connectionWaiter?.cancel()
        peerWaiter?.cancel()
        runCatching { appContext.unregisterReceiver(receiver) }
        runCatching { channel.close() }
    }

    private class WifiP2pOperationException(val reason: Int) : IllegalStateException(
        when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "系统报告不支持 Wi-Fi Direct"
            WifiP2pManager.BUSY -> "Wi-Fi Direct 服务忙或被系统禁用，请打开 WLAN 后重试"
            WifiP2pManager.ERROR -> "Wi-Fi Direct 固件暂时拒绝操作"
            else -> "Wi-Fi Direct 操作失败：$reason"
        }
    )

    private companion object {
        const val TAG = "BetterHvWifi"
    }
}
