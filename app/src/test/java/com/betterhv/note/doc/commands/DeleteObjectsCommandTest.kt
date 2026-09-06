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

private fun strokeObjectAt(x: Float, y: Float): StrokeObject {
  val stroke = Stroke(
    points = listOf(InkPoint(x, y, 0.5f, 0L), InkPoint(x + 10f, y, 0.5f, 1L)),
    style = PenStyle(),
  )
  return StrokeObject(id = UUID.randomUUID(), transform = Transform2D.IDENTITY, stroke = stroke)
}

class DeleteObjectsCommandTest {

  @Test
  fun `execute removes the objects from the page`() {
    val page = Page()
    val obj = strokeObjectAt(0f, 0f)
    page.addObject(obj)
    val cmd = DeleteObjectsCommand(page, listOf(obj.id))
    cmd.execute()
    assertNull(page.getObject(obj.id))
  }

  @Test
  fun `undo restores the exact removed object`() {
    val page = Page()
    val obj = strokeObjectAt(0f, 0f)
    page.addObject(obj)
    val cmd = DeleteObjectsCommand(page, listOf(obj.id))
    cmd.execute()
    cmd.undo()
    val restored = page.getObject(obj.id)
    assertNotNull(restored)
    assertEquals(obj, restored)
  }

  @Test
  fun `deleted object no longer appears in a spatial query`() {
    val page = Page()
    val obj = strokeObjectAt(0f, 0f)
    page.addObject(obj)
    DeleteObjectsCommand(page, listOf(obj.id)).execute()
    val hits = page.queryObjects(obj.pageBounds)
    assert(obj.id !in hits.map { it.id })
  }
}
