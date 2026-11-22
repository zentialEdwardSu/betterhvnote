package com.betterhv.note.doc.commands

import com.betterhv.note.doc.Command
import com.betterhv.note.doc.Page

/** Persistent, undoable page bookmark toggle. Bookmarking does not change ink revision. */
class SetPageBookmarkCommand(private var page: Page, private val bookmarked: Boolean) : Command {
  override val affectedPages = setOf(page.id)
  private val previous = page.bookmarked
  override fun currentPage(pageId: java.util.UUID): Page? = if (pageId == page.id) page else null
  override fun rebindPage(livePage: Page) {
    if (livePage.id == page.id) page = livePage
  }

  override fun execute() = page.setBookmarked(bookmarked)

  override fun undo() = page.setBookmarked(previous)
}
