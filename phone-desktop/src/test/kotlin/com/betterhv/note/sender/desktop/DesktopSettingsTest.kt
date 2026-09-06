package com.betterhv.note.sender.desktop

import com.betterhv.note.sender.shared.NoteLinkLanguage
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

  @Test
  fun multiplePairingsPersistAndCanBeSelectedOrRemovedIndividually() {
    val root = createTempDirectory("notelink-settings-multi-").toFile()
    try {
      val native = PassthroughNativeApi()
      val paths = DesktopPaths(root)
      val settings = DesktopSettings(paths, native)
      settings.pairWithCode("111111", "note-1", "书房")
      settings.pairWithCode("222222", "note-2", "办公室")

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
    override fun startBle(deviceId: String, deviceName: String, imageCount: Int, textCount: Int, pdfCount: Int) = Unit
    override fun updateBleCounts(imageCount: Int, textCount: Int, pdfCount: Int) = Unit
    override fun pollBleCommand(timeoutMillis: Int): ByteArray? = null
    override fun respondBle(value: ByteArray) = Unit
    override fun stopBle() = Unit
    override fun lanInfo() = com.betterhv.transfer.windows.WindowsLanInfo("192.168.1.10", "Test WiFi")
    override fun protect(value: ByteArray) = value.copyOf()
    override fun unprotect(value: ByteArray) = value.copyOf()
    override fun close() = Unit
  }
}
