package com.betterhv.note.doc.commands

import com.betterhv.note.doc.Command
import com.betterhv.note.doc.PageObject
import java.util.UUID

/**
 * Bundles Commands that were each already executed live (one per hit, as a
 * gesture like an eraser drag crossed each object) into a single undo/redo
 * step, so one undo reverts the whole gesture rather than one entry per
 * object touched (spec §44's "one undo per drag" intent). Pushed via
 * [com.betterhv.note.doc.CommandStack.push] since the operations already ran.
 */
class CompositeCommand(private val operations: List<Command>) : Command {
  override val affectedObjects: Map<java.util.UUID, Set<java.util.UUID>> =
    operations.flatMap { it.affectedObjects.entries }
      .groupBy({ it.key }, { it.value })
      .mapValues { (_, values) -> values.flatten().toSet() }
  override val affectedPages: Set<java.util.UUID> = operations.flatMap { it.affectedPages }.toSet()
  override val changesPageStructure: Boolean = operations.any { it.changesPageStructure }
  override fun currentObject(pageId: UUID, objectId: UUID): PageObject? {
    for (operation in operations) {
      if (objectId in operation.affectedObjects[pageId].orEmpty()) {
        return operation.currentObject(pageId, objectId)
      }
    }
    return null
  }
  override fun currentPage(pageId: UUID) = operations.firstNotNullOfOrNull { it.currentPage(pageId) }

  override fun execute() {
    for (op in operations) op.execute()
  }

  override fun undo() {
    for (op in operations.asReversed()) op.undo()
  }
}
