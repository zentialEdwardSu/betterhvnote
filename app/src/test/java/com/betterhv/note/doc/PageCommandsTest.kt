package com.betterhv.note.doc

import com.betterhv.note.doc.commands.AddPageCommand
import com.betterhv.note.doc.commands.DeletePageCommand
import com.betterhv.note.doc.commands.MovePageCommand
import com.betterhv.note.doc.commands.SetPageTemplateCommand
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

    @Test
    fun templateChangeIsUndoableAndDoesNotTouchScene() {
        val page = Page(templateId = DEFAULT_TEMPLATE_ID)
        val stack = CommandStack()
        val beforeRevision = page.contentRevision

        stack.execute(SetPageTemplateCommand(page, "custom.grid"))
        assertEquals("custom.grid", page.templateId)
        assertEquals(0, page.scene.size)
        assertEquals(beforeRevision + 1, page.contentRevision)

        stack.undo()
        assertEquals(DEFAULT_TEMPLATE_ID, page.templateId)
        assertEquals(0, page.scene.size)
    }

    @Test
    fun newPageTemplateInheritanceUsesPreviousWritablePageOrBlankAfterPdf() {
        val standard = Page(templateId = "custom.lines")
        val standardMetadata = Notebook.PageMetadata(
            standard.id, 300f, 400f, false, 0L, 1L, 1L,
            PageKind.BLANK, templateId = standard.templateId
        )
        val linkedMetadata = standardMetadata.copy(kind = PageKind.LINKED_NOTE, templateId = "custom.dots")
        val pdfMetadata = standardMetadata.copy(kind = PageKind.PDF_SOURCE, templateId = null)

        assertEquals("custom.lines", inheritedTemplateId(standardMetadata))
        assertEquals("custom.dots", inheritedTemplateId(linkedMetadata))
        assertEquals(DEFAULT_TEMPLATE_ID, inheritedTemplateId(pdfMetadata))
        assertEquals(DEFAULT_TEMPLATE_ID, inheritedTemplateId(null))
    }
}
