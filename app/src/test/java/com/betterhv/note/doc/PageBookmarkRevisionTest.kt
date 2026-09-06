package com.betterhv.note.doc

import com.betterhv.note.doc.commands.AddObjectCommand
import com.betterhv.note.doc.commands.SetPageBookmarkCommand
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PageBookmarkRevisionTest {
  @Test
  fun inkMutationsAdvanceRevisionButBookmarkDoesNot() {
    val page = Page()
    val initial = page.contentRevision
    val objectValue = StrokeObject(
      id = UUID.randomUUID(),
      stroke = Stroke(listOf(InkPoint(1f, 2f, 0.5f, 10L)), PenStyle()),
    )
    page.addObject(objectValue)
    assertTrue(page.contentRevision > initial)
    val afterInk = page.contentRevision

    page.setBookmarked(true)
    assertTrue(page.bookmarked)
    assertEquals(afterInk, page.contentRevision)
  }

  @Test
  fun bookmarkCommandSupportsUndoAndRedoWithoutChangingInkRevision() {
    val page = Page()
    val stack = CommandStack()
    val revision = page.contentRevision
    stack.execute(SetPageBookmarkCommand(page, true))
    assertTrue(page.bookmarked)
    stack.undo()
    assertFalse(page.bookmarked)
    stack.redo()
    assertTrue(page.bookmarked)
    assertEquals(revision, page.contentRevision)
  }

  @Test
  fun commandUndoAlsoAdvancesContentRevision() {
    val page = Page()
    val objectValue = StrokeObject(
      id = UUID.randomUUID(),
      stroke = Stroke(listOf(InkPoint(0f, 0f, 1f, 1L)), PenStyle()),
    )
    val stack = CommandStack()
    stack.execute(AddObjectCommand(page, objectValue))
    val afterAdd = page.contentRevision
    stack.undo()
    assertTrue(page.contentRevision > afterAdd)
  }
}
