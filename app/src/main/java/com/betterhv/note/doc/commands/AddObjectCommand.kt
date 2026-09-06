package com.betterhv.note.doc.commands

import com.betterhv.note.doc.Command
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.PageObject

class AddObjectCommand(private val page: Page, private val obj: PageObject) : Command {
  override val affectedObjects = mapOf(page.id to setOf(obj.id))
  override fun currentObject(pageId: java.util.UUID, objectId: java.util.UUID): PageObject? =
    if (pageId == page.id) page.getObject(objectId) else null
  override fun currentPage(pageId: java.util.UUID): Page? = if (pageId == page.id) page else null

  override fun execute() {
    page.addObject(obj)
  }

  override fun undo() {
    page.removeObject(obj.id)
  }
}
