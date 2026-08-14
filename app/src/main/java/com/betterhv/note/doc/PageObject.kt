package com.betterhv.note.doc

import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.Stroke
import java.util.UUID

/** Spec §9. IMAGE/TEXT are reserved for later phases; only STROKE is used in V1. */
enum class ObjectType { STROKE, IMAGE, TEXT }

/**
 * Base of every persistent, addressable thing on a [Page]. Spec §9: identity,
 * z-order and transform live here rather than on the raw content (e.g.
 * [com.betterhv.note.ink.Stroke]) so a stroke's own geometry never has to know
 * about the document it's placed in.
 */
sealed class PageObject {
    abstract val id: UUID
    abstract val type: ObjectType
    abstract val transform: Transform2D
    abstract val zIndex: Int
    abstract val createdAt: Long
    abstract val updatedAt: Long

    /** Bounds in the object's own coordinate space, before [transform]. */
    abstract val localBounds: Bounds

    /** Bounds in page space -- what the spatial index and renderer use. */
    val pageBounds: Bounds get() = transform.mapRect(localBounds)

    /** Returns a copy of this object with a new transform (spec §38-39). */
    abstract fun withTransform(transform: Transform2D): PageObject
}

data class StrokeObject(
    override val id: UUID,
    override val transform: Transform2D = Transform2D.IDENTITY,
    override val zIndex: Int = 0,
    override val createdAt: Long = 0L,
    override val updatedAt: Long = 0L,
    val stroke: Stroke
) : PageObject() {
    override val type: ObjectType get() = ObjectType.STROKE
    override val localBounds: Bounds get() = stroke.bounds

    override fun withTransform(transform: Transform2D): StrokeObject =
        copy(transform = transform)
}
