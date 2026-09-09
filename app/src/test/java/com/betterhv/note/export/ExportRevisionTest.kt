package com.betterhv.note.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

class ExportRevisionTest {
  @Test fun `pdf page cache name is renderer-versioned`() {
    val source = ExportPageSource(UUID.fromString("00000000-0000-0000-0000-000000000123"), 42L, 0)

    assertEquals(6, ExportTaskRepository.RENDERER_VERSION)
    assertEquals(
      "00000000-0000-0000-0000-000000000123-r42-be3b0c44298fc-v6.pdf",
      ExportTaskRepository.pageCacheFileName(source),
    )
  }

  @Test fun backgroundChangeInvalidatesFingerprintAndPageCacheName() {
    val page = ExportPageSource(first, 4, 0, "template-a")
    val changed = page.copy(backgroundRevision = "template-b")
    assertNotEquals(
      ExportRevision.fingerprint(ExportFormat.PDF, listOf(page), 5),
      ExportRevision.fingerprint(ExportFormat.PDF, listOf(changed), 5),
    )
    assertNotEquals(
      ExportTaskRepository.pageCacheFileName(page),
      ExportTaskRepository.pageCacheFileName(changed),
    )
  }

  private val first = UUID.randomUUID()
  private val second = UUID.randomUUID()

  @Test fun revisionAndOrderChangeFingerprint() {
    val original = listOf(ExportPageSource(first, 1, 0), ExportPageSource(second, 2, 1))
    assertNotEquals(
      ExportRevision.fingerprint(ExportFormat.PDF, original, 2),
      ExportRevision.fingerprint(ExportFormat.PDF, original.reversed(), 2),
    )
    assertNotEquals(
      ExportRevision.fingerprint(ExportFormat.PDF, original, 2),
      ExportRevision.fingerprint(
        ExportFormat.PDF,
        original.mapIndexed { index, page ->
          if (index == 1) page.copy(contentRevision = 3) else page
        },
        2
      ),
    )
  }

  @Test fun staleCountOnlyIncludesMissingOrChangedPages() {
    val sources = listOf(ExportPageSource(first, 4, 0), ExportPageSource(second, 8, 1))
    assertEquals(0, ExportRevision.stalePageCount(sources, mapOf(first to 4L, second to 8L)))
    assertEquals(1, ExportRevision.stalePageCount(sources, mapOf(first to 4L, second to 7L)))
    assertEquals(2, ExportRevision.stalePageCount(sources, emptyMap()))
  }

  @Test fun sourceMissingTakesPriorityOverOldArtifact() {
    assertEquals(
      ExportTaskState.SOURCE_MISSING,
      ExportRevision.state(true, true, "old", "new", true),
    )
    assertEquals(
      ExportTaskState.OUTDATED,
      ExportRevision.state(false, true, "old", "new", false),
    )
  }
}
