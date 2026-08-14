package com.betterhv.note.doc.commands

import com.betterhv.note.doc.Command
import com.betterhv.note.doc.Notebook
import com.betterhv.note.doc.Page
import java.util.UUID

/**
 * Spec §42's page-level V1 commands. Not exercised by the UI until multi-page
 * navigation (§88 Phase 6) lands, but implemented for real now so nothing here
 * needs redoing later.
 */
class AddPageCommand(
    private val notebook: Notebook,
    private val page: Page = Page(),
    private val index: Int = notebook.pageOrder.size
) : Command {
    override fun execute() {
        notebook.addPage(page, index)
    }

    override fun undo() {
        notebook.removePage(page.id)
    }
}

class DeletePageCommand(
    private val notebook: Notebook,
    private val pageId: UUID
) : Command {
    private var removedPage: Page? = null
    private var removedIndex: Int = -1

    override fun execute() {
        removedIndex = notebook.pageOrder.indexOf(pageId)
        removedPage = notebook.removePage(pageId)
    }

    override fun undo() {
        val page = removedPage ?: return
        if (removedIndex >= 0) notebook.addPage(page, removedIndex)
    }
}

class MovePageCommand(
    private val notebook: Notebook,
    private val pageId: UUID,
    private val toIndex: Int
) : Command {
    private var fromIndex: Int = -1

    override fun execute() {
        fromIndex = notebook.pageOrder.indexOf(pageId)
        notebook.movePage(pageId, toIndex)
    }

    override fun undo() {
        if (fromIndex >= 0) notebook.movePage(pageId, fromIndex)
    }
}
