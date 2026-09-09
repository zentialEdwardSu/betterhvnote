package com.betterhv.note.export

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import com.betterhv.note.doc.PageKind
import com.betterhv.note.storage.SQLiteStore
import com.betterhv.note.template.TemplateStore
import java.io.File
import java.util.UUID

class ExportTaskRepository(context: Context) : AutoCloseable {
  private val documentsDir = File(context.filesDir, "documents").also(File::mkdirs)
  private val store = SQLiteStore(context.applicationContext, File(documentsDir, "default.inknote"))
  private val templates = TemplateStore.get(context.applicationContext)

  @Synchronized
  fun createTask(notebookId: UUID, scope: ExportScope, format: ExportFormat, pageIds: List<UUID>): ExportTask {
    validate(scope, format, pageIds)
    require(notebookExists(notebookId)) { "Notebook does not exist" }
    val normalizedPages = when (scope) {
      ExportScope.ALL_PAGES -> emptyList()
      else -> orderedExistingPages(notebookId).filter(pageIds.toSet()::contains)
    }
    if (scope != ExportScope.ALL_PAGES) {
      require(normalizedPages.size == pageIds.toSet().size) { "Selection contains deleted pages" }
    }
    listTasksRaw().firstOrNull {
      it.notebookId == notebookId && it.scope == scope && it.format == format &&
        it.pageIds == normalizedPages
    }?.let {
      touch(it.id)
      return loadTask(it.id)!!
    }

    val now = System.currentTimeMillis()
    val task = ExportTask(UUID.randomUUID(), notebookId, scope, format, normalizedPages, now, now)
    val db = store.writableDatabase
    db.transaction {
      try {
        ContentValues().apply {
          put("id", task.id.toString())
          put("notebook_id", notebookId.toString())
          put("scope", scope.name)
          put("format", format.name)
          put("created_at", now)
          put("last_used_at", now)
        }.also { insertOrThrow("export_tasks", null, it) }
        normalizedPages.forEachIndexed { index, pageId ->
          ContentValues().apply {
            put("task_id", task.id.toString())
            put("page_id", pageId.toString())
            put("position", index)
          }.also { insertOrThrow("export_task_pages", null, it) }
        }
      } finally {
      }
    }
    return task
  }

  @Synchronized
  fun listSummaries(): List<ExportTaskSummary> = listTasksRaw().map { task ->
    val title = notebookTitle(task.notebookId) ?: "已删除的笔记本"
    val sources = resolveSources(task)
    val missing = task.scope != ExportScope.ALL_PAGES && sources.size != task.pageIds.size
    val record = artifactRecord(task.id)
    val currentFingerprint = if (missing || sources.isEmpty()) null else fingerprint(task, sources)
    val stale = if (missing) task.pageIds.size - sources.size else stalePageCount(task, sources)
    val fileExists = record?.path?.let(::File)?.isFile == true
    val state = ExportRevision.state(
      missing,
      fileExists,
      record?.fingerprint,
      currentFingerprint,
      record?.lastError != null,
    )
    ExportTaskSummary(task, title, sources.size, stale, state, record?.createdAt, record?.lastError)
  }

  @Synchronized
  fun listNotebookOptions(): List<ExportNotebookOption> {
    val db = store.readableDatabase
    return db.query(
      "notebooks",
      arrayOf("id", "title"),
      null,
      null,
      null,
      null,
      "updated_at DESC",
    ).use { cursor ->
      buildList {
        while (cursor.moveToNext()) {
          val id = UUID.fromString(cursor.getString(0))
          val task = ExportTask(UUID(0, 0), id, ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList(), 0, 0)
          add(ExportNotebookOption(id, cursor.getString(1), resolveSources(task)))
        }
      }
    }
  }

  @Synchronized
  fun loadTask(id: UUID): ExportTask? = listTasksRaw().firstOrNull { it.id == id }

