package com.betterhv.transfer.windows

/** Testable contract around the stable C ABI exported by notelink_windows.dll. */
interface WindowsNativeApi : AutoCloseable {
    fun initialize()
    fun capabilities(): WindowsCapabilities
    fun startBle(deviceId: String, deviceName: String, imageCount: Int, textCount: Int)
    fun updateBleCounts(imageCount: Int, textCount: Int)
    fun pollBleCommand(timeoutMillis: Int): ByteArray?
    fun respondBle(value: ByteArray)
    fun stopBle()
    fun startWifiDirect(networkName: String, passphrase: String): String
    fun stopWifiDirect()
    fun protect(value: ByteArray): ByteArray
    fun unprotect(value: ByteArray): ByteArray
    override fun close()
}

data class WindowsCapabilities(
    val blePeripheral: Boolean,
    val wifiDirect: Boolean,
    val dataProtection: Boolean
) {
    val ready: Boolean get() = blePeripheral && wifiDirect && dataProtection
}

class WindowsNativeException(message: String) : IllegalStateException(message)
