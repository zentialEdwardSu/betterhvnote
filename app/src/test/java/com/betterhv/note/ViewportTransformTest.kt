package com.betterhv.note

import com.betterhv.note.canvas.PdfViewportMath
import com.betterhv.note.canvas.PdfViewportState
import com.betterhv.note.canvas.ViewportTransform
import com.betterhv.note.doc.UniformGridSpatialIndex
import com.betterhv.note.ink.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ViewportTransformTest {
  @Test
  fun fitLetterPageAndRoundTripCoordinates() {
    val transform = ViewportTransform.fit(800f, 1200f, 1600, 2560)
    assertEquals(2f, transform.scale, 0.0001f)
    assertEquals(0f, transform.offsetX, 0.0001f)
    assertEquals(80f, transform.offsetY, 0.0001f)

    val screen = transform.pageToScreen(320f, 440f)
    val page = transform.screenToPage(screen[0], screen[1])
    assertEquals(320f, page[0], 0.0001f)
    assertEquals(440f, page[1], 0.0001f)
    assertEquals(
      Bounds(200f, 280f, 600f, 680f),
      transform.pageToScreen(Bounds(100f, 100f, 300f, 300f)),
    )
  }

  @Test
  fun zoomedAndPannedDirtyRectInversionQueriesMatchingSpatialCells() {
    val transform = PdfViewportMath.transform(
      state = PdfViewportState(zoom = 2.5f, panX = -320f, panY = -480f, revision = 1),
      pageWidth = 1000f,
      pageHeight = 1400f,
      viewWidth = 1404,
      viewHeight = 1872,
    )
    val targetId = UUID.randomUUID()
    val distantId = UUID.randomUUID()
    val targetBounds = Bounds(620f, 710f, 700f, 790f)
    val distantBounds = Bounds(20f, 30f, 90f, 100f)

    val index = UniformGridSpatialIndex()
    index.insert(targetId, targetBounds)
    index.insert(distantId, distantBounds)

    val screenDirty = transform.pageToScreen(targetBounds)
    val topLeft = transform.screenToPage(screenDirty.left, screenDirty.top)
    val bottomRight = transform.screenToPage(screenDirty.right, screenDirty.bottom)
    val pageDirtyBounds = Bounds(
      minOf(topLeft[0], bottomRight[0]),
      minOf(topLeft[1], bottomRight[1]),
      maxOf(topLeft[0], bottomRight[0]),
      maxOf(topLeft[1], bottomRight[1]),
    )

    assertEquals(targetBounds.left, pageDirtyBounds.left, 0.001f)
    assertEquals(targetBounds.top, pageDirtyBounds.top, 0.001f)
    assertEquals(targetBounds.right, pageDirtyBounds.right, 0.001f)
    assertEquals(targetBounds.bottom, pageDirtyBounds.bottom, 0.001f)

    val hits = index.query(pageDirtyBounds)
    assertTrue(targetId in hits)
    assertFalse(distantId in hits)
  }
}
