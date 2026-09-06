package com.betterhv.note.export

import com.betterhv.transfer.core.TransferLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ExportFileNamesTest {
  private val taskId = UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890")

  @Test fun usesNotebookTitleAndFirstEightTaskIdCharacters() {
    assertEquals("会议记录_abcdef12.pdf", ExportFileNames.displayName("会议记录", taskId, ExportFormat.PDF))
    assertEquals("会议记录_abcdef12.png", ExportFileNames.displayName("会议记录", taskId, ExportFormat.PNG))
  }

  @Test fun replacesIllegalCharactersAndFallsBackForBlankTitle() {
    assertEquals("a_b_c_abcdef12.pdf", ExportFileNames.displayName(" a/b:c ", taskId, ExportFormat.PDF))
    assertEquals("notebook_abcdef12.pdf", ExportFileNames.displayName("  ", taskId, ExportFormat.PDF))
  }

  @Test fun truncatesByUtf8BytesWithoutSplittingCodePoints() {
    val name = ExportFileNames.displayName("笔".repeat(80) + "😀", taskId, ExportFormat.PDF)
    assertTrue(name.endsWith("_abcdef12.pdf"))
    assertTrue(name.encodeToByteArray().size <= TransferLimits.MAX_EXPORT_NAME_BYTES)
    assertFalse(name.contains('\uFFFD'))
  }
}
