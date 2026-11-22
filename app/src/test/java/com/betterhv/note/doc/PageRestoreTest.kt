package com.betterhv.note.doc

import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PageRestoreTest {
  @Test
  fun restoreObjectInsertsCachedBoundsIntoSpatialIndexWithoutMatchingOutsideBounds() {
    val page = Page()
    val stroke = Stroke(
      points = listOf(InkPoint(700f, 700f, 0.5f, 1L), InkPoint(720f, 720f, 0.5f, 2L)),
      style = PenStyle(),
    )
    val strokeObj = StrokeObject(id = UUID.randomUUID(), stroke = stroke)
    val cachedBounds = Bounds(10f, 10f, 40f, 40f)

    page.restoreObject(strokeObj, cachedBounds)

    assertEquals(listOf(strokeObj), page.queryObjects(Bounds(15f, 15f, 25f, 25f)))
    assertTrue(page.queryObjects(Bounds(695f, 695f, 725f, 725f)).isEmpty())
  }

  @Test
  fun restoreObjectDefaultsToObjectPageBounds() {
    val page = Page()
    val textObj = TextObject(
      id = UUID.randomUUID(),
      transform = Transform2D.translate(700f, 700f),
      text = "hello",
      localBounds = Bounds(0f, 0f, 50f, 20f),
    )

    page.restoreObject(textObj)

    assertEquals(listOf(textObj), page.queryObjects(Bounds(710f, 705f, 720f, 715f)))
    assertTrue(page.queryObjects(Bounds(0f, 0f, 50f, 20f)).isEmpty())
  }
}
