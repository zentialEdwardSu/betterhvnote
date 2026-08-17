package com.betterhv.note.doc

import com.betterhv.note.doc.commands.AddPageCommand
import com.betterhv.note.doc.commands.DeletePageCommand
import com.betterhv.note.doc.commands.MovePageCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PageCommandsTest {
    @Test
    fun addDeleteMoveAreUndoableWithoutChangingPageIdentity() {
        val notebook = Notebook()
        val first = Page()
        val second = Page()
        notebook.addPage(first)
        notebook.addPage(second)
        val stack = CommandStack()

        stack.execute(MovePageCommand(notebook, second.id, 0))
        assertEquals(listOf(second.id, first.id), notebook.pageOrder)
        stack.undo()
        assertEquals(listOf(first.id, second.id), notebook.pageOrder)

        stack.execute(DeletePageCommand(notebook, first.id))
        assertEquals(listOf(second.id), notebook.pageOrder)
        stack.undo()
        assertSame(first, notebook.getPage(first.id))
        assertEquals(listOf(first.id, second.id), notebook.pageOrder)

        val third = Page()
        stack.execute(AddPageCommand(notebook, third, 1))
        assertEquals(listOf(first.id, third.id, second.id), notebook.pageOrder)
        stack.undo()
        assertEquals(listOf(first.id, second.id), notebook.pageOrder)
    }
}
