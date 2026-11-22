package com.betterhv.note.doc

import com.betterhv.note.doc.commands.AddObjectCommand
import com.betterhv.note.doc.commands.AddPageCommand
import com.betterhv.note.doc.commands.CompositeCommand
import com.betterhv.note.doc.commands.DeleteObjectsCommand
import com.betterhv.note.doc.commands.DeletePageCommand
import com.betterhv.note.doc.commands.MovePageCommand
import com.betterhv.note.doc.commands.SetPageBookmarkCommand
import com.betterhv.note.doc.commands.SetPageTemplateCommand
import com.betterhv.note.doc.commands.SplitStrokeCommand
import com.betterhv.note.doc.commands.TransformObjectsCommand
import com.betterhv.note.doc.commands.UpdateObjectCommand
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.Stroke
import com.betterhv.note.storage.DocumentChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

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
      PageKind.BLANK, templateId = standard.templateId,
    )
    val linkedMetadata = standardMetadata.copy(kind = PageKind.LINKED_NOTE, templateId = "custom.dots")
    val pdfMetadata = standardMetadata.copy(kind = PageKind.PDF_SOURCE, templateId = null)

    assertEquals("custom.lines", inheritedTemplateId(standardMetadata))
    assertEquals("custom.dots", inheritedTemplateId(linkedMetadata))
    assertEquals(DEFAULT_TEMPLATE_ID, inheritedTemplateId(pdfMetadata))
    assertEquals(DEFAULT_TEMPLATE_ID, inheritedTemplateId(null))
  }

  @Test
  fun addPageUndoAndRedoAfterDetachmentMaintainsPageOrderAndMetadata() {
    val notebook = Notebook()
    val first = Page()
    val second = Page()
    notebook.addPage(first)
    val stack = CommandStack()
    val addCmd = AddPageCommand(notebook, second, 1)
    stack.execute(addCmd)
    assertEquals(listOf(first.id, second.id), notebook.pageOrder)

    notebook.detachPage(second.id)
    assertNull(notebook.getPage(second.id))

    stack.undo()
    assertFalse(second.id in notebook.pageOrder)
    assertNull(notebook.metadata(second.id))
    val undoChange = DocumentChange.forCommand(notebook, addCmd, CommandAction.UNDO)
    assertEquals(listOf(first.id), undoChange.pageOrder)
    assertEquals(listOf(first.id), undoChange.pages.map { it.id })
    assertTrue(undoChange.fullPages.isEmpty())

    stack.redo()
    assertEquals(listOf(first.id, second.id), notebook.pageOrder)
    assertEquals(1, notebook.pageOrder.count { it == second.id })
    assertNotNull(notebook.metadata(second.id))
  }

  @Test
  fun rebindPageRoutesUndoAndRedoToReloadedLivePage() {
    val pageId = UUID.randomUUID()
    val initialPage = Page(id = pageId, templateId = DEFAULT_TEMPLATE_ID)
    val stroke = StrokeObject(
      UUID.randomUUID(),
      stroke = Stroke(listOf(InkPoint(0f, 0f, 0.5f, 1L)), PenStyle()),
    )
    val textBefore = TextObject(
      UUID.randomUUID(),
      text = "before",
      localBounds = Bounds(0f, 0f, 40f, 20f),
    )
    val textAfter = textBefore.copy(text = "after")
    initialPage.addObject(textBefore)

    val addCmd = AddObjectCommand(initialPage, stroke)
    addCmd.execute()
    val updateCmd = UpdateObjectCommand(initialPage, textBefore, textAfter)
    updateCmd.execute()
    val transformCmd = TransformObjectsCommand(
      initialPage,
      listOf(textBefore.id),
      mapOf(textBefore.id to Transform2D.IDENTITY),
      mapOf(textBefore.id to Transform2D.translate(15f, 25f)),
    )
    transformCmd.execute()
    val bookmarkCmd = SetPageBookmarkCommand(initialPage, true)
    bookmarkCmd.execute()
    val templateCmd = SetPageTemplateCommand(initialPage, "custom.grid")
    templateCmd.execute()

    val piece1 = StrokeObject(
      UUID.randomUUID(),
      stroke = Stroke(listOf(InkPoint(1f, 1f, 0.5f, 2L)), PenStyle()),
    )
    val splitCmd = SplitStrokeCommand(initialPage, stroke.id, listOf(piece1))
    splitCmd.execute()
    val deleteCmd = DeleteObjectsCommand(initialPage, listOf(piece1.id))
    deleteCmd.execute()
    val composite = CompositeCommand(listOf(bookmarkCmd, templateCmd))

    // Simulate page eviction and reload into a new Page instance.
    val reloadedPage = Page(id = pageId, bookmarked = true, templateId = "custom.grid")
    reloadedPage.restoreObject(textAfter.copy(transform = Transform2D.translate(15f, 25f)))

    deleteCmd.rebindPage(reloadedPage)
    deleteCmd.undo()
    assertNotNull(reloadedPage.getObject(piece1.id))

    splitCmd.rebindPage(reloadedPage)
    splitCmd.undo()
    assertNull(reloadedPage.getObject(piece1.id))
    assertNotNull(reloadedPage.getObject(stroke.id))

    addCmd.rebindPage(reloadedPage)
    addCmd.undo()
    assertNull(reloadedPage.getObject(stroke.id))
    assertNull(initialPage.getObject(stroke.id))

    transformCmd.rebindPage(reloadedPage)
    transformCmd.undo()
    assertEquals(Transform2D.IDENTITY, reloadedPage.getObject(textBefore.id)?.transform)

    updateCmd.rebindPage(reloadedPage)
    updateCmd.undo()
    assertEquals("before", (reloadedPage.getObject(textBefore.id) as TextObject).text)

    composite.rebindPage(reloadedPage)
    composite.undo()
    assertFalse(reloadedPage.bookmarked)
    assertEquals(DEFAULT_TEMPLATE_ID, reloadedPage.templateId)

    val notebook = Notebook()
    val added = Page()
    val addPageCmd = AddPageCommand(notebook, added)
    addPageCmd.execute()
    val reloadedAdded = Page(id = added.id)
    addPageCmd.rebindPage(reloadedAdded)
    addPageCmd.undo()
    addPageCmd.execute()
    assertSame(reloadedAdded, notebook.getPage(added.id))
  }

  @Test
  fun deletePageWithFallbackProviderSucceedsAfterEviction() {
    val notebook = Notebook()
    val first = Page()
    val second = Page()
    notebook.addPage(first)
    notebook.addPage(second)
    notebook.detachPage(second.id)
    assertNull(notebook.getPage(second.id))

    val reloadedSecond = Page(id = second.id)
    val stack = CommandStack()
    stack.execute(
      DeletePageCommand(notebook, second.id) { id ->
        if (id == second.id) reloadedSecond else null
      },
    )
    assertEquals(listOf(first.id), notebook.pageOrder)
    assertNull(notebook.metadata(second.id))

    stack.undo()
    assertEquals(listOf(first.id, second.id), notebook.pageOrder)
    assertSame(reloadedSecond, notebook.getPage(second.id))
  }
}
