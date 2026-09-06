package com.betterhv.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateUiStateTest {
  private val info = UpdateInfo(
    currentVersion = requireNotNull(AppVersion.parse("1.0.0")),
    latestVersion = requireNotNull(AppVersion.parse("1.1.0")),
    releaseTitle = "Release",
    releaseUrl = "https://github.com/zentialEdwardSu/betterhvnote/releases/tag/note-v1.1.0",
  )

  @Test
  fun `automatic failure is not user visible`() {
    val state = UpdateUiState().checking(manual = false)
      .completed(UpdateCheckState.Failed("offline"), manual = false)
    assertFalse(state.manualErrorVisible)
    assertNull(state.visibleUpdate)
  }

  @Test
  fun `manual failure is visible`() {
    val state = UpdateUiState().checking(manual = true)
      .completed(UpdateCheckState.Failed("offline"), manual = true)
    assertTrue(state.manualErrorVisible)
  }

  @Test
  fun `dismissal lasts until a new available result`() {
    val available = UpdateCheckState.UpdateAvailable(info)
    val dismissed = UpdateUiState().completed(available, manual = false).dismissBanner()
    assertNull(dismissed.visibleUpdate)
    assertNotNull(dismissed.completed(available, manual = true).visibleUpdate)
  }
}
