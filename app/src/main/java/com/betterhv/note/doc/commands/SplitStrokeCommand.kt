package com.betterhv.note.doc.commands

import com.betterhv.note.doc.Command
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.PageObject
import com.betterhv.note.doc.StrokeObject
import java.util.UUID

/**
 * Point Eraser (spec §34-35): removes the original stroke and adds the pieces
 * it was split into. Undo removes the pieces and restores the exact original
 * object (snapshotted, same as [DeleteObjectsCommand]).
 */
class SplitStrokeCommand(private val page: Page, private val originalId: UUID, private val pieces: List<StrokeObject>) :
  Command {
  override val affectedObjects = mapOf(page.id to (setOf(originalId) + pieces.map { it.id }))
  override fun currentObject(pageId: UUID, objectId: UUID): PageObject? =
    if (pageId == page.id) page.getObject(objectId) else null
  override fun currentPage(pageId: UUID): Page? = if (pageId == page.id) page else null

  private var originalSnapshot: PageObject? = null

  override fun execute() {
    originalSnapshot = page.removeObject(originalId)
    for (piece in pieces) page.addObject(piece)
  }

  override fun undo() {
    for (piece in pieces) page.removeObject(piece.id)
    originalSnapshot?.let { page.addObject(it) }
  }
}
