package com.betterhv.note.doc

import com.betterhv.note.ink.Bounds
import java.util.UUID

/**
 * Spec §8: a page is purely logical (Scene + SpatialIndex); rendering/tile
 * caching lives above this. Owns object CRUD so the scene and spatial index
 * can never drift apart -- every mutation goes through one of these methods
 * rather than callers touching [scene]/[spatialIndex] directly.
 */
class Page(val id: UUID = UUID.randomUUID()) {
    val scene = Scene()
    val spatialIndex: SpatialIndex = UniformGridSpatialIndex()

    fun addObject(obj: PageObject) {
        scene.add(obj)
        spatialIndex.insert(obj.id, obj.pageBounds)
    }

    fun removeObject(id: UUID): PageObject? {
        val removed = scene.removeById(id) ?: return null
        spatialIndex.remove(id, removed.pageBounds)
        return removed
    }

    fun getObject(id: UUID): PageObject? = scene.getById(id)

    /** Applies [newTransform] to the object at [id], keeping the index in sync. */
    fun updateObjectTransform(id: UUID, newTransform: Transform2D): PageObject? {
        val existing = scene.getById(id) ?: return null
        val oldBounds = existing.pageBounds
        val updated = existing.withTransform(newTransform)
        scene.replace(id, updated)
        spatialIndex.update(id, oldBounds, updated.pageBounds)
        return updated
    }

    fun queryObjects(area: Bounds): List<PageObject> =
        spatialIndex.query(area).mapNotNull { scene.getById(it) }

    fun clear() {
        scene.clear()
        (spatialIndex as? UniformGridSpatialIndex)?.clearAll()
    }
}
