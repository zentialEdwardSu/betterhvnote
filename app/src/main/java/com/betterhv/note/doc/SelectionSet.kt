package com.betterhv.note.doc

import com.betterhv.note.ink.Bounds
import java.util.UUID

/**
 * Spec §36-37: a lightweight selection -- object ids and their combined bounds
 * only, no copies of the objects themselves. Not a [Command]/undoable; only
 * the edits performed while a selection is active are.
 */
data class SelectionSet(val objectIds: List<UUID>, val bounds: Bounds) {
  val isEmpty: Boolean get() = objectIds.isEmpty()

  companion object {
    val EMPTY = SelectionSet(emptyList(), Bounds(0f, 0f, 0f, 0f))

    fun of(objects: List<PageObject>): SelectionSet {
      if (objects.isEmpty()) return EMPTY
      var bounds = objects.first().pageBounds
      for (i in 1 until objects.size) bounds = bounds.union(objects[i].pageBounds)
      return SelectionSet(objects.map { it.id }, bounds)
    }
  }
}