  @Synchronized
  fun resolveSources(task: ExportTask): List<ExportPageSource> {
    val requested = when (task.scope) {
      ExportScope.ALL_PAGES -> orderedExistingPages(task.notebookId)
      else -> task.pageIds
    }
    if (requested.isEmpty()) return emptyList()
    data class Revision(val content: Long, val background: String)
    val revisions = HashMap<UUID, Revision>()
    store.readableDatabase.query(
      "pages",
      arrayOf("id", "content_revision", "kind", "template_id"),
      "notebook_id=?",
      arrayOf(task.notebookId.toString()),
      null,
      null,
      null,
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val kind = runCatching { PageKind.valueOf(cursor.getString(2)) }.getOrDefault(PageKind.BLANK)
        val background = if (kind == PageKind.PDF_SOURCE) {
          "pdf"
        } else {
          templates.visualFingerprint(if (cursor.isNull(3)) null else cursor.getString(3))
        }
        revisions[UUID.fromString(cursor.getString(0))] = Revision(cursor.getLong(1), background)
      }
    }
    return requested.mapIndexedNotNull { index, id ->
      revisions[id]?.let { ExportPageSource(id, it.content, index, it.background) }
    }
  }

  @Synchronized
  fun currentArtifact(task: ExportTask, sources: List<ExportPageSource>): ExportArtifact? {
    val record = artifactRecord(task.id) ?: return null
    if (record.fingerprint != fingerprint(task, sources)) return null
    val file = record.path?.let(::File) ?: return null
    if (!file.isFile || record.id == null || record.sha256 == null || record.createdAt == null) return null
    return ExportArtifact(
      record.id, task.id, file, displayName(task), task.format.mimeType,
      file.length(), record.sha256, record.fingerprint, record.createdAt,
    )
  }

  @Synchronized
  fun cachedPage(taskId: UUID, source: ExportPageSource): File? = store.readableDatabase.query(
    "export_page_cache",
    arrayOf("content_revision", "cache_path"),
    "task_id=? AND page_id=?",
    arrayOf(taskId.toString(), source.id.toString()),
    null,
    null,
    null,
  ).use { cursor ->
    if (!cursor.moveToFirst() || cursor.getLong(0) != source.contentRevision) {
      null
    } else {
      File(cursor.getString(1)).takeIf {
        it.isFile && it.name == pageCacheFileName(source)
      }
    }
  }

  @Synchronized
  fun recordCachedPage(taskId: UUID, source: ExportPageSource, file: File): File? {
    val previous = store.readableDatabase.query(
      "export_page_cache",
      arrayOf("cache_path"),
      "task_id=? AND page_id=?",
      arrayOf(taskId.toString(), source.id.toString()),
      null,
      null,
      null,
    ).use { cursor -> if (cursor.moveToFirst()) File(cursor.getString(0)) else null }
    ContentValues().apply {
      put("task_id", taskId.toString())
      put("page_id", source.id.toString())
      put("content_revision", source.contentRevision)
      put("cache_path", file.absolutePath)
    }.also {
      store.writableDatabase.insertWithOnConflict(
        "export_page_cache",
        null,
        it,
        SQLiteDatabase.CONFLICT_REPLACE,
      )
    }
    return previous?.takeIf { it.absolutePath != file.absolutePath }
  }

  @Synchronized
  fun removeObsoleteCaches(taskId: UUID, retained: Set<UUID>): List<File> {
    val removed = ArrayList<File>()
    val db = store.writableDatabase
    db.query(
      "export_page_cache",
      arrayOf("page_id", "cache_path"),
      "task_id=?",
      arrayOf(taskId.toString()),
      null,
      null,
      null,
    ).use { cursor ->
      while (cursor.moveToNext()) {
        if (UUID.fromString(cursor.getString(0)) !in retained) removed += File(cursor.getString(1))
      }
    }
    if (retained.isEmpty()) {
      db.delete("export_page_cache", "task_id=?", arrayOf(taskId.toString()))
    } else {
      val placeholders = retained.joinToString(",") { "?" }
      val args = ArrayList<String>().apply {
        add(taskId.toString())
        retained.forEach { add(it.toString()) }
      }
      db.delete(
        "export_page_cache",
        "task_id=? AND page_id NOT IN ($placeholders)",
        args.toTypedArray(),
      )
    }
    return removed
  }

  @Synchronized
  fun recordArtifact(artifact: ExportArtifact): File? {
    val previous = artifactRecord(artifact.taskId)?.path?.let(::File)
    ContentValues().apply {
      put("artifact_id", artifact.id.toString())
      put("artifact_path", artifact.file.absolutePath)
      put("artifact_fingerprint", artifact.fingerprint)
      put("artifact_size", artifact.byteLength)
      put("artifact_sha256", artifact.sha256)
      put("artifact_created_at", artifact.createdAt)
      put("last_used_at", artifact.createdAt)
      putNull("last_error")
    }.also {
      store.writableDatabase.update("export_tasks", it, "id=?", arrayOf(artifact.taskId.toString()))
    }
    return previous?.takeIf { it.absolutePath != artifact.file.absolutePath }
  }

  @Synchronized
  fun markError(taskId: UUID, message: String) {
    ContentValues().apply { put("last_error", message) }.also {
      store.writableDatabase.update("export_tasks", it, "id=?", arrayOf(taskId.toString()))
    }
  }

  @Synchronized
  fun deleteTask(id: UUID): List<File> {
    val files = ArrayList<File>()
    artifactRecord(id)?.path?.let { files += File(it) }
    store.readableDatabase.query(
      "export_page_cache",
      arrayOf("cache_path"),
      "task_id=?",
      arrayOf(id.toString()),
      null,
      null,
      null,
    ).use { cursor -> while (cursor.moveToNext()) files += File(cursor.getString(0)) }
    store.writableDatabase.delete("export_tasks", "id=?", arrayOf(id.toString()))
    return files
  }

  @Synchronized
  fun referencedInternalFiles(): Set<String> {
    val paths = LinkedHashSet<String>()
    store.readableDatabase.query(
      "export_tasks",
      arrayOf("artifact_path"),
      "artifact_path IS NOT NULL",
      null,
      null,
      null,
      null,
    ).use { cursor -> while (cursor.moveToNext()) paths += File(cursor.getString(0)).absolutePath }
    store.readableDatabase.query(
      "export_page_cache",
      arrayOf("cache_path"),
      null,
      null,
      null,
      null,
      null,
    ).use { cursor -> while (cursor.moveToNext()) paths += File(cursor.getString(0)).absolutePath }
    return paths
  }

  fun fingerprint(task: ExportTask, sources: List<ExportPageSource>): String =
    ExportRevision.fingerprint(task.format, sources, RENDERER_VERSION)

  fun displayName(task: ExportTask): String =
    ExportFileNames.displayName(notebookTitle(task.notebookId), task.id, task.format)

  private fun validate(scope: ExportScope, format: ExportFormat, pageIds: List<UUID>) {
    require(scope == ExportScope.SINGLE_PAGE || format == ExportFormat.PDF) { "Multi-page tasks only support PDF" }
    require(scope != ExportScope.SINGLE_PAGE || pageIds.distinct().size == 1) {
      "Single-page task must select one page"
    }
    require(scope != ExportScope.SELECTED_PAGES || pageIds.isNotEmpty()) { "Select at least one page" }
    require(scope != ExportScope.ALL_PAGES || pageIds.isEmpty()) { "All-pages task cannot store fixed pages" }
  }

  private fun listTasksRaw(): List<ExportTask> {
    val db = store.readableDatabase
    val pages = HashMap<UUID, MutableList<Pair<Int, UUID>>>()
    db.query(
      "export_task_pages",
      arrayOf("task_id", "page_id", "position"),
      null,
      null,
      null,
      null,
      "task_id, position",
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val taskId = UUID.fromString(cursor.getString(0))
        pages.getOrPut(taskId, ::ArrayList) += cursor.getInt(2) to UUID.fromString(cursor.getString(1))
      }
    }
    return db.query(
      "export_tasks",
      arrayOf("id", "notebook_id", "scope", "format", "created_at", "last_used_at"),
      null,
      null,
      null,
      null,
      "last_used_at DESC, created_at DESC",
    ).use { cursor ->
      buildList {
        while (cursor.moveToNext()) {
          val id = UUID.fromString(cursor.getString(0))
          add(
            ExportTask(
              id,
              UUID.fromString(cursor.getString(1)),
              ExportScope.valueOf(cursor.getString(2)),
              ExportFormat.valueOf(cursor.getString(3)),
              pages[id].orEmpty().sortedBy { it.first }.map { it.second },
              cursor.getLong(4),
              cursor.getLong(5),
            )
          )
        }
      }
    }
  }

  private fun orderedExistingPages(notebookId: UUID): List<UUID> = store.readableDatabase.query(
    "page_order",
    arrayOf("page_id"),
    "notebook_id=?",
    arrayOf(notebookId.toString()),
    null,
    null,
    "position",
  ).use { cursor -> buildList { while (cursor.moveToNext()) add(UUID.fromString(cursor.getString(0))) } }

  private fun stalePageCount(task: ExportTask, sources: List<ExportPageSource>): Int =
    if (task.format == ExportFormat.PNG) {
      val record = artifactRecord(task.id)
      if (record?.fingerprint == fingerprint(task, sources) && record.path?.let(::File)?.isFile == true) {
        0
      } else {
        sources.size
      }
    } else {
      sources.count { cachedPage(task.id, it) == null }
    }

  private fun artifactRecord(taskId: UUID): ArtifactRecord? = store.readableDatabase.query(
    "export_tasks",
    arrayOf(
      "artifact_id",
      "artifact_path",
      "artifact_fingerprint",
      "artifact_sha256",
      "artifact_created_at",
      "last_error",
    ),
    "id=?",
    arrayOf(taskId.toString()),
    null,
    null,
    null,
  ).use { cursor ->
    if (!cursor.moveToFirst()) {
      null
    } else {
      ArtifactRecord(
        cursor.getString(0)?.let(UUID::fromString),
        cursor.getString(1),
        cursor.getString(2),
        cursor.getBlob(3),
        if (cursor.isNull(4)) null else cursor.getLong(4),
        cursor.getString(5),
      )
    }
  }

  private fun touch(id: UUID) {
    ContentValues().apply { put("last_used_at", System.currentTimeMillis()) }.also {
      store.writableDatabase.update("export_tasks", it, "id=?", arrayOf(id.toString()))
    }
  }

  private fun notebookExists(id: UUID): Boolean = store.readableDatabase.query(
    "notebooks",
    arrayOf("id"),
    "id=?",
    arrayOf(id.toString()),
    null,
    null,
    null,
  ).use { it.moveToFirst() }

  private fun notebookTitle(id: UUID): String? = store.readableDatabase.query(
    "notebooks",
    arrayOf("title"),
    "id=?",
    arrayOf(id.toString()),
    null,
    null,
    null,
  ).use { if (it.moveToFirst()) it.getString(0) else null }

  override fun close() = store.close()

  private data class ArtifactRecord(
    val id: UUID?,
    val path: String?,
    val fingerprint: String?,
    val sha256: ByteArray?,
    val createdAt: Long?,
    val lastError: String?,
  )

  companion object {
    const val RENDERER_VERSION = 6

    fun pageCacheFileName(source: ExportPageSource): String =
      "${source.id}-r${source.contentRevision}-b${backgroundHash(source.backgroundRevision)}-v$RENDERER_VERSION.pdf"

    private fun backgroundHash(value: String): String =
      java.security.MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        .take(6).joinToString("") { "%02x".format(it) }
  }
}
