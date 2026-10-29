package com.betterhv.note.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportProgressPresentationTest {
  @Test fun renderingAssemblingAndSendingUseDeterminateProgress() {
    assertEquals(
      0.25f,
      ExportProgress(1, 4, null, ExportProgress.Stage.RENDERING).determinateFraction(),
    )
    assertEquals(
      0.5f,
      ExportProgress(15, 30, null, ExportProgress.Stage.ASSEMBLING).determinateFraction(),
    )
    assertEquals(
      1f,
      ExportProgress(12, 10, null, ExportProgress.Stage.SENDING).determinateFraction(),
    )
  }

  @Test fun unmeteredStagesRemainIndeterminate() {
    assertNull(ExportProgress(0, 0, null, ExportProgress.Stage.PREPARING).determinateFraction())
    assertNull(ExportProgress(0, 0, null, ExportProgress.Stage.RENDERING).determinateFraction())
    assertNull(ExportProgress(0, 1, null, ExportProgress.Stage.WRITING).determinateFraction())
    assertNull(ExportProgress(0, 1, null, ExportProgress.Stage.VERIFYING).determinateFraction())
    assertNull(ExportProgress(0, 1, null, ExportProgress.Stage.CANCELLING).determinateFraction())
  }

  @Test fun remainingTimeUsesStageElapsedAndProcessedPages() {
    assertEquals(
      8000L,
      ExportProgress(
        current = 2,
        total = 10,
        pageId = null,
        stage = ExportProgress.Stage.RENDERING,
        elapsedMs = 5_000,
        stageElapsedMs = 2_000,
      ).remainingMs(),
    )
    assertNull(
      ExportProgress(0, 10, null, ExportProgress.Stage.RENDERING, stageElapsedMs = 900).remainingMs(),
    )
    assertNull(
      ExportProgress(10, 10, null, ExportProgress.Stage.RENDERING, stageElapsedMs = 900).remainingMs(),
    )
    assertNull(
      ExportProgress(2, 10, null, ExportProgress.Stage.WRITING, stageElapsedMs = 900).remainingMs(),
    )
  }

  @Test fun durationsUseCompactSecondsAndMinutes() {
    assertEquals("0.0s", formatDuration(0))
    assertEquals("12.3s", formatDuration(12_300))
    assertEquals("1m 05s", formatDuration(65_000))
    assertEquals("2m 00s", formatDuration(120_000))
  }
}
