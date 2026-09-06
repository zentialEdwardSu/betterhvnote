package com.betterhv.note.doc.commands

import com.betterhv.note.doc.Command
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.PageObject
import java.util.UUID

/** Replaces one object's content/style as one undoable operation. */
class UpdateObjectCommand(private val page: Page, private val before: PageObject, private val after: PageObject) :
  Command {
  init {
    require(before.id == after.id) { "Object identity must not change" }
  }

  override val affectedObjects = mapOf(page.id to setOf(before.id))
  override fun currentObject(pageId: UUID, objectId: UUID): PageObject? =
    if (pageId == page.id && objectId == before.id) page.getObject(objectId) else null
  override fun currentPage(pageId: UUID): Page? = if (pageId == page.id) page else null

  override fun execute() {
    page.updateObject(before.id, after)
  }

  override fun undo() {
    page.updateObject(before.id, before)
  }
}
