package com.betterhv.note.doc

import java.util.UUID

/**
 * Ordered container of [PageObject]s for one [Page]. Spec §8-9: rendering walks
 * this in order for correct z-stacking; the spatial index (kept in sync by
 * [Page]) is what makes lookups by area fast, not this list.
 */
class Scene {
  private val objects = ArrayList<PageObject>()
  private val byId = HashMap<UUID, Int>()

  fun add(obj: PageObject) {
    byId[obj.id] = objects.size
    objects.add(obj)
  }

  fun removeById(id: UUID): PageObject? {
    val index = byId.remove(id) ?: return null
    val removed = objects.removeAt(index)
    // Shift every id after the removed slot down by one.
    for (i in index until objects.size) {
      byId[objects[i].id] = i
    }
    return removed
  }

  fun getById(id: UUID): PageObject? {
    val index = byId[id] ?: return null
    return objects[index]
  }

  /** Replace the object at [id]'s slot in place, preserving z-order. */
  fun replace(id: UUID, updated: PageObject) {
    val index = byId[id] ?: return
    objects[index] = updated
    if (updated.id != id) {
      byId.remove(id)
      byId[updated.id] = index
    }
  }

  fun all(): List<PageObject> = objects

  val size: Int get() = objects.size

  fun clear() {
    objects.clear()
    byId.clear()
  }
}
