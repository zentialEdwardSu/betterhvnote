package com.betterhv.note.doc

import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.Stroke
import java.util.UUID

enum class ObjectType { STROKE, IMAGE, TEXT }

enum class TextFontFamily(val androidName: String) {
  SANS_SERIF("sans-serif"),
  SERIF("serif"),
  MONOSPACE("monospace"),
}

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
  val stroke: Stroke,
) : PageObject() {
  override val type: ObjectType get() = ObjectType.STROKE
  override val localBounds: Bounds get() = stroke.bounds

  override fun withTransform(transform: Transform2D): StrokeObject = copy(transform = transform)
}

/** A persistent image whose bytes live below files/documents/assets. */
data class ImageObject(
  override val id: UUID,
  override val transform: Transform2D = Transform2D.IDENTITY,
  override val zIndex: Int = 0,
  override val createdAt: Long = 0L,
  override val updatedAt: Long = 0L,
  val assetPath: String,
  val mimeType: String,
  val pixelWidth: Int,
  val pixelHeight: Int,
  val exifOrientation: Int = 1,
) : PageObject() {
  override val type: ObjectType get() = ObjectType.IMAGE
  override val localBounds: Bounds
    get() = if (exifOrientation in 5..8) {
      Bounds(0f, 0f, pixelHeight.toFloat(), pixelWidth.toFloat())
    } else {
      Bounds(0f, 0f, pixelWidth.toFloat(), pixelHeight.toFloat())
    }

  override fun withTransform(transform: Transform2D): ImageObject = copy(transform = transform)
}

/** Framework-free text data. [localBounds] is measured by the Android renderer. */
data class TextObject(
  override val id: UUID,
  override val transform: Transform2D = Transform2D.IDENTITY,
  override val zIndex: Int = 0,
  override val createdAt: Long = 0L,
  override val updatedAt: Long = 0L,
  val text: String,
  val fontFamily: TextFontFamily = TextFontFamily.SANS_SERIF,
  val fontSize: Float = 24f,
  override val localBounds: Bounds,
) : PageObject() {
  override val type: ObjectType get() = ObjectType.TEXT

  override fun withTransform(transform: Transform2D): TextObject = copy(transform = transform)
}
