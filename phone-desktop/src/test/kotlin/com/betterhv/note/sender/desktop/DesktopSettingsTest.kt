package com.betterhv.note.sender.desktop

import com.betterhv.transfer.core.i18n.NoteLinkLanguage
import com.betterhv.transfer.core.SendOrder
import com.betterhv.transfer.windows.WindowsCapabilities
import com.betterhv.transfer.windows.WindowsNativeApi
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSettingsTest {
  @Test
  fun languagePreferencePersists() {
    val root = createTempDirectory("notelink-settings-language-").toFile()
    try {
      val paths = DesktopPaths(root)
      val native = PassthroughNativeApi()
      val settings = DesktopSettings(paths, native)
      assertEquals(NoteLinkLanguage.SYSTEM, settings.language)

      settings.language = NoteLinkLanguage.ENGLISH

      assertEquals(NoteLinkLanguage.ENGLISH, DesktopSettings(paths, native).language)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun recentTransferEventPreferencePersists() {
    val root = createTempDirectory("notelink-settings-events-").toFile()
    try {
      val paths = DesktopPaths(root)
      val native = PassthroughNativeApi()
      val settings = DesktopSettings(paths, native)
      assertEquals(true, settings.showRecentTransferEvents)

      settings.showRecentTransferEvents = false

      assertEquals(false, DesktopSettings(paths, native).showRecentTransferEvents)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun sendOrderDefaultsToQueueAndPersists() {
    val root = createTempDirectory("notelink-settings-order-").toFile()
    try {
      val paths = DesktopPaths(root)
      val native = PassthroughNativeApi()
      val settings = DesktopSettings(paths, native)
      assertEquals(SendOrder.QUEUE, settings.sendOrder)

      settings.sendOrder = SendOrder.STACK

      assertEquals(SendOrder.STACK, DesktopSettings(paths, native).sendOrder)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun srpPairingPersistsTheFullIdentityAndKey() {
    val root = createTempDirectory("notelink-settings-").toFile()
    try {
      val native = PassthroughNativeApi()
      val paths = DesktopPaths(root)
      val settings = DesktopSettings(paths, native)
      val key = ByteArray(32) { it.toByte() }
      assertNull(settings.pairing)

      settings.storePairing("note-device-id", "N10Pro", key)

      val reloaded = DesktopSettings(paths, native).pairing
      assertEquals("note-device-id", reloaded?.deviceId)
      assertEquals("N10Pro", reloaded?.deviceName)
      assertContentEquals(key, reloaded?.sharedKey)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun multiplePairingsPersistAndCanBeSelectedOrRemovedIndividually() {
    val root = createTempDirectory("notelink-settings-multi-").toFile()
    try {
      val native = PassthroughNativeApi()
      val paths = DesktopPaths(root)
      val settings = DesktopSettings(paths, native)
      settings.storePairing("note-1", "书房", ByteArray(32) { 1 })
      settings.storePairing("note-2", "办公室", ByteArray(32) { 2 })

      val reloaded = DesktopSettings(paths, native)
      assertEquals(setOf("note-1", "note-2"), reloaded.pairings.map { it.deviceId }.toSet())
      assertEquals("note-2", reloaded.pairing?.deviceId)

      reloaded.markLastUsed("note-1")
      assertEquals("note-1", reloaded.pairing?.deviceId)
      reloaded.unpair("note-1")
      assertEquals(listOf("note-2"), reloaded.pairings.map { it.deviceId })
      assertTrue(reloaded.pairings.single().sharedKey.isNotEmpty())
    } finally {
      root.deleteRecursively()
    }
  }

  private class PassthroughNativeApi : WindowsNativeApi {
    override fun initialize() = Unit
    override fun capabilities() = WindowsCapabilities(true, true, true)
    override fun startBle(
      deviceId: String,
      deviceName: String,
      imageCount: Int,
      textCount: Int,
      pdfCount: Int,
      flags: Int,
    ) = Unit

    override fun updateBleCounts(imageCount: Int, textCount: Int, pdfCount: Int, flags: Int) = Unit
    override fun pollBleEvent(timeoutMillis: Int): com.betterhv.transfer.windows.BleControlEvent? = null
    override fun notifyBle(client: String, value: ByteArray) = Unit
    override fun stopBle() = Unit
    override fun lanInfo() =
      com.betterhv.transfer.windows.WindowsLanInfo("192.168.1.10", "Test WiFi")

    override fun protect(value: ByteArray) = value.copyOf()
    override fun unprotect(value: ByteArray) = value.copyOf()
    override fun close() = Unit
  }
}
