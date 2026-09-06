package com.betterhv.note.doc.commands

import com.betterhv.note.doc.Command
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.PageObject
import java.util.UUID

/** Spec §40: snapshots the removed objects so undo can restore them exactly. */
class DeleteObjectsCommand(private val page: Page, private val ids: List<UUID>) : Command {
  override val affectedObjects = mapOf(page.id to ids.toSet())
  override fun currentObject(pageId: UUID, objectId: UUID): PageObject? =
    if (pageId == page.id) page.getObject(objectId) else null
  override fun currentPage(pageId: UUID): Page? = if (pageId == page.id) page else null

  private var snapshots: List<PageObject> = emptyList()

  override fun execute() {
    snapshots = ids.mapNotNull { page.removeObject(it) }
  }

  override fun undo() {
    for (obj in snapshots) page.addObject(obj)
  }
}
