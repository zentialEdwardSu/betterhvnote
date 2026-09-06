package com.betterhv.note.doc.commands

import com.betterhv.note.doc.Command
import com.betterhv.note.doc.Page
import java.util.UUID

/** Replaces only the page background reference; Scene objects are untouched. */
class SetPageTemplateCommand(private val page: Page, private val templateId: String) : Command {
  private val previous = requireNotNull(page.templateId)
  override val affectedPages: Set<UUID> = setOf(page.id)
  override fun currentPage(pageId: UUID): Page? = page.takeIf { it.id == pageId }

  override fun execute() = page.setTemplate(templateId)
  override fun undo() = page.setTemplate(previous)
}
