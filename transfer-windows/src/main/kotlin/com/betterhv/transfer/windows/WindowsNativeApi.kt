package com.betterhv.transfer.windows

/** Testable contract around the stable C ABI exported by notelink_windows.dll. */
interface WindowsNativeApi : AutoCloseable {
  fun initialize()
  fun capabilities(): WindowsCapabilities
  fun startBle(deviceId: String, deviceName: String, imageCount: Int, textCount: Int, pdfCount: Int = 0)
  fun updateBleCounts(imageCount: Int, textCount: Int, pdfCount: Int = 0)
  fun pollBleCommand(timeoutMillis: Int): ByteArray?
  fun respondBle(value: ByteArray)
  fun stopBle()
  fun lanInfo(): WindowsLanInfo
  fun protect(value: ByteArray): ByteArray
  fun unprotect(value: ByteArray): ByteArray
  override fun close()
}

data class WindowsCapabilities(val blePeripheral: Boolean, val lan: Boolean, val dataProtection: Boolean) {
  val ready: Boolean get() = blePeripheral && lan && dataProtection
}

data class WindowsLanInfo(val ipv4: String, val ssid: String)

class WindowsNativeException(message: String) : IllegalStateException(message)
