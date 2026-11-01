package com.betterhv.transfer.windows

/** One event from the Windows GATT server, keyed by the GATT session's device id. */
sealed interface BleControlEvent {
  val client: String

  data class Record(override val client: String, val bytes: ByteArray) : BleControlEvent {
    override fun equals(other: Any?): Boolean = other is Record && client == other.client && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = 31 * client.hashCode() + bytes.contentHashCode()
  }

  data class Connected(override val client: String) : BleControlEvent
  data class Disconnected(override val client: String) : BleControlEvent
  data class Mtu(override val client: String, val mtu: Int) : BleControlEvent
}

/** Testable contract around the stable C ABI exported by notelink_windows.dll. */
interface WindowsNativeApi : AutoCloseable {
  fun initialize()
  fun capabilities(): WindowsCapabilities
  fun startBle(deviceId: String, deviceName: String, imageCount: Int, textCount: Int, pdfCount: Int = 0, flags: Int = 0)
  fun updateBleCounts(imageCount: Int, textCount: Int, pdfCount: Int = 0, flags: Int = 0)

  /** Next control event, or null after [timeoutMillis]. */
  fun pollBleEvent(timeoutMillis: Int): BleControlEvent?

  /** Notifies one subscribed client; returns after Windows reported the result. */
  fun notifyBle(client: String, value: ByteArray)
  fun stopBle()
  fun lanInfo(): WindowsLanInfo
  fun protect(value: ByteArray): ByteArray
  fun unprotect(value: ByteArray): ByteArray
  override fun close()
}

data class WindowsCapabilities(val blePeripheral: Boolean, val lan: Boolean, val dataProtection: Boolean) {
  val ready: Boolean get() = blePeripheral && dataProtection
}

/** [ipv4] lists every reachable LAN address, Wi-Fi first; [ssid] is blank on Ethernet. */
data class WindowsLanInfo(val ipv4: String, val ssid: String) {
  val addresses: List<String> get() = ipv4.split(',').map(String::trim).filter(String::isNotEmpty)
}

class WindowsNativeException(message: String) : IllegalStateException(message)
