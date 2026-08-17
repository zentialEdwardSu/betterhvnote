package com.betterhv.note.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.betterhv.note.doc.Notebook
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.PageObject
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
    val committedAt: Long = System.currentTimeMillis()
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
        notebook.pageOrder.firstOrNull()?.let { loadPage(it)?.let(notebook::attachPage) }
        writeMetadata(db, ACTIVE_NOTEBOOK_KEY, notebook.id.toString())
        return notebook
    }

    @Synchronized
    fun loadNotebook(id: UUID): Notebook? {
        val notebook = loadNotebookMetadata(store.readableDatabase, id) ?: return null
        notebook.pageOrder.firstOrNull()?.let { loadPage(it)?.let(notebook::attachPage) }
        return notebook
    }

    @Synchronized
    fun listNotebooks(currentId: UUID): List<NotebookSummary> {
        val db = store.readableDatabase
        val workingId = ensureWorkingCopy(db)
        val summaries = ArrayList<NotebookSummary>()
        db.query(
            "notebooks", arrayOf("id", "title", "updated_at"),
            null, null, null, null, "updated_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = UUID.fromString(cursor.getString(0))
                val notebook = loadNotebookMetadata(db, id) ?: continue
                summaries += NotebookSummary(
                    id = id,
                    title = cursor.getString(1),
                    pageCount = notebook.pageOrder.size,
                    cover = notebook.pageOrder.firstOrNull()?.let(notebook::metadata),
                    updatedAt = cursor.getLong(2),
                    isWorkingCopy = id == workingId,
                    isCurrent = id == currentId
                )
            }
        }
        return summaries.sortedWith(
            compareByDescending<NotebookSummary> { it.isCurrent }.thenByDescending { it.updatedAt }
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
    fun createBlankNotebook(title: String, width: Float, height: Float): UUID {
        val normalizedTitle = requireTitle(title)
        val db = store.writableDatabase
        val notebookId = UUID.randomUUID()
        val pageId = UUID.randomUUID()
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            insertNotebook(db, notebookId, normalizedTitle, now)
            insertBlankPage(db, pageId, notebookId, width, height, now)
            insertPageOrder(db, notebookId, pageId, 0)
            writeMetadata(db, ACTIVE_NOTEBOOK_KEY, notebookId.toString())
            insertJournal(db, notebookId, "create:blank-notebook")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return notebookId
    }

    @Synchronized
    fun transferPagesToNewNotebook(
        sourceNotebookId: UUID,
        selectedPageIds: Set<UUID>,
        title: String
    ): NotebookTransferResult {
        val normalizedTitle = requireTitle(title)
        val db = store.writableDatabase
        val sourceOrder = loadPageIds(db, sourceNotebookId)
        val moved = orderedSelectedPages(sourceOrder, selectedPageIds)
        require(moved.isNotEmpty()) { "At least one source page must be selected" }
        require(moved.size == selectedPageIds.size) { "Selection contains a page outside the source notebook" }
        val retained = sourceOrder.filterNot(selectedPageIds::contains)
        val targetId = UUID.randomUUID()
        val replacementId = if (retained.isEmpty()) UUID.randomUUID() else null
        val now = System.currentTimeMillis()
        val sourceSize = firstPageSize(db, sourceOrder.firstOrNull())

        db.beginTransaction()
        try {
            insertNotebook(db, targetId, normalizedTitle, now)
            db.delete("page_order", "notebook_id=?", arrayOf(sourceNotebookId.toString()))
            moved.forEach { pageId ->
                ContentValues().apply { put("notebook_id", targetId.toString()) }.also { values ->
                    db.update("pages", values, "id=?", arrayOf(pageId.toString()))
                }
            }
            val finalSourceOrder = if (replacementId != null) {
                insertBlankPage(
                    db, replacementId, sourceNotebookId,
                    sourceSize.first, sourceSize.second, now
                )
                listOf(replacementId)
            } else {
                retained
            }
            finalSourceOrder.forEachIndexed { index, pageId ->
                insertPageOrder(db, sourceNotebookId, pageId, index)
            }
            moved.forEachIndexed { index, pageId -> insertPageOrder(db, targetId, pageId, index) }
            ContentValues().apply { put("updated_at", now) }.also { values ->
                db.update("notebooks", values, "id=?", arrayOf(sourceNotebookId.toString()))
            }
            writeMetadata(db, ACTIVE_NOTEBOOK_KEY, targetId.toString())
            insertJournal(db, sourceNotebookId, "transfer:pages-out")
            insertJournal(db, targetId, "transfer:pages-in")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return NotebookTransferResult(targetId, moved, replacementId)
    }

    @Synchronized
    fun deleteNotebook(notebookId: UUID): NotebookDeletionResult {
        val db = store.writableDatabase
        val workingId = ensureWorkingCopy(db)
        require(notebookId != workingId) { "Working Copy 不能删除" }
        require(notebookExists(db, notebookId)) { "笔记本不存在" }
        val deletedPageIds = loadPageIds(db, notebookId)
        val recordedActive = readMetadata(db, ACTIVE_NOTEBOOK_KEY)?.let(::uuidOrNull)
        val nextActive = if (recordedActive == notebookId ||
            recordedActive == null || !notebookExists(db, recordedActive)
        ) {
            workingId
        } else {
            recordedActive
        }

        db.beginTransaction()
        try {
            val deleted = db.delete("notebooks", "id=?", arrayOf(notebookId.toString()))
            check(deleted == 1) { "删除笔记本失败" }
            writeMetadata(db, ACTIVE_NOTEBOOK_KEY, nextActive.toString())
            insertJournal(db, workingId, "delete:notebook:$notebookId")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return NotebookDeletionResult(notebookId, deletedPageIds, nextActive)
    }

    @Synchronized
    fun loadPage(id: UUID): Page? {
        val db = store.readableDatabase
        val page = db.query(
            "pages", arrayOf(
                "width", "height", "bookmarked", "content_revision", "created_at", "updated_at"
            ),
            "id=?", arrayOf(id.toString()), null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            Page(
                id = id,
                width = cursor.getFloat(0),
                height = cursor.getFloat(1),
                bookmarked = cursor.getInt(2) != 0,
                contentRevision = cursor.getLong(3),
                createdAt = cursor.getLong(4),
                updatedAt = cursor.getLong(5)
            )
        }
        db.rawQuery(
            """SELECT id,type,a,b,c,d,tx,ty,left_bound,top_bound,right_bound,bottom_bound,
                       z_index,created_at,updated_at
                 FROM objects WHERE page_id=? ORDER BY z_index,created_at""".trimIndent(),
            arrayOf(id.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val objectId = UUID.fromString(cursor.getString(0))
                val transform = Transform2D(
                    cursor.getFloat(2), cursor.getFloat(3), cursor.getFloat(4),
                    cursor.getFloat(5), cursor.getFloat(6), cursor.getFloat(7)
                )
                val localBounds = Bounds(
                    cursor.getFloat(8), cursor.getFloat(9), cursor.getFloat(10), cursor.getFloat(11)
                )
                val zIndex = cursor.getInt(12)
                val createdAt = cursor.getLong(13)
                val updatedAt = cursor.getLong(14)
                val obj = when (cursor.getString(1)) {
                    "STROKE" -> loadStroke(db, objectId, transform, zIndex, createdAt, updatedAt)
                    "IMAGE" -> loadImage(db, objectId, transform, zIndex, createdAt, updatedAt)
                    "TEXT" -> loadText(
                        db, objectId, transform, zIndex, createdAt, updatedAt, localBounds
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
        persistWithReceipt(change, null)
    }

    @Synchronized
    fun persistWithReceipt(change: DocumentChange, receipt: TransferReceipt?) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            upsertNotebook(db, change)
            syncPagesAndOrder(db, change)
            change.fullPages.forEach { snapshot ->
                db.delete("objects", "page_id=?", arrayOf(snapshot.metadata.id.toString()))
                snapshot.objects.forEach { upsertObject(db, snapshot.metadata.id, it) }
            }
            change.objectChanges.forEach { mutation ->
                val value = mutation.value
                if (value == null) {
                    db.delete("objects", "id=? AND page_id=?", arrayOf(mutation.objectId.toString(), mutation.pageId.toString()))
                } else {
                    upsertObject(db, mutation.pageId, value)
                }
            }
            ContentValues().apply {
                put("notebook_id", change.notebookId.toString())
                put("committed_at", System.currentTimeMillis())
                put("description", change.description)
            }.also { db.insertOrThrow("operation_journal", null, it) }
            receipt?.let {
                ContentValues().apply {
                    put("source_device_id", it.sourceDeviceId); put("item_id", it.itemId.toString())
                    put("object_id", it.objectId.toString()); put("committed_at", it.committedAt)
                }.also { values -> db.insertWithOnConflict(
                    "transfer_receipts", null, values, SQLiteDatabase.CONFLICT_IGNORE
                ) }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun findTransferReceipt(sourceDeviceId: String, itemId: UUID): UUID? =
        store.readableDatabase.query(
            "transfer_receipts", arrayOf("object_id"), "source_device_id=? AND item_id=?",
            arrayOf(sourceDeviceId, itemId.toString()), null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) UUID.fromString(cursor.getString(0)) else null }

    @Synchronized fun recover(): RecoveryManager.Result = RecoveryManager(store).recover()
    @Synchronized fun checkpoint() = store.checkpoint()

    @Synchronized
    fun referencedImageAssets(): Set<String> {
        val result = LinkedHashSet<String>()
        store.readableDatabase.query(
            "images", arrayOf("asset_path"), null, null, null, null, null
        ).use { cursor -> while (cursor.moveToNext()) result += cursor.getString(0) }
        return result
    }
    @Synchronized override fun close() = store.close()

    private fun loadNotebookMetadata(db: SQLiteDatabase, notebookId: UUID): Notebook? {
        val notebook = db.query(
            "notebooks", arrayOf("id", "title", "created_at", "updated_at"),
            "id=?", arrayOf(notebookId.toString()), null, null, null, "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            Notebook(
                id = UUID.fromString(cursor.getString(0)),
                title = cursor.getString(1),
                createdAt = cursor.getLong(2),
                updatedAt = cursor.getLong(3)
            )
        }
        db.rawQuery(
            """SELECT p.id,p.width,p.height,p.bookmarked,p.content_revision,p.created_at,p.updated_at
                 FROM page_order po JOIN pages p ON p.id=po.page_id
                WHERE po.notebook_id=? ORDER BY po.position""".trimIndent(),
            arrayOf(notebook.id.toString())
        ).use { cursor ->
            var index = 0
            while (cursor.moveToNext()) {
                notebook.addPageMetadata(
                    Notebook.PageMetadata(
                        UUID.fromString(cursor.getString(0)), cursor.getFloat(1), cursor.getFloat(2),
                        cursor.getInt(3) != 0, cursor.getLong(4), cursor.getLong(5), cursor.getLong(6)
                    ),
                    index++
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
            "notebooks", arrayOf("id"), null, null, null, null, "updated_at DESC", "1"
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
        "metadata", arrayOf("value"), "key=?", arrayOf(key), null, null, null
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun writeMetadata(db: SQLiteDatabase, key: String, value: String) {
        ContentValues().apply {
            put("key", key)
            put("value", value)
        }.also { db.insertWithOnConflict("metadata", null, it, SQLiteDatabase.CONFLICT_REPLACE) }
    }

    private fun notebookExists(db: SQLiteDatabase, id: UUID): Boolean = db.query(
        "notebooks", arrayOf("id"), "id=?", arrayOf(id.toString()), null, null, null, "1"
    ).use { it.moveToFirst() }

    private fun loadPageIds(db: SQLiteDatabase, notebookId: UUID): List<UUID> {
        val ids = ArrayList<UUID>()
        db.query(
            "page_order", arrayOf("page_id"), "notebook_id=?",
            arrayOf(notebookId.toString()), null, null, "position"
        ).use { cursor -> while (cursor.moveToNext()) ids += UUID.fromString(cursor.getString(0)) }
        return ids
    }

    private fun firstPageSize(db: SQLiteDatabase, pageId: UUID?): Pair<Float, Float> {
        if (pageId == null) return 0f to 0f
        return db.query(
            "pages", arrayOf("width", "height"), "id=?",
            arrayOf(pageId.toString()), null, null, null, "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getFloat(0) to cursor.getFloat(1) else 0f to 0f
        }
    }

    private fun insertNotebook(db: SQLiteDatabase, id: UUID, title: String, now: Long) {
        ContentValues().apply {
            put("id", id.toString())
            put("title", title)
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
        now: Long
    ) {
        ContentValues().apply {
            put("id", id.toString())
            put("notebook_id", notebookId.toString())
            put("width", width.coerceAtLeast(0f))
            put("height", height.coerceAtLeast(0f))
            put("bookmarked", 0)
            put("content_revision", 0L)
            put("created_at", now)
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
        ContentValues().apply {
            put("id", obj.id.toString()); put("page_id", pageId.toString()); put("type", obj.type.name)
            put("a", t.a); put("b", t.b); put("c", t.c); put("d", t.d); put("tx", t.tx); put("ty", t.ty)
            put("left_bound", bounds.left); put("top_bound", bounds.top)
            put("right_bound", bounds.right); put("bottom_bound", bounds.bottom)
            put("z_index", obj.zIndex); put("created_at", obj.createdAt); put("updated_at", obj.updatedAt)
        }.also { db.insertWithOnConflict("objects", null, it, SQLiteDatabase.CONFLICT_REPLACE) }
        when (obj) {
            is StrokeObject -> {
                val style = obj.stroke.style
                ContentValues().apply {
                    put("object_id", obj.id.toString()); put("base_width", style.baseWidth); put("color", style.color)
                    put("pressure_a", style.pressureCurve.a); put("pressure_gamma", style.pressureCurve.gamma)
                    put("pen_type", style.penType.name); put("points_blob", PointBlobCodec.encode(obj.stroke.points))
                }.also { db.insertWithOnConflict("strokes", null, it, SQLiteDatabase.CONFLICT_REPLACE) }
            }
            is ImageObject -> ContentValues().apply {
                put("object_id", obj.id.toString()); put("asset_path", obj.assetPath)
                put("mime_type", obj.mimeType); put("pixel_width", obj.pixelWidth)
                put("pixel_height", obj.pixelHeight); put("exif_orientation", obj.exifOrientation)
            }.also { db.insertWithOnConflict("images", null, it, SQLiteDatabase.CONFLICT_REPLACE) }
            is TextObject -> ContentValues().apply {
                put("object_id", obj.id.toString()); put("content", obj.text)
                put("font_family", obj.fontFamily.name); put("font_size", obj.fontSize)
            }.also { db.insertWithOnConflict("texts", null, it, SQLiteDatabase.CONFLICT_REPLACE) }
        }
    }

    private fun loadStroke(
        db: SQLiteDatabase, id: UUID, transform: Transform2D, z: Int, created: Long, updated: Long
    ): StrokeObject? = db.query(
        "strokes", arrayOf("base_width", "color", "pressure_a", "pressure_gamma", "pen_type", "points_blob"),
        "object_id=?", arrayOf(id.toString()), null, null, null
    ).use { c ->
        if (!c.moveToFirst()) return@use null
        val style = PenStyle(c.getFloat(0), c.getInt(1), PressureCurve(c.getFloat(2), c.getFloat(3)), PenType.valueOf(c.getString(4)))
        StrokeObject(id, transform, z, created, updated, Stroke(PointBlobCodec.decode(c.getBlob(5)), style))
    }

    private fun loadImage(
        db: SQLiteDatabase, id: UUID, transform: Transform2D, z: Int, created: Long, updated: Long
    ): ImageObject? = db.query(
        "images", arrayOf("asset_path", "mime_type", "pixel_width", "pixel_height", "exif_orientation"),
        "object_id=?", arrayOf(id.toString()), null, null, null
    ).use { c ->
        if (!c.moveToFirst()) return@use null
        ImageObject(id, transform, z, created, updated, c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getInt(4))
    }

    private fun loadText(
        db: SQLiteDatabase, id: UUID, transform: Transform2D, z: Int, created: Long, updated: Long,
        bounds: Bounds
    ): TextObject? = db.query(
        "texts", arrayOf("content", "font_family", "font_size"),
        "object_id=?", arrayOf(id.toString()), null, null, null
    ).use { c ->
        if (!c.moveToFirst()) return@use null
        TextObject(id, transform, z, created, updated, c.getString(0), TextFontFamily.valueOf(c.getString(1)), c.getFloat(2), bounds)
    }

    companion object {
        const val WORKING_COPY_TITLE = "Working Copy"
        private const val WORKING_NOTEBOOK_KEY = "working_notebook_id"
        private const val ACTIVE_NOTEBOOK_KEY = "active_notebook_id"
        private const val STARTUP_BEHAVIOR_KEY = "startup_behavior"
    }
}
