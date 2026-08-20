package com.betterhv.transfer.android

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.betterhv.transfer.core.DeviceCapabilities
import com.betterhv.transfer.core.HighBandwidthEndpoint
import com.betterhv.transfer.core.NetworkEndpoint
import com.betterhv.transfer.core.SharedFileTransfer
import com.betterhv.transfer.core.TransferModes
import com.betterhv.transfer.core.TransferNetworkSecurity
import java.net.Inet4Address

data class AndroidNetworkInfo(val endpoint: NetworkEndpoint?, val ssid: String?)

class AndroidNetworkInfoProvider(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)

    @SuppressLint("MissingPermission")
    fun current(): AndroidNetworkInfo = runCatching {
        val network = connectivity?.activeNetwork ?: return AndroidNetworkInfo(null, null)
        val capabilities = connectivity.getNetworkCapabilities(network)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            return AndroidNetworkInfo(null, null)
        }
        val host = connectivity.getLinkProperties(network)?.linkAddresses
            ?.asSequence()?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
        val capabilitySsid = (capabilities.transportInfo as? WifiInfo)?.ssid
        val legacySsid = runCatching { wifi?.connectionInfo?.ssid }.getOrNull()
        val ssid = sequenceOf(capabilitySsid, legacySsid)
            .mapNotNull(TransferNetworkSecurity::normalizeSsid)
            .firstOrNull()
        Log.d(TAG, "Active Wi-Fi endpoint=${host ?: "UNKNOWN"} ssid=${ssid ?: "UNKNOWN"}")
        AndroidNetworkInfo(host?.let { NetworkEndpoint(it, SharedFileTransfer.PORT) }, ssid)
    }.getOrElse { AndroidNetworkInfo(null, null) }

    fun capabilities(
        pairingKey: ByteArray,
        wifiDirectEndpoint: HighBandwidthEndpoint? = null,
        extensions: Int = 0
    ): DeviceCapabilities {
        val network = current()
        var modes = if (network.endpoint != null) TransferModes.LAN else TransferModes.NONE
        if (wifiDirectEndpoint != null) modes = modes or TransferModes.WIFI_DIRECT
        return DeviceCapabilities(
            modes = modes,
            lanEndpoint = network.endpoint,
            ssidFingerprint = TransferNetworkSecurity.ssidFingerprint(pairingKey, network.ssid),
            wifiDirectEndpoint = wifiDirectEndpoint,
            extensions = extensions
        )
    }

    private companion object {
        const val TAG = "BetterHvNetwork"
    }
}
