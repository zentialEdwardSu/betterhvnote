package com.betterhv.note.export

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExportRevisionTest {
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()

    @Test fun revisionAndOrderChangeFingerprint() {
        val original = listOf(ExportPageSource(first, 1, 0), ExportPageSource(second, 2, 1))
        assertNotEquals(
            ExportRevision.fingerprint(ExportFormat.PDF, original, 2),
            ExportRevision.fingerprint(ExportFormat.PDF, original.reversed(), 2)
        )
        assertNotEquals(
            ExportRevision.fingerprint(ExportFormat.PDF, original, 2),
            ExportRevision.fingerprint(ExportFormat.PDF, original.mapIndexed { index, page ->
                if (index == 1) page.copy(contentRevision = 3) else page
            }, 2)
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
            ExportRevision.state(true, true, "old", "new", true)
        )
        assertEquals(
            ExportTaskState.OUTDATED,
            ExportRevision.state(false, true, "old", "new", false)
        )
    }
}
