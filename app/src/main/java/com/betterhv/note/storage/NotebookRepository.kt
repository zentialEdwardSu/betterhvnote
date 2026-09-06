package com.betterhv.note.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import com.betterhv.note.doc.DEFAULT_TEMPLATE_ID
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.Notebook
import com.betterhv.note.doc.NotebookKind
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.PageKind
import com.betterhv.note.doc.PageObject
import com.betterhv.note.doc.PdfAnchor
import com.betterhv.note.doc.PdfAnchorKind
import com.betterhv.note.doc.PdfDocumentRecord
import com.betterhv.note.doc.PdfPageSource
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.TextFontFamily
import com.betterhv.note.doc.TextObject
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.PenType
import com.betterhv.note.ink.PressureCurve
import com.betterhv.note.ink.Stroke
import java.io.File
import java.util.UUID

data class TransferReceipt(
  val sourceDeviceId: String,
  val itemId: UUID,
  val objectId: UUID,
  val committedAt: Long = System.currentTimeMillis(),
)

/** SQLite/WAL document repository (spec §49-56). All writes are transactional. */
class NotebookRepository(context: Context) : AutoCloseable {
  private val documentsDir = File(context.filesDir, "documents").also { it.mkdirs() }
  val notebookFile: File = File(documentsDir, "default.inknote")
  private val store = SQLiteStore(context.applicationContext, notebookFile)

  @Synchronized
  fun openOrCreate(): Notebook {
    val db = store.writableDatabase // Opening replays committed WAL records.
    check(store.quickCheck()) { "Notebook database failed SQLite quick_check" }
    val workingId = ensureWorkingCopy(db)
    val behavior = readStartupBehavior(db)
    val activeId = readMetadata(db, ACTIVE_NOTEBOOK_KEY)?.let(::uuidOrNull)
    val requestedId = resolveStartupNotebookId(behavior, workingId, activeId) {
      notebookExists(db, it)
    }
    val notebook = loadNotebookMetadata(db, requestedId)
      ?: loadNotebookMetadata(db, workingId)
      ?: error("Working Copy could not be loaded")
    initialPageId(db, notebook)?.let { loadPage(it)?.let(notebook::attachPage) }
    writeMetadata(db, ACTIVE_NOTEBOOK_KEY, notebook.id.toString())
    return notebook
  }

  @Synchronized
  fun loadNotebook(id: UUID): Notebook? {
    val db = store.readableDatabase
    val notebook = loadNotebookMetadata(db, id) ?: return null
    initialPageId(db, notebook)?.let { loadPage(it)?.let(notebook::attachPage) }
    return notebook
  }

