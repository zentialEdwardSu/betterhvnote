package com.betterhv.note.storage

import com.betterhv.note.doc.CommandAction
import com.betterhv.note.doc.Notebook
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.commands.AddObjectCommand
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
