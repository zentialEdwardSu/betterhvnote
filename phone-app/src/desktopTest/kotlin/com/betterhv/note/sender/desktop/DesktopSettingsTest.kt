package com.betterhv.note.sender.desktop

import com.betterhv.transfer.windows.WindowsCapabilities
import com.betterhv.transfer.windows.WindowsNativeApi
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopSettingsTest {
    @Test
    fun ownerPairingPersistsAfterAuthentication() {
        val root = createTempDirectory("notelink-settings-").toFile()
        try {
            val native = PassthroughNativeApi()
            val paths = DesktopPaths(root)
            val settings = DesktopSettings(paths, native)
            val code = settings.ownerPairingCode
            val ownerKey = settings.ownerPairing.sharedKey

            assertEquals(6, code.length)
            assertEquals(true, code.all(Char::isDigit))
            assertNull(settings.pairing)

            settings.completeOwnerPairing()

            val reloaded = DesktopSettings(paths, native).pairing
            assertEquals("betterhv-note", reloaded?.deviceId)
            assertEquals("N10Pro", reloaded?.deviceName)
            assertContentEquals(ownerKey, reloaded?.sharedKey)
        } finally {
            root.deleteRecursively()
        }
    }

    private class PassthroughNativeApi : WindowsNativeApi {
        override fun initialize() = Unit
        override fun capabilities() = WindowsCapabilities(true, true, true)
        override fun startBle(deviceId: String, deviceName: String, imageCount: Int, textCount: Int) = Unit
        override fun updateBleCounts(imageCount: Int, textCount: Int) = Unit
        override fun pollBleCommand(timeoutMillis: Int): ByteArray? = null
        override fun respondBle(value: ByteArray) = Unit
        override fun stopBle() = Unit
        override fun startWifiDirect(networkName: String, passphrase: String) = "192.168.137.1"
        override fun stopWifiDirect() = Unit
        override fun protect(value: ByteArray) = value.copyOf()
        override fun unprotect(value: ByteArray) = value.copyOf()
        override fun close() = Unit
    }
}
