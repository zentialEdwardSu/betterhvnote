package com.betterhv.note.doc

import com.betterhv.note.ink.Bounds
import java.util.UUID

/**
 * Spec §8: a page is purely logical (Scene + SpatialIndex); rendering/tile
 * caching lives above this. Owns object CRUD so the scene and spatial index
 * can never drift apart -- every mutation goes through one of these methods
 * rather than callers touching [scene]/[spatialIndex] directly.
 */
class Page(
  val id: UUID = UUID.randomUUID(),
  width: Float = 0f,
  height: Float = 0f,
  val kind: PageKind = PageKind.BLANK,
  val parentPdfPageId: UUID? = null,
  val pdfSource: PdfPageSource? = null,
  templateId: String? = if (kind == PageKind.PDF_SOURCE) null else DEFAULT_TEMPLATE_ID,
  bookmarked: Boolean = false,
  contentRevision: Long = 0L,
  val createdAt: Long = System.currentTimeMillis(),
  updatedAt: Long = createdAt,
) {
  init {
    require((kind == PageKind.PDF_SOURCE) == (pdfSource != null)) {
      "Only PDF source pages may carry PDF geometry"
    }
    require((kind == PageKind.LINKED_NOTE) == (parentPdfPageId != null)) {
      "Linked note pages must reference their PDF source page"
    }
    require((kind == PageKind.PDF_SOURCE) == (templateId == null)) {
      "PDF source pages cannot carry a template; writable pages require one"
    }
  }

  var width: Float = width
    private set
  var height: Float = height
    private set
  var updatedAt: Long = updatedAt
    private set
  var bookmarked: Boolean = bookmarked
    private set
  var contentRevision: Long = contentRevision
    private set
  var templateId: String? = templateId
    private set
  val scene = Scene()
  val spatialIndex: SpatialIndex = UniformGridSpatialIndex()

  fun addObject(obj: PageObject) {
    scene.add(obj)
    spatialIndex.insert(obj.id, obj.pageBounds)
    touch()
  }

  fun removeObject(id: UUID): PageObject? {
    val removed = scene.removeById(id) ?: return null
    spatialIndex.remove(id, removed.pageBounds)
    touch()
    return removed
  }

  fun getObject(id: UUID): PageObject? = scene.getById(id)

  /** Replaces an object's content/style while keeping Scene and index synchronized. */
  fun updateObject(id: UUID, updated: PageObject): PageObject? {
    val existing = scene.getById(id) ?: return null
    require(updated.id == id) { "Updated object id must not change" }
    scene.replace(id, updated)
    spatialIndex.update(id, existing.pageBounds, updated.pageBounds)
    touch()
    return updated
  }

  /** Applies [newTransform] to the object at [id], keeping the index in sync. */
  fun updateObjectTransform(id: UUID, newTransform: Transform2D): PageObject? {
    val existing = scene.getById(id) ?: return null
    val oldBounds = existing.pageBounds
    val updated = existing.withTransform(newTransform)
    scene.replace(id, updated)
    spatialIndex.update(id, oldBounds, updated.pageBounds)
    touch()
    return updated
  }

  fun queryObjects(area: Bounds): List<PageObject> = spatialIndex.query(area).mapNotNull { scene.getById(it) }

  fun clear() {
    scene.clear()
    (spatialIndex as? UniformGridSpatialIndex)?.clearAll()
    touch()
  }

  fun updateSize(width: Float, height: Float) {
    if (width <= 0f || height <= 0f || (this.width == width && this.height == height)) return
    this.width = width
    this.height = height
    touch()
  }

  fun setBookmarked(bookmarked: Boolean) {
    if (this.bookmarked == bookmarked) return
    this.bookmarked = bookmarked
    touch(contentChanged = false)
  }

  fun setTemplate(templateId: String) {
    require(kind != PageKind.PDF_SOURCE) { "PDF source pages use the imported document background" }
    require(templateId.isNotBlank()) { "Template id must not be blank" }
    if (this.templateId == templateId) return
    this.templateId = templateId
    touch()
  }

  /** Storage-only restore path: rebuilds Scene + index without changing persisted timestamps. */
  fun restoreObject(obj: PageObject) {
    scene.add(obj)
    spatialIndex.insert(obj.id, obj.pageBounds)
  }

  private fun touch(contentChanged: Boolean = true) {
    updatedAt = System.currentTimeMillis()
    if (contentChanged) contentRevision++
  }
}

const val DEFAULT_TEMPLATE_ID = "builtin.blank"
