package com.betterhv.note.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ExportPageCacheKeyTest {
  private val source = ExportPageSource(
    id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
    contentRevision = 7L,
    position = 0,
    backgroundRevision = "template:a",
  )

  @Test fun keyIsStableForIdenticalInputs() {
    assertEquals(
      ExportPageCacheKey.fileName(source, ExportRenderMode.FAST, 3),
      ExportPageCacheKey.fileName(source, ExportRenderMode.FAST, 3),
    )
    assertTrue(ExportPageCacheKey.fileName(source, ExportRenderMode.FAST, 3).endsWith("-v3-fast.pdf"))
  }

  @Test fun modeRevisionBackgroundAndRendererAreIsolated() {
    val base = ExportPageCacheKey.fileName(source, ExportRenderMode.VECTOR, 3)
    assertNotEquals(base, ExportPageCacheKey.fileName(source, ExportRenderMode.FAST, 3))
    assertNotEquals(base, ExportPageCacheKey.fileName(source.copy(contentRevision = 8), ExportRenderMode.VECTOR, 3))
    assertNotEquals(
      base,
      ExportPageCacheKey.fileName(source.copy(backgroundRevision = "template:b"), ExportRenderMode.VECTOR, 3),
    )
    assertNotEquals(base, ExportPageCacheKey.fileName(source, ExportRenderMode.VECTOR, 4))
  }
}
