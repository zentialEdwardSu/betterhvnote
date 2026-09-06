package com.betterhv.note.doc.commands

import com.betterhv.note.doc.Page
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class SplitStrokeCommandTest {

  private fun buildOriginal(): StrokeObject {
    val stroke = Stroke(
      points = listOf(
        InkPoint(0f, 0f, 0.5f, 0L),
        InkPoint(10f, 0f, 0.5f, 1L),
        InkPoint(20f, 0f, 0.5f, 2L),
      ),
      style = PenStyle(),
    )
    return StrokeObject(id = UUID.randomUUID(), transform = Transform2D.IDENTITY, stroke = stroke)
  }

  private fun piece(original: StrokeObject, range: IntRange): StrokeObject {
    val points = original.stroke.points.slice(range)
    return StrokeObject(
      id = UUID.randomUUID(),
      transform = original.transform,
      stroke = Stroke(points = points, style = original.stroke.style),
    )
  }

  @Test
  fun `execute removes the original and adds the pieces`() {
    val page = Page()
    val original = buildOriginal()
    page.addObject(original)
    val pieces = listOf(piece(original, 0..0), piece(original, 2..2))

    val cmd = SplitStrokeCommand(page, original.id, pieces)
    cmd.execute()

    assertNull(page.getObject(original.id))
    for (p in pieces) assertNotNull(page.getObject(p.id))
    assertEquals(pieces.size, page.scene.size)
  }

  @Test
  fun `undo removes the pieces and restores the exact original`() {
    val page = Page()
    val original = buildOriginal()
    page.addObject(original)
    val pieces = listOf(piece(original, 0..0), piece(original, 2..2))

    val cmd = SplitStrokeCommand(page, original.id, pieces)
    cmd.execute()
    cmd.undo()

    assertEquals(original, page.getObject(original.id))
    for (p in pieces) assertNull(page.getObject(p.id))
    assertEquals(1, page.scene.size)
  }
}
