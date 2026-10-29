package com.betterhv.note.export

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.betterhv.note.storage.MigrationManager
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.storage.SQLiteStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExportMigrationInstrumentedTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()

  @Test fun v8ExportTasksMigrateToVectorMode() {
    val file = File(app.cacheDir, "export-migration-${UUID.randomUUID()}.inknote")
    val taskId = UUID.randomUUID()
    try {
      SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
          """CREATE TABLE export_tasks(
                    id TEXT PRIMARY KEY,
                    notebook_id TEXT NOT NULL,
                    scope TEXT NOT NULL,
                    format TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    last_used_at INTEGER NOT NULL,
                    artifact_id TEXT,
                    artifact_path TEXT,
                    artifact_fingerprint TEXT,
                    artifact_size INTEGER,
                    artifact_sha256 BLOB,
                    artifact_created_at INTEGER,
                    last_error TEXT
                )
          """.trimIndent(),
        )
        db.execSQL(
          "INSERT INTO export_tasks(id, notebook_id, scope, format, created_at, last_used_at) " +
            "VALUES(?, ?, 'ALL_PAGES', 'PDF', 1, 1)",
          arrayOf(taskId.toString(), UUID.randomUUID().toString()),
        )
        db.version = 8
      }

      SQLiteStore(app, file).use { store ->
        val db = store.readableDatabase
        assertEquals(MigrationManager.VERSION, db.version)
        db.rawQuery("SELECT render_mode FROM export_tasks WHERE id=?", arrayOf(taskId.toString())).use { cursor ->
          assertEquals(1, cursor.count)
          cursor.moveToFirst()
          assertEquals("VECTOR", cursor.getString(0))
        }
        db.rawQuery("SELECT value FROM metadata WHERE key='schema_version'", null).use { cursor ->
          cursor.moveToFirst()
          assertEquals(MigrationManager.VERSION.toString(), cursor.getString(0))
        }
      }
    } finally {
      listOf("", "-wal", "-shm").forEach { suffix -> File(file.path + suffix).delete() }
    }
  }

  @Test fun taskRenderModeSurvivesRepositoryReopen() {
    val notebooks = NotebookRepository(app)
    val seeded = seedInkNotebook(notebooks, "Mode persistence ${UUID.randomUUID()}", pageCount = 1, strokesPerPage = 0)
    var tasks = ExportTaskRepository(app)
    val fast = tasks.createTask(
      seeded.id, ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList(), ExportRenderMode.FAST,
    )
    tasks.close()
    tasks = ExportTaskRepository(app)
    try {
      assertEquals(ExportRenderMode.FAST, tasks.loadTask(fast.id)?.renderMode)
      assertEquals(
        ExportRenderMode.VECTOR,
        tasks.createTask(
          seeded.id, ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList(), ExportRenderMode.VECTOR,
        ).renderMode,
      )
    } finally {
      tasks.listSummaries().filter { it.task.notebookId == seeded.id }.forEach { tasks.deleteTask(it.task.id) }
      tasks.close()
      notebooks.deleteNotebook(seeded.id)
      notebooks.close()
    }
  }
}
