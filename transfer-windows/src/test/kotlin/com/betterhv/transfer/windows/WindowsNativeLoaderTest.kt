package com.betterhv.transfer.windows

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsNativeLoaderTest {
  @Test
  fun bundledLibraryIsExtractedToAFileJnaCanLoad() {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
    val cacheRoot = createTempDirectory("notelink-native-cache-").toFile()
    try {
      val path = JnaWindowsNativeApi.resolveLibraryPath(null, cacheRoot)
      val extracted = File(path)

      assertEquals("notelink_windows.dll", extracted.name)
      assertTrue(extracted.isFile)
      assertTrue(extracted.length() > 0)
    } finally {
      cacheRoot.deleteRecursively()
    }
  }

  @Test
  fun bundledLibraryIsCachedDeterministicallyBySha256AndReusedAcrossCalls() {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
    val cacheRoot = createTempDirectory("notelink-native-cache-").toFile()
    try {
      val firstPath = JnaWindowsNativeApi.resolveLibraryPath(null, cacheRoot)
      val secondPath = JnaWindowsNativeApi.resolveLibraryPath(null, cacheRoot)

      assertEquals(firstPath, secondPath)
      val extracted = File(firstPath)
      assertEquals(1, cacheRoot.listFiles()?.size ?: 0)
      assertEquals(64, extracted.parentFile.name.length)
      assertTrue(extracted.parentFile.name.all { it in '0'..'9' || it in 'a'..'f' })

      val expectedLength = extracted.length()
      extracted.writeText("corrupted")
      val repairedPath = JnaWindowsNativeApi.resolveLibraryPath(null, cacheRoot)
      assertEquals(firstPath, repairedPath)
      assertEquals(expectedLength, File(repairedPath).length())
    } finally {
      cacheRoot.deleteRecursively()
    }
  }

  @Test
  fun explicitLibraryPathRemainsUnchanged() {
    assertEquals(
      "C:\\test\\notelink_windows.dll",
      JnaWindowsNativeApi.resolveLibraryPath("C:\\test\\notelink_windows.dll"),
    )
  }
}
