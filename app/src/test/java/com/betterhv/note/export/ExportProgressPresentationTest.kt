package com.betterhv.note.export

import com.betterhv.note.determinateFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportProgressPresentationTest {
  @Test fun renderingAndSendingUseDeterminateProgressWhenTotalIsKnown() {
    assertEquals(
      0.25f,
      ExportProgress(1, 4, null, ExportProgress.Stage.RENDERING).determinateFraction(),
    )
    assertEquals(
      1f,
      ExportProgress(12, 10, null, ExportProgress.Stage.SENDING).determinateFraction(),
    )
  }

  @Test fun stageOnlyAndUnknownTotalsRemainIndeterminate() {
    assertNull(ExportProgress(0, 0, null, ExportProgress.Stage.PREPARING).determinateFraction())
    assertNull(ExportProgress(0, 0, null, ExportProgress.Stage.RENDERING).determinateFraction())
    assertNull(ExportProgress(0, 1, null, ExportProgress.Stage.ASSEMBLING).determinateFraction())
  }
}