  @Synchronized
  fun listNotebooks(currentId: UUID): List<NotebookSummary> {
    val db = store.readableDatabase
    val workingId = ensureWorkingCopy(db)
    val summaries = ArrayList<NotebookSummary>()
    db.query(
      "notebooks",
      arrayOf("id", "title", "kind", "updated_at"),
      null,
      null,
      null,
      null,
      "updated_at DESC",
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val id = UUID.fromString(cursor.getString(0))
        val notebook = loadNotebookMetadata(db, id) ?: continue
        summaries += NotebookSummary(
          id = id,
          title = cursor.getString(1),
          kind = enumValueOrDefault(cursor.getString(2), NotebookKind.STANDARD),
          pageCount = notebook.pageOrder.size,
          cover = notebook.pageOrder.firstOrNull()?.let(notebook::metadata),
          updatedAt = cursor.getLong(3),
          isWorkingCopy = id == workingId,
          isCurrent = id == currentId,
        )
      }
    }
    return summaries.sortedWith(
      compareByDescending<NotebookSummary> { it.isCurrent }.thenByDescending { it.updatedAt },
    )
  }

  @Synchronized
  fun workingNotebookId(): UUID = ensureWorkingCopy(store.writableDatabase)

  @Synchronized
  fun startupBehavior(): StartupBehavior = readStartupBehavior(store.readableDatabase)

  @Synchronized
  fun setStartupBehavior(behavior: StartupBehavior) {
    writeMetadata(store.writableDatabase, STARTUP_BEHAVIOR_KEY, behavior.name)
  }

  @Synchronized
  fun setActiveNotebook(id: UUID) {
    require(notebookExists(store.readableDatabase, id)) { "Notebook $id does not exist" }
    writeMetadata(store.writableDatabase, ACTIVE_NOTEBOOK_KEY, id.toString())
  }

  @Synchronized
  fun lastOpenedPageId(notebookId: UUID): UUID? {
    val db = store.readableDatabase
    val notebook = loadNotebookMetadata(db, notebookId) ?: return null
    return initialPageId(db, notebook)
  }

  @Synchronized
  fun setLastOpenedPage(notebookId: UUID, pageId: UUID) {
    val owner = pageOwnerAndKind(store.readableDatabase, pageId)
    require(owner?.first == notebookId) { "Page $pageId does not belong to notebook $notebookId" }
    writeMetadata(store.writableDatabase, lastPageKey(notebookId), pageId.toString())
  }

  @Synchronized
  fun createBlankNotebook(title: String, width: Float, height: Float, templateId: String = DEFAULT_TEMPLATE_ID): UUID {
    val normalizedTitle = requireTitle(title)
    val db = store.writableDatabase
    val notebookId = UUID.randomUUID()
    val pageId = UUID.randomUUID()
    val now = System.currentTimeMillis()
    db.transaction {
      try {
        insertNotebook(this, notebookId, normalizedTitle, now)
        insertBlankPage(this, pageId, notebookId, width, height, now, templateId)
        insertPageOrder(this, notebookId, pageId, 0)
        writeMetadata(this, ACTIVE_NOTEBOOK_KEY, notebookId.toString())
        writeMetadata(this, lastPageKey(notebookId), pageId.toString())
        insertJournal(this, notebookId, "create:blank-notebook")
      } finally {
      }
    }
    return notebookId
  }

  @Synchronized
  fun createPdfNotebook(import: PdfImportCommit): UUID {
    val normalizedTitle = requireTitle(import.title)
    require(import.pages.isNotEmpty()) { "PDF must contain at least one page" }
    require(import.byteLength > 0L) { "PDF asset is empty" }
    require(import.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid PDF SHA-256" }
    val db = store.writableDatabase
    val notebookId = UUID.randomUUID()
    var firstPageId: UUID? = null
    val now = System.currentTimeMillis()
    db.transaction {
      try {
        insertNotebook(this, notebookId, normalizedTitle, now, NotebookKind.PDF)
        import.pages.forEachIndexed { position, imported ->
          val pageId = UUID.randomUUID()
          if (firstPageId == null) firstPageId = pageId
          insertPdfPage(this, pageId, notebookId, imported, now)
          insertPageOrder(this, notebookId, pageId, position)
        }
        ContentValues().apply {
          put("notebook_id", notebookId.toString())
          put("asset_path", import.assetPath)
          put("display_name", import.displayName)
          put("mime_type", import.mimeType)
          put("byte_length", import.byteLength)
          put("sha256", import.sha256.lowercase())
          put("page_count", import.pages.size)
          put("created_at", now)
        }.also { insertOrThrow("pdf_documents", null, it) }
        writeMetadata(this, ACTIVE_NOTEBOOK_KEY, notebookId.toString())
        writeMetadata(this, lastPageKey(notebookId), requireNotNull(firstPageId).toString())
        insertJournal(this, notebookId, "create:pdf-notebook")
      } finally {
      }
    }
    return notebookId
  }

  @Synchronized
  fun pdfDocument(notebookId: UUID): PdfDocumentRecord? = store.readableDatabase.query(
    "pdf_documents",
    arrayOf("asset_path", "display_name", "mime_type", "byte_length", "sha256", "page_count", "created_at"),
    "notebook_id=?",
    arrayOf(notebookId.toString()),
    null,
    null,
    null,
    "1",
  ).use { cursor ->
    if (!cursor.moveToFirst()) return@use null
    PdfDocumentRecord(
      notebookId,
      cursor.getString(0),
      cursor.getString(1),
      cursor.getString(2),
      cursor.getLong(3),
      cursor.getString(4),
      cursor.getInt(5),
      cursor.getLong(6),
    )
  }

  fun resolveDocumentAsset(relativePath: String): File? {
    val root = documentsDir.canonicalFile.toPath()
    val file = File(documentsDir, relativePath).canonicalFile
    return file.takeIf { it.toPath().startsWith(root) && it.isFile }
  }

  @Synchronized
  fun createLinkedNotePage(sourcePageId: UUID, width: Float, height: Float): UUID {
    require(width > 0f && height > 0f) { "Linked note page size must be positive" }
    val db = store.writableDatabase
    val source = pageOwnerAndKind(db, sourcePageId) ?: error("PDF source page does not exist")
    require(source.second == PageKind.PDF_SOURCE) { "Linked notes require a PDF source page" }
    require(notebookKind(db, source.first) == NotebookKind.PDF) { "Source notebook is not a PDF notebook" }
    val order = loadPageIds(db, source.first)
    val sourcePosition = order.indexOf(sourcePageId)
    check(sourcePosition >= 0)
    var insertAt = sourcePosition + 1
    while (insertAt < order.size && parentPdfPageId(db, order[insertAt]) == sourcePageId) insertAt++
    val pageId = UUID.randomUUID()
    val now = System.currentTimeMillis()
    db.transaction {
      try {
        insertLinkedNotePage(this, pageId, source.first, sourcePageId, width, height, now)
        replacePageOrder(this, source.first, order.toMutableList().apply { add(insertAt, pageId) })
        ContentValues().apply { put("updated_at", now) }.also { values ->
          update("notebooks", values, "id=?", arrayOf(source.first.toString()))
        }
        insertJournal(this, source.first, "create:linked-note")
      } finally {
      }
    }
    return pageId
  }

  @Synchronized
  fun deleteLinkedNotePage(pageId: UUID): Boolean {
    val db = store.writableDatabase
    val owner = pageOwnerAndKind(db, pageId) ?: return false
    require(owner.second == PageKind.LINKED_NOTE) { "Only linked-note pages may be deleted here" }
    val order = loadPageIds(db, owner.first).filterNot { it == pageId }
    db.beginTransaction()
    return try {
      val deleted = db.delete("pages", "id=?", arrayOf(pageId.toString())) == 1
      if (deleted) {
        replacePageOrder(db, owner.first, order)
        insertJournal(db, owner.first, "delete:linked-note")
      }
      db.setTransactionSuccessful()
      deleted
    } finally {
      db.endTransaction()
    }
  }

  @Synchronized
  fun savePdfAnchor(anchor: PdfAnchor) {
    val db = store.writableDatabase
    validatePdfAnchor(db, anchor)
    insertPdfAnchor(db, anchor)
  }

  private fun insertPdfAnchor(db: SQLiteDatabase, anchor: PdfAnchor) {
    val b = anchor.normalizedBounds
    ContentValues().apply {
      put("id", anchor.id.toString());
      put("source_page_id", anchor.sourcePageId.toString())
      put("note_page_id", anchor.notePageId.toString());
      put("note_object_id", anchor.noteObjectId.toString())
      put("kind", anchor.kind.name);
      put("normalized_left", b.left);
      put("normalized_top", b.top)
      put("normalized_right", b.right);
      put("normalized_bottom", b.bottom)
      put("selected_text", anchor.selectedText);
      put("ordinal", anchor.ordinal);
      put("created_at", anchor.createdAt)
    }.also { db.insertOrThrow("pdf_anchors", null, it) }
  }

  @Synchronized
  fun pdfAnchors(sourcePageId: UUID): List<PdfAnchor> {
    val result = ArrayList<PdfAnchor>()
    store.readableDatabase.query(
      "pdf_anchors",
      arrayOf(
        "id", "note_page_id", "note_object_id", "kind", "normalized_left", "normalized_top",
        "normalized_right", "normalized_bottom", "selected_text", "ordinal", "created_at",
      ),
      "source_page_id=?",
      arrayOf(sourcePageId.toString()),
      null,
      null,
      "ordinal",
    ).use { c ->
      while (c.moveToNext()) {
        result += PdfAnchor(
          UUID.fromString(c.getString(0)), sourcePageId, UUID.fromString(c.getString(1)),
          UUID.fromString(c.getString(2)), enumValueOrDefault(c.getString(3), PdfAnchorKind.REGION_IMAGE),
          Bounds(c.getFloat(4), c.getFloat(5), c.getFloat(6), c.getFloat(7)),
          if (c.isNull(8)) null else c.getString(8), c.getInt(9), c.getLong(10),
        )
      }
    }
    return result
  }

  /** All link markers rendered on one linked-note page, ordered like their PDF badges. */
  @Synchronized
  fun pdfAnchorsForNotePage(notePageId: UUID): List<PdfAnchor> {
    val result = ArrayList<PdfAnchor>()
    store.readableDatabase.query(
      "pdf_anchors",
      arrayOf(
        "id", "source_page_id", "note_object_id", "kind",
        "normalized_left", "normalized_top", "normalized_right", "normalized_bottom",
        "selected_text", "ordinal", "created_at",
      ),
      "note_page_id=?",
      arrayOf(notePageId.toString()),
      null,
      null,
      "ordinal",
    ).use { c ->
      while (c.moveToNext()) {
        result += PdfAnchor(
          id = UUID.fromString(c.getString(0)),
          sourcePageId = UUID.fromString(c.getString(1)),
          notePageId = notePageId,
          noteObjectId = UUID.fromString(c.getString(2)),
          kind = enumValueOrDefault(c.getString(3), PdfAnchorKind.REGION_IMAGE),
          normalizedBounds = Bounds(c.getFloat(4), c.getFloat(5), c.getFloat(6), c.getFloat(7)),
          selectedText = if (c.isNull(8)) null else c.getString(8),
          ordinal = c.getInt(9),
          createdAt = c.getLong(10),
        )
      }
    }
    return result
  }

  @Synchronized
  fun pdfAnchorForNoteObject(noteObjectId: UUID): PdfAnchor? = store.readableDatabase.query(
    "pdf_anchors",
    arrayOf(
      "id", "source_page_id", "note_page_id", "kind",
      "normalized_left", "normalized_top", "normalized_right", "normalized_bottom",
      "selected_text", "ordinal", "created_at",
    ),
    "note_object_id=?",
    arrayOf(noteObjectId.toString()),
    null,
    null,
    null,
    "1",
  ).use { c ->
    if (!c.moveToFirst()) return@use null
    PdfAnchor(
      id = UUID.fromString(c.getString(0)),
      sourcePageId = UUID.fromString(c.getString(1)),
      notePageId = UUID.fromString(c.getString(2)),
      noteObjectId = noteObjectId,
      kind = enumValueOrDefault(c.getString(3), PdfAnchorKind.REGION_IMAGE),
      normalizedBounds = Bounds(c.getFloat(4), c.getFloat(5), c.getFloat(6), c.getFloat(7)),
      selectedText = if (c.isNull(8)) null else c.getString(8),
      ordinal = c.getInt(9),
      createdAt = c.getLong(10),
    )
  }

  @Synchronized
  fun transferPagesToNewNotebook(
    sourceNotebookId: UUID,
    selectedPageIds: Set<UUID>,
    title: String,
  ): NotebookTransferResult {
    val normalizedTitle = requireTitle(title)
    val db = store.writableDatabase
    require(notebookKind(db, sourceNotebookId) == NotebookKind.STANDARD) {
      "PDF source and linked-note pages cannot be transferred to another notebook"
    }
    val sourceOrder = loadPageIds(db, sourceNotebookId)
    val moved = orderedSelectedPages(sourceOrder, selectedPageIds)
    require(moved.isNotEmpty()) { "At least one source page must be selected" }
    require(moved.size == selectedPageIds.size) { "Selection contains a page outside the source notebook" }
    val retained = sourceOrder.filterNot(selectedPageIds::contains)
    val targetId = UUID.randomUUID()
    val replacementId = if (retained.isEmpty()) UUID.randomUUID() else null
    val now = System.currentTimeMillis()
    val sourceSize = firstPageSize(db, sourceOrder.firstOrNull())

    db.transaction {
      try {
        insertNotebook(this, targetId, normalizedTitle, now)
        delete("page_order", "notebook_id=?", arrayOf(sourceNotebookId.toString()))
        moved.forEach { pageId ->
          ContentValues().apply { put("notebook_id", targetId.toString()) }.also { values ->
            update("pages", values, "id=?", arrayOf(pageId.toString()))
          }
        }
        val finalSourceOrder = if (replacementId != null) {
          insertBlankPage(
            this,
            replacementId,
            sourceNotebookId,
            sourceSize.first,
            sourceSize.second,
            now,
          )
          listOf(replacementId)
        } else {
          retained
        }
        finalSourceOrder.forEachIndexed { index, pageId ->
          insertPageOrder(this, sourceNotebookId, pageId, index)
        }
        moved.forEachIndexed { index, pageId -> insertPageOrder(this, targetId, pageId, index) }
        ContentValues().apply { put("updated_at", now) }.also { values ->
          update("notebooks", values, "id=?", arrayOf(sourceNotebookId.toString()))
        }
        writeMetadata(this, ACTIVE_NOTEBOOK_KEY, targetId.toString())
        insertJournal(this, sourceNotebookId, "transfer:pages-out")
        insertJournal(this, targetId, "transfer:pages-in")
      } finally {
      }
    }
    return NotebookTransferResult(targetId, moved, replacementId)
  }

  @Synchronized
  fun deleteNotebook(notebookId: UUID): NotebookDeletionResult {
    val db = store.writableDatabase
    val workingId = ensureWorkingCopy(db)
    require(notebookId != workingId) { "Working Copy cannot be deleted" }
    require(notebookExists(db, notebookId)) { "Notebook does not exist" }
    val deletedPageIds = loadPageIds(db, notebookId)
    val recordedActive = readMetadata(db, ACTIVE_NOTEBOOK_KEY)?.let(::uuidOrNull)
    val nextActive = if (recordedActive == notebookId ||
      recordedActive == null || !notebookExists(db, recordedActive)
    ) {
      workingId
    } else {
      recordedActive
    }

    db.transaction {
      try {
        val deleted = delete("notebooks", "id=?", arrayOf(notebookId.toString()))
        check(deleted == 1) { "Could not delete notebook" }
        delete("metadata", "key=?", arrayOf(lastPageKey(notebookId)))
        writeMetadata(this, ACTIVE_NOTEBOOK_KEY, nextActive.toString())
        insertJournal(this, workingId, "delete:notebook:$notebookId")
      } finally {
      }
    }
    return NotebookDeletionResult(notebookId, deletedPageIds, nextActive)
  }

  @Synchronized
  fun loadPage(id: UUID): Page? {
    val db = store.readableDatabase
    val page = db.query(
      "pages",
      arrayOf(
        "width", "height", "bookmarked", "content_revision", "created_at", "updated_at",
        "kind", "parent_pdf_page_id", "pdf_page_index",
        "pdf_bound_left", "pdf_bound_top", "pdf_bound_right", "pdf_bound_bottom",
        "pdf_a", "pdf_b", "pdf_c", "pdf_d", "pdf_tx", "pdf_ty", "template_id",
      ),
      "id=?",
      arrayOf(id.toString()),
      null,
      null,
      null,
    ).use { cursor ->
      if (!cursor.moveToFirst()) return null
      val kind = enumValueOrDefault(cursor.getString(6), PageKind.BLANK)
      val pdfSource = if (kind == PageKind.PDF_SOURCE) {
        PdfPageSource(
          sourcePageIndex = cursor.getInt(8),
          bounds = Bounds(cursor.getFloat(9), cursor.getFloat(10), cursor.getFloat(11), cursor.getFloat(12)),
          pdfToPage = Transform2D(
            cursor.getFloat(13),
            cursor.getFloat(14),
            cursor.getFloat(15),
            cursor.getFloat(16),
            cursor.getFloat(17),
            cursor.getFloat(18),
          ),
        )
      } else {
        null
      }
      Page(
        id = id,
        width = cursor.getFloat(0),
        height = cursor.getFloat(1),
        kind = kind,
        parentPdfPageId = if (cursor.isNull(7)) null else UUID.fromString(cursor.getString(7)),
        pdfSource = pdfSource,
        templateId = if (kind == PageKind.PDF_SOURCE) {
          null
        } else {
          (if (cursor.isNull(19)) DEFAULT_TEMPLATE_ID else cursor.getString(19))
        },
        bookmarked = cursor.getInt(2) != 0,
        contentRevision = cursor.getLong(3),
        createdAt = cursor.getLong(4),
        updatedAt = cursor.getLong(5),
      )
    }
    db.rawQuery(
      """SELECT id,type,a,b,c,d,tx,ty,left_bound,top_bound,right_bound,bottom_bound,
                       z_index,created_at,updated_at
                 FROM objects WHERE page_id=? ORDER BY z_index,created_at
      """.trimIndent(),
      arrayOf(id.toString()),
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val objectId = UUID.fromString(cursor.getString(0))
        val transform = Transform2D(
          cursor.getFloat(2),
          cursor.getFloat(3),
          cursor.getFloat(4),
          cursor.getFloat(5),
          cursor.getFloat(6),
          cursor.getFloat(7),
        )
        val localBounds = Bounds(
          cursor.getFloat(8),
          cursor.getFloat(9),
          cursor.getFloat(10),
          cursor.getFloat(11),
        )
        val zIndex = cursor.getInt(12)
        val createdAt = cursor.getLong(13)
        val updatedAt = cursor.getLong(14)
        val obj = when (cursor.getString(1)) {
          "STROKE" -> loadStroke(db, objectId, transform, zIndex, createdAt, updatedAt)

          "IMAGE" -> loadImage(db, objectId, transform, zIndex, createdAt, updatedAt)

          "TEXT" -> loadText(
            db,
            objectId,
            transform,
            zIndex,
            createdAt,
            updatedAt,
            localBounds,
          )

          else -> null
        }
        obj?.let(page::restoreObject)
      }
    }
    return page
  }

  @Synchronized
  fun persist(change: DocumentChange) {
    persistInternal(change, null, null)
  }

  @Synchronized
  fun persistWithReceipt(change: DocumentChange, receipt: TransferReceipt?) {
    persistInternal(change, receipt, null)
  }

  @Synchronized
  fun persistWithPdfAnchor(change: DocumentChange, anchor: PdfAnchor) {
    persistInternal(change, null, anchor)
  }

  private fun persistInternal(change: DocumentChange, receipt: TransferReceipt?, anchor: PdfAnchor?) {
    val db = store.writableDatabase
    db.transaction {
      try {
        upsertNotebook(this, change)
        syncPagesAndOrder(this, change)
        change.fullPages.forEach { snapshot ->
          syncPageObjects(this, snapshot)
        }
        change.objectChanges.forEach { mutation ->
          val value = mutation.value
          if (value == null) {
            delete("objects", "id=? AND page_id=?", arrayOf(mutation.objectId.toString(), mutation.pageId.toString()))
          } else {
            upsertObject(this, mutation.pageId, value)
          }
        }
        anchor?.let {
          validatePdfAnchor(this, it)
          insertPdfAnchor(this, it)
        }
        ContentValues().apply {
          put("notebook_id", change.notebookId.toString())
          put("committed_at", System.currentTimeMillis())
          put("description", change.description)
        }.also { insertOrThrow("operation_journal", null, it) }
        receipt?.let {
          ContentValues().apply {
            put("source_device_id", it.sourceDeviceId);
            put("item_id", it.itemId.toString())
            put("object_id", it.objectId.toString());
            put("committed_at", it.committedAt)
          }.also { values ->
            insertWithOnConflict(
              "transfer_receipts",
              null,
              values,
              SQLiteDatabase.CONFLICT_IGNORE,
            )
          }
        }
      } finally {
      }
    }
  }

  /**
   * Authoritatively syncs one page without deleting objects that still exist.
   * Deleting and reinserting every row would trigger pdf_anchors' ON DELETE
   * CASCADE and silently destroy links whose screenshot object was retained.
   */
  private fun syncPageObjects(db: SQLiteDatabase, snapshot: PageSnapshot) {
    val retainedIds = snapshot.objects.asSequence().map { it.id }.toHashSet()
    val removedIds = ArrayList<String>()
    db.query(
      "objects",
      arrayOf("id"),
      "page_id=?",
      arrayOf(snapshot.metadata.id.toString()),
      null,
      null,
      null,
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val id = cursor.getString(0)
        if (runCatching { UUID.fromString(id) }.getOrNull() !in retainedIds) removedIds += id
      }
    }
    removedIds.forEach { id ->
      db.delete(
        "objects",
        "id=? AND page_id=?",
        arrayOf(id, snapshot.metadata.id.toString()),
      )
    }
    snapshot.objects.forEach { upsertObject(db, snapshot.metadata.id, it) }
  }

  @Synchronized
  fun findTransferReceipt(sourceDeviceId: String, itemId: UUID): UUID? = store.readableDatabase.query(
    "transfer_receipts",
    arrayOf("object_id"),
    "source_device_id=? AND item_id=?",
    arrayOf(sourceDeviceId, itemId.toString()),
    null,
    null,
    null,
  ).use { cursor -> if (cursor.moveToFirst()) UUID.fromString(cursor.getString(0)) else null }

  @Synchronized fun recover(): RecoveryManager.Result = RecoveryManager(store).recover()

  @Synchronized fun checkpoint() = store.checkpoint()

  @Synchronized
  fun referencedImageAssets(): Set<String> {
    val result = LinkedHashSet<String>()
    store.readableDatabase.query(
      "images",
      arrayOf("asset_path"),
      null,
      null,
      null,
      null,
      null,
    ).use { cursor -> while (cursor.moveToNext()) result += cursor.getString(0) }
    return result
  }

  @Synchronized
  fun referencedPdfAssets(): Set<String> {
    val result = LinkedHashSet<String>()
    store.readableDatabase.query(
      "pdf_documents",
      arrayOf("asset_path"),
      null,
      null,
      null,
      null,
      null,
    ).use { cursor -> while (cursor.moveToNext()) result += cursor.getString(0) }
    return result
  }

  @Synchronized override fun close() = store.close()

  private fun loadNotebookMetadata(db: SQLiteDatabase, notebookId: UUID): Notebook? {
    val notebook = db.query(
      "notebooks",
      arrayOf("id", "title", "kind", "created_at", "updated_at"),
      "id=?",
      arrayOf(notebookId.toString()),
      null,
      null,
      null,
      "1",
    ).use { cursor ->
      if (!cursor.moveToFirst()) return null
      Notebook(
        id = UUID.fromString(cursor.getString(0)),
        title = cursor.getString(1),
        kind = enumValueOrDefault(cursor.getString(2), NotebookKind.STANDARD),
        createdAt = cursor.getLong(3),
        updatedAt = cursor.getLong(4),
      )
    }
    db.rawQuery(
      """SELECT p.id,p.width,p.height,p.bookmarked,p.content_revision,p.created_at,p.updated_at,
                       p.kind,p.parent_pdf_page_id,p.pdf_page_index,
                       p.pdf_bound_left,p.pdf_bound_top,p.pdf_bound_right,p.pdf_bound_bottom,
                       p.pdf_a,p.pdf_b,p.pdf_c,p.pdf_d,p.pdf_tx,p.pdf_ty,p.template_id
                 FROM page_order po JOIN pages p ON p.id=po.page_id
                WHERE po.notebook_id=? ORDER BY po.position
      """.trimIndent(),
      arrayOf(notebook.id.toString()),
    ).use { cursor ->
      var index = 0
      while (cursor.moveToNext()) {
        val kind = enumValueOrDefault(cursor.getString(7), PageKind.BLANK)
        val source = if (kind == PageKind.PDF_SOURCE) {
          PdfPageSource(
            cursor.getInt(9),
            Bounds(cursor.getFloat(10), cursor.getFloat(11), cursor.getFloat(12), cursor.getFloat(13)),
            Transform2D(
              cursor.getFloat(14),
              cursor.getFloat(15),
              cursor.getFloat(16),
              cursor.getFloat(17),
              cursor.getFloat(18),
              cursor.getFloat(19),
            ),
          )
        } else {
          null
        }
        notebook.addPageMetadata(
          Notebook.PageMetadata(
            UUID.fromString(cursor.getString(0)), cursor.getFloat(1), cursor.getFloat(2),
            cursor.getInt(3) != 0, cursor.getLong(4), cursor.getLong(5), cursor.getLong(6),
            kind, if (cursor.isNull(8)) null else UUID.fromString(cursor.getString(8)), source,
            if (kind == PageKind.PDF_SOURCE) {
              null
            } else {
              (if (cursor.isNull(20)) DEFAULT_TEMPLATE_ID else cursor.getString(20))
            },
          ),
          index++,
        )
      }
    }
    return notebook
  }

  private fun ensureWorkingCopy(db: SQLiteDatabase): UUID {
    val recorded = readMetadata(db, WORKING_NOTEBOOK_KEY)?.let(::uuidOrNull)
    if (recorded != null && notebookExists(db, recorded)) {
      ensureWorkingCopyTitle(db, recorded)
      ensureStartupMetadata(db, recorded)
      return recorded
    }
    val existing = db.query(
      "notebooks",
      arrayOf("id"),
      null,
      null,
      null,
      null,
      "updated_at DESC",
      "1",
    ).use { cursor ->
      if (cursor.moveToFirst()) UUID.fromString(cursor.getString(0)) else null
    }
    val workingId = existing ?: run {
      val notebookId = UUID.randomUUID()
      val pageId = UUID.randomUUID()
      val now = System.currentTimeMillis()
      db.beginTransaction()
      try {
        insertNotebook(db, notebookId, WORKING_COPY_TITLE, now)
        insertBlankPage(db, pageId, notebookId, 0f, 0f, now)
        insertPageOrder(db, notebookId, pageId, 0)
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }
      notebookId
    }
    ensureWorkingCopyTitle(db, workingId)
    writeMetadata(db, WORKING_NOTEBOOK_KEY, workingId.toString())
    ensureStartupMetadata(db, workingId)
    return workingId
  }

  private fun ensureWorkingCopyTitle(db: SQLiteDatabase, id: UUID) {
    ContentValues().apply { put("title", WORKING_COPY_TITLE) }.also { values ->
      db.update("notebooks", values, "id=?", arrayOf(id.toString()))
    }
  }

  private fun ensureStartupMetadata(db: SQLiteDatabase, workingId: UUID) {
    if (readMetadata(db, STARTUP_BEHAVIOR_KEY) == null) {
      writeMetadata(db, STARTUP_BEHAVIOR_KEY, StartupBehavior.WORKING_COPY.name)
    }
    val active = readMetadata(db, ACTIVE_NOTEBOOK_KEY)?.let(::uuidOrNull)
    if (active == null || !notebookExists(db, active)) {
      writeMetadata(db, ACTIVE_NOTEBOOK_KEY, workingId.toString())
    }
  }

  private fun readStartupBehavior(db: SQLiteDatabase): StartupBehavior =
    readMetadata(db, STARTUP_BEHAVIOR_KEY)?.let { value ->
      StartupBehavior.entries.firstOrNull { it.name == value }
    } ?: StartupBehavior.WORKING_COPY

  private fun readMetadata(db: SQLiteDatabase, key: String): String? = db.query(
    "metadata",
    arrayOf("value"),
    "key=?",
    arrayOf(key),
    null,
    null,
    null,
  ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

  private fun writeMetadata(db: SQLiteDatabase, key: String, value: String) {
    ContentValues().apply {
      put("key", key)
      put("value", value)
    }.also { db.insertWithOnConflict("metadata", null, it, SQLiteDatabase.CONFLICT_REPLACE) }
  }

  private fun initialPageId(db: SQLiteDatabase, notebook: Notebook): UUID? {
    val recorded = readMetadata(db, lastPageKey(notebook.id))?.let(::uuidOrNull)
    if (recorded != null && recorded in notebook.pageOrder &&
      pageOwnerAndKind(db, recorded)?.first == notebook.id
    ) {
      return recorded
    }
    return notebook.pageOrder.firstOrNull()
  }

  private fun lastPageKey(notebookId: UUID) = "$LAST_PAGE_PREFIX$notebookId"

  private fun notebookExists(db: SQLiteDatabase, id: UUID): Boolean = db.query(
    "notebooks",
    arrayOf("id"),
    "id=?",
    arrayOf(id.toString()),
    null,
    null,
    null,
    "1",
  ).use { it.moveToFirst() }

  private fun loadPageIds(db: SQLiteDatabase, notebookId: UUID): List<UUID> {
    val ids = ArrayList<UUID>()
    db.query(
      "page_order",
      arrayOf("page_id"),
      "notebook_id=?",
      arrayOf(notebookId.toString()),
      null,
      null,
      "position",
    ).use { cursor -> while (cursor.moveToNext()) ids += UUID.fromString(cursor.getString(0)) }
    return ids
  }

  private fun firstPageSize(db: SQLiteDatabase, pageId: UUID?): Pair<Float, Float> {
    if (pageId == null) return 0f to 0f
    return db.query(
      "pages",
      arrayOf("width", "height"),
      "id=?",
      arrayOf(pageId.toString()),
      null,
      null,
      null,
      "1",
    ).use { cursor ->
      if (cursor.moveToFirst()) cursor.getFloat(0) to cursor.getFloat(1) else 0f to 0f
    }
  }

  private fun insertNotebook(
    db: SQLiteDatabase,
    id: UUID,
    title: String,
    now: Long,
    kind: NotebookKind = NotebookKind.STANDARD,
  ) {
    ContentValues().apply {
      put("id", id.toString())
      put("title", title)
      put("kind", kind.name)
      put("created_at", now)
      put("updated_at", now)
    }.also { db.insertOrThrow("notebooks", null, it) }
  }

  private fun insertBlankPage(
    db: SQLiteDatabase,
    id: UUID,
    notebookId: UUID,
    width: Float,
    height: Float,
    now: Long,
    templateId: String = DEFAULT_TEMPLATE_ID,
  ) {
    ContentValues().apply {
      put("id", id.toString())
      put("notebook_id", notebookId.toString())
      put("width", width.coerceAtLeast(0f))
      put("height", height.coerceAtLeast(0f))
      put("bookmarked", 0)
      put("content_revision", 0L)
      put("kind", PageKind.BLANK.name)
      put("template_id", templateId)
      put("created_at", now)
      put("updated_at", now)
    }.also { db.insertOrThrow("pages", null, it) }
  }

  private fun insertPdfPage(db: SQLiteDatabase, id: UUID, notebookId: UUID, imported: PdfImportedPage, now: Long) {
    val source = imported.source
    ContentValues().apply {
      put("id", id.toString());
      put("notebook_id", notebookId.toString())
      put("width", imported.width);
      put("height", imported.height)
      put("bookmarked", 0);
      put("content_revision", 0L);
      put("kind", PageKind.PDF_SOURCE.name)
      put("pdf_page_index", source.sourcePageIndex)
      put("pdf_bound_left", source.bounds.left);
      put("pdf_bound_top", source.bounds.top)
      put("pdf_bound_right", source.bounds.right);
      put("pdf_bound_bottom", source.bounds.bottom)
      put("pdf_a", source.pdfToPage.a);
      put("pdf_b", source.pdfToPage.b)
      put("pdf_c", source.pdfToPage.c);
      put("pdf_d", source.pdfToPage.d)
      put("pdf_tx", source.pdfToPage.tx);
      put("pdf_ty", source.pdfToPage.ty)
      putNull("template_id")
      put("created_at", now);
      put("updated_at", now)
    }.also { db.insertOrThrow("pages", null, it) }
  }

  private fun insertLinkedNotePage(
    db: SQLiteDatabase,
    id: UUID,
    notebookId: UUID,
    sourcePageId: UUID,
    width: Float,
    height: Float,
    now: Long,
  ) {
    ContentValues().apply {
      put("id", id.toString());
      put("notebook_id", notebookId.toString())
      put("width", width);
      put("height", height);
      put("bookmarked", 0);
      put("content_revision", 0L)
      put("kind", PageKind.LINKED_NOTE.name);
      put("parent_pdf_page_id", sourcePageId.toString())
      put("template_id", DEFAULT_TEMPLATE_ID)
      put("created_at", now);
      put("updated_at", now)
    }.also { db.insertOrThrow("pages", null, it) }
  }

  private fun insertPageOrder(db: SQLiteDatabase, notebookId: UUID, pageId: UUID, position: Int) {
    ContentValues().apply {
      put("notebook_id", notebookId.toString())
      put("page_id", pageId.toString())
      put("position", position)
    }.also { db.insertOrThrow("page_order", null, it) }
  }

  private fun replacePageOrder(db: SQLiteDatabase, notebookId: UUID, order: List<UUID>) {
    db.delete("page_order", "notebook_id=?", arrayOf(notebookId.toString()))
    order.forEachIndexed { index, pageId -> insertPageOrder(db, notebookId, pageId, index) }
  }

  private fun notebookKind(db: SQLiteDatabase, notebookId: UUID): NotebookKind? = db.query(
    "notebooks",
    arrayOf("kind"),
    "id=?",
    arrayOf(notebookId.toString()),
    null,
    null,
    null,
    "1",
  ).use { c -> if (c.moveToFirst()) enumValueOrDefault(c.getString(0), NotebookKind.STANDARD) else null }

  private fun pageOwnerAndKind(db: SQLiteDatabase, pageId: UUID): Pair<UUID, PageKind>? = db.query(
    "pages",
    arrayOf("notebook_id", "kind"),
    "id=?",
    arrayOf(pageId.toString()),
    null,
    null,
    null,
    "1",
  ).use { c ->
    if (!c.moveToFirst()) {
      null
    } else {
      UUID.fromString(c.getString(0)) to
        enumValueOrDefault(c.getString(1), PageKind.BLANK)
    }
  }

  private fun parentPdfPageId(db: SQLiteDatabase, pageId: UUID): UUID? = db.query(
    "pages",
    arrayOf("parent_pdf_page_id"),
    "id=?",
    arrayOf(pageId.toString()),
    null,
    null,
    null,
    "1",
  ).use { c ->
    if (!c.moveToFirst() || c.isNull(0)) null else UUID.fromString(c.getString(0))
  }

  private fun objectBelongsToPage(db: SQLiteDatabase, objectId: UUID, pageId: UUID): Boolean = db.query(
    "objects",
    arrayOf("id"),
    "id=? AND page_id=?",
    arrayOf(objectId.toString(), pageId.toString()),
    null,
    null,
    null,
    "1",
  ).use { it.moveToFirst() }

  private fun validatePdfAnchor(db: SQLiteDatabase, anchor: PdfAnchor) {
    val b = anchor.normalizedBounds
    require(
      b.left in 0f..1f && b.top in 0f..1f && b.right in 0f..1f && b.bottom in 0f..1f &&
        b.right > b.left && b.bottom > b.top
    ) { "Anchor bounds must be normalized and non-empty" }
    val source = pageOwnerAndKind(db, anchor.sourcePageId) ?: error("Source page does not exist")
    val note = pageOwnerAndKind(db, anchor.notePageId) ?: error("Note page does not exist")
    require(source.first == note.first && source.second == PageKind.PDF_SOURCE) {
      "Anchor pages must share one PDF notebook"
    }
    require(note.second == PageKind.LINKED_NOTE && parentPdfPageId(db, anchor.notePageId) == anchor.sourcePageId) {
      "Anchor note page is not linked to its source page"
    }
    require(objectBelongsToPage(db, anchor.noteObjectId, anchor.notePageId)) {
      "Anchor object is not on the note page"
    }
  }

  private fun insertJournal(db: SQLiteDatabase, notebookId: UUID, description: String) {
    ContentValues().apply {
      put("notebook_id", notebookId.toString())
      put("committed_at", System.currentTimeMillis())
      put("description", description)
    }.also { db.insertOrThrow("operation_journal", null, it) }
  }

  private fun requireTitle(title: String): String = title.trim().also {
    require(it.isNotEmpty()) { "Notebook title must not be blank" }
  }

  private fun uuidOrNull(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

  private fun upsertNotebook(db: SQLiteDatabase, change: DocumentChange) {
    val values = ContentValues().apply {
      put("id", change.notebookId.toString())
      put("title", change.title)
      put("kind", change.notebookKind.name)
      put("created_at", change.createdAt)
      put("updated_at", change.updatedAt)
    }
    db.insertWithOnConflict("notebooks", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    db.update("notebooks", values, "id=?", arrayOf(change.notebookId.toString()))
  }

  private fun syncPagesAndOrder(db: SQLiteDatabase, change: DocumentChange) {
    val notebookId = change.notebookId.toString()
    change.pages.forEach { page ->
      val values = ContentValues().apply {
        put("id", page.id.toString())
        put("notebook_id", notebookId)
        put("width", page.width)
        put("height", page.height)
        put("bookmarked", if (page.bookmarked) 1 else 0)
        put("content_revision", page.contentRevision)
        put("kind", page.kind.name)
        if (page.templateId == null) putNull("template_id") else put("template_id", page.templateId)
        if (page.parentPdfPageId == null) {
          putNull("parent_pdf_page_id")
        } else {
          put("parent_pdf_page_id", page.parentPdfPageId.toString())
        }
        val source = page.pdfSource
        if (source == null) {
          putNull("pdf_page_index");
          putNull("pdf_bound_left");
          putNull("pdf_bound_top")
          putNull("pdf_bound_right");
          putNull("pdf_bound_bottom")
          putNull("pdf_a");
          putNull("pdf_b");
          putNull("pdf_c");
          putNull("pdf_d")
          putNull("pdf_tx");
          putNull("pdf_ty")
        } else {
          put("pdf_page_index", source.sourcePageIndex)
          put("pdf_bound_left", source.bounds.left);
          put("pdf_bound_top", source.bounds.top)
          put("pdf_bound_right", source.bounds.right);
          put("pdf_bound_bottom", source.bounds.bottom)
          put("pdf_a", source.pdfToPage.a);
          put("pdf_b", source.pdfToPage.b)
          put("pdf_c", source.pdfToPage.c);
          put("pdf_d", source.pdfToPage.d)
          put("pdf_tx", source.pdfToPage.tx);
          put("pdf_ty", source.pdfToPage.ty)
        }
        put("created_at", page.createdAt)
        put("updated_at", page.updatedAt)
      }
      db.insertWithOnConflict("pages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
      db.update("pages", values, "id=?", arrayOf(page.id.toString()))
    }
    val retained = change.pageOrder.map { it.toString() }.toSet()
    val removedPageIds = ArrayList<String>()
    db.query("pages", arrayOf("id"), "notebook_id=?", arrayOf(notebookId), null, null, null).use { cursor ->
      while (cursor.moveToNext()) {
        val id = cursor.getString(0)
        if (id !in retained) removedPageIds += id
      }
    }
    removedPageIds.forEach { id -> db.delete("pages", "id=?", arrayOf(id)) }
    db.delete("page_order", "notebook_id=?", arrayOf(notebookId))
    change.pageOrder.forEachIndexed { position, pageId ->
      ContentValues().apply {
        put("notebook_id", notebookId)
        put("page_id", pageId.toString())
        put("position", position)
      }.also { db.insertOrThrow("page_order", null, it) }
    }
  }

  private fun upsertObject(db: SQLiteDatabase, pageId: UUID, obj: PageObject) {
    val bounds = obj.localBounds
    val t = obj.transform
    val objectValues = ContentValues().apply {
      put("id", obj.id.toString());
      put("page_id", pageId.toString());
      put("type", obj.type.name)
      put("a", t.a);
      put("b", t.b);
      put("c", t.c);
      put("d", t.d);
      put("tx", t.tx);
      put("ty", t.ty)
      put("left_bound", bounds.left);
      put("top_bound", bounds.top)
      put("right_bound", bounds.right);
      put("bottom_bound", bounds.bottom)
      put("z_index", obj.zIndex);
      put("created_at", obj.createdAt);
      put("updated_at", obj.updatedAt)
    }
    updateOrInsert(db, "objects", objectValues, "id", obj.id.toString())
    when (obj) {
      is StrokeObject -> {
        val style = obj.stroke.style
        val values = ContentValues().apply {
          put("object_id", obj.id.toString());
          put("base_width", style.baseWidth);
          put("color", style.color)
          put("pressure_a", style.pressureCurve.a);
          put("pressure_gamma", style.pressureCurve.gamma)
          put("pen_type", style.penType.name);
          put("points_blob", PointBlobCodec.encode(obj.stroke.points))
        }
        updateOrInsert(db, "strokes", values, "object_id", obj.id.toString())
      }

      is ImageObject -> updateOrInsert(
        db, "images",
        ContentValues().apply {
          put("object_id", obj.id.toString());
          put("asset_path", obj.assetPath)
          put("mime_type", obj.mimeType);
          put("pixel_width", obj.pixelWidth)
          put("pixel_height", obj.pixelHeight);
          put("exif_orientation", obj.exifOrientation)
        },
        "object_id", obj.id.toString()
      )

      is TextObject -> updateOrInsert(
        db, "texts",
        ContentValues().apply {
          put("object_id", obj.id.toString());
          put("content", obj.text)
          put("font_family", obj.fontFamily.name);
          put("font_size", obj.fontSize)
        },
        "object_id", obj.id.toString()
      )
    }
  }

  /** SQLite REPLACE performs DELETE + INSERT and therefore must not be used for FK anchor parents. */
  private fun updateOrInsert(
    db: SQLiteDatabase,
    table: String,
    values: ContentValues,
    keyColumn: String,
    keyValue: String,
  ) {
    if (db.update(table, values, "$keyColumn=?", arrayOf(keyValue)) == 0) {
      db.insertOrThrow(table, null, values)
    }
  }

  private fun loadStroke(
    db: SQLiteDatabase,
    id: UUID,
    transform: Transform2D,
    z: Int,
    created: Long,
    updated: Long,
  ): StrokeObject? = db.query(
    "strokes",
    arrayOf("base_width", "color", "pressure_a", "pressure_gamma", "pen_type", "points_blob"),
    "object_id=?",
    arrayOf(id.toString()),
    null,
    null,
    null,
  ).use { c ->
    if (!c.moveToFirst()) return@use null
    val style = PenStyle(
      c.getFloat(0),
      c.getInt(1),
      PressureCurve(c.getFloat(2), c.getFloat(3)),
      PenType.valueOf(c.getString(4)),
    )
    StrokeObject(id, transform, z, created, updated, Stroke(PointBlobCodec.decode(c.getBlob(5)), style))
  }

  private fun loadImage(
    db: SQLiteDatabase,
    id: UUID,
    transform: Transform2D,
    z: Int,
    created: Long,
    updated: Long,
  ): ImageObject? = db.query(
    "images",
    arrayOf("asset_path", "mime_type", "pixel_width", "pixel_height", "exif_orientation"),
    "object_id=?",
    arrayOf(id.toString()),
    null,
    null,
    null,
  ).use { c ->
    if (!c.moveToFirst()) return@use null
    ImageObject(
      id, transform, z, created, updated,
      c.getString(
        0,
      ),
      c.getString(1), c.getInt(2), c.getInt(3), c.getInt(4)
    )
  }

  private fun loadText(
    db: SQLiteDatabase,
    id: UUID,
    transform: Transform2D,
    z: Int,
    created: Long,
    updated: Long,
    bounds: Bounds,
  ): TextObject? = db.query(
    "texts",
    arrayOf("content", "font_family", "font_size"),
    "object_id=?",
    arrayOf(id.toString()),
    null,
    null,
    null,
  ).use { c ->
    if (!c.moveToFirst()) return@use null
    TextObject(
      id, transform, z, created, updated,
      c.getString(
        0,
      ),
      TextFontFamily.valueOf(c.getString(1)), c.getFloat(2), bounds
    )
  }

  companion object {
    const val WORKING_COPY_TITLE = "Working Copy"
    private const val WORKING_NOTEBOOK_KEY = "working_notebook_id"
    private const val ACTIVE_NOTEBOOK_KEY = "active_notebook_id"
    private const val STARTUP_BEHAVIOR_KEY = "startup_behavior"
    private const val LAST_PAGE_PREFIX = "last_page:"
  }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
  enumValues<T>().firstOrNull { it.name == value } ?: fallback
