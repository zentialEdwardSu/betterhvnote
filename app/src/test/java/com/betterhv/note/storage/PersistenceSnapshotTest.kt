package com.betterhv.note.storage

import com.betterhv.note.doc.CommandAction
import com.betterhv.note.doc.Notebook
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.commands.AddObjectCommand
import com.betterhv.note.doc.commands.AddPageCommand
import com.betterhv.note.doc.commands.SetPageBookmarkCommand
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PersistenceSnapshotTest {
  @Test
  fun commandSnapshotContainsOnlyAffectedObjectAndRepresentsUndoAsDelete() {
    val notebook = Notebook()
    val page = Page()
    notebook.addPage(page)
    val obj = StrokeObject(
      UUID.randomUUID(),
      stroke = Stroke(listOf(InkPoint(1f, 2f, 0.5f, 10L)), PenStyle()),
    )
    val command = AddObjectCommand(page, obj)
    command.execute()

    val afterExecute = DocumentChange.forCommand(notebook, command, CommandAction.EXECUTE)
    assertEquals(listOf(obj.id), afterExecute.objectChanges.map { it.objectId })
    assertEquals(obj, afterExecute.objectChanges.single().value)
    assertEquals(1, afterExecute.pages.size)

    command.undo()
    val afterUndo = DocumentChange.forCommand(notebook, command, CommandAction.UNDO)
    assertNull(afterUndo.objectChanges.single().value)

    // Undo history may retain a Page after the three-page cache detaches it;
    // redo still has to persist the object from the command's Page reference.
    notebook.detachPage(page.id)
    command.execute()
    val afterDetachedRedo = DocumentChange.forCommand(notebook, command, CommandAction.REDO)
    assertEquals(obj, afterDetachedRedo.objectChanges.single().value)
  }

  @Test
  fun nonStructuralCommandsEmitPageStructureChangedFalseAndOnlyAffectedPages() {
    val notebook = Notebook()
    val page1 = Page()
    val page2 = Page()
    notebook.addPage(page1)
    notebook.addPage(page2)

    val stroke = StrokeObject(
      UUID.randomUUID(),
      stroke = Stroke(listOf(InkPoint(1f, 2f, 0.5f, 10L)), PenStyle()),
    )
    val addStroke = AddObjectCommand(page1, stroke)
    addStroke.execute()
    val strokeChange = DocumentChange.forCommand(notebook, addStroke, CommandAction.EXECUTE)
    assertFalse(strokeChange.pageStructureChanged)
    assertEquals(emptyList<UUID>(), strokeChange.pageOrder)
    assertEquals(listOf(page1.id), strokeChange.pages.map { it.id })

    val bookmarkCmd = SetPageBookmarkCommand(page2, true)
    bookmarkCmd.execute()
    val bookmarkChange = DocumentChange.forCommand(notebook, bookmarkCmd, CommandAction.EXECUTE)
    assertFalse(bookmarkChange.pageStructureChanged)
    assertEquals(emptyList<UUID>(), bookmarkChange.pageOrder)
    assertEquals(listOf(page2.id), bookmarkChange.pages.map { it.id })

    val page3 = Page()
    val addPage = AddPageCommand(notebook, page3)
    addPage.execute()
    val structureChange = DocumentChange.forCommand(notebook, addPage, CommandAction.EXECUTE)
    assertTrue(structureChange.pageStructureChanged)
    assertEquals(listOf(page1.id, page2.id, page3.id), structureChange.pageOrder)
    assertEquals(3, structureChange.pages.size)
  }
}
