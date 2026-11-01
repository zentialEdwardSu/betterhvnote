package com.betterhv.transfer.android

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.betterhv.transfer.core.DeviceCapabilities
import com.betterhv.transfer.core.HighBandwidthEndpoint
import com.betterhv.transfer.core.NetworkEndpoint
import com.betterhv.transfer.core.NOTELINK_LAN_PORT
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
    val ssid = TransferNetworkSecurity.normalizeSsid(currentSsid(capabilities))
    Log.d(TAG, "Active Wi-Fi endpoint=${host ?: "UNKNOWN"} ssid=${ssid ?: "UNKNOWN"}")
    AndroidNetworkInfo(host?.let { NetworkEndpoint(it, NOTELINK_LAN_PORT) }, ssid)
  }.getOrElse { AndroidNetworkInfo(null, null) }

  /**
   * Every usable IPv4 address of this device on Wi-Fi or Ethernet, active network first.
   * Cellular and VPN networks are excluded (a Note can never reach them), and Wi-Fi Direct
   * groups are negotiated separately.
   */
  @SuppressLint("MissingPermission")
  fun lanAddresses(): List<String> = runCatching {
    val manager = connectivity ?: return emptyList()
    val active = manager.activeNetwork
    @Suppress("DEPRECATION")
    val networks = manager.allNetworks.sortedByDescending { it == active }
    networks.flatMap { network ->
      val capabilities = manager.getNetworkCapabilities(network) ?: return@flatMap emptyList()
      val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
      if (!local || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@flatMap emptyList()
      manager.getLinkProperties(network)?.linkAddresses.orEmpty()
        .map { it.address }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        .mapNotNull { it.hostAddress }
    }.distinct()
  }.getOrDefault(emptyList())

  /** Calls [onChange] whenever the default network or its addresses change. */
  fun watch(onChange: () -> Unit): AutoCloseable {
    val manager = connectivity ?: return AutoCloseable {}
    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: android.net.Network) = onChange()
      override fun onLost(network: android.net.Network) = onChange()
      override fun onLinkPropertiesChanged(network: android.net.Network, linkProperties: android.net.LinkProperties) =
        onChange()
    }
    return runCatching {
      manager.registerDefaultNetworkCallback(callback)
      AutoCloseable { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.getOrElse { AutoCloseable {} }
  }

  private fun currentSsid(capabilities: NetworkCapabilities): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      (capabilities.transportInfo as? WifiInfo)?.ssid
    } else {
      @Suppress("DEPRECATION")
      wifi?.connectionInfo?.ssid
    }

  fun capabilities(
    pairingKey: ByteArray,
    wifiDirectEndpoint: HighBandwidthEndpoint? = null,
    extensions: Int = 0,
  ): DeviceCapabilities {
    val network = current()
    var modes = if (network.endpoint != null) TransferModes.LAN else TransferModes.NONE
    if (wifiDirectEndpoint != null) modes = modes or TransferModes.WIFI_DIRECT
    return DeviceCapabilities(
      modes = modes,
      lanEndpoint = network.endpoint,
      ssidFingerprint = TransferNetworkSecurity.ssidFingerprint(pairingKey, network.ssid),
      wifiDirectEndpoint = wifiDirectEndpoint,
      extensions = extensions,
    )
  }

  private companion object {
    const val TAG = "BetterHvNetwork"
  }
}
