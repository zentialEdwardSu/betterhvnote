package com.betterhv.note.export

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.betterhv.note.storage.DocumentChange
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.template.TemplateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Reproducible timing comparison used by Requirement 6. It is intentionally a
 * single sequential run (no warm-up tricks) so the numbers match what a user
 * sees on the device; the report is also written to the export run report.
 */
@RunWith(AndroidJUnit4::class)
class ExportBenchmarkInstrumentedTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()

  @Test fun fastModeIsMeaningfullyFasterThanVectorAndHotRunReusesPageCache() = runBlocking {
    val notebooks = NotebookRepository(app)
    val tasks = ExportTaskRepository(app)
    val root = File(app.cacheDir, "export-benchmark-${UUID.randomUUID()}")
    val engine = ExportEngine(
      notebooks,
      tasks,
      PageRenderer(File(app.filesDir, "documents"), TemplateStore.get(app)),
      root,
    )
    val seeded = seedInkNotebook(
      notebooks,
      "Benchmark ${UUID.randomUUID()}",
      pageCount = BENCHMARK_PAGES,
      strokesPerPage = BENCHMARK_STROKES_PER_PAGE,
    )
    val vector = tasks.createTask(
      seeded.id, ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList(), ExportRenderMode.VECTOR,
    )
    val fast = tasks.createTask(
      seeded.id, ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList(), ExportRenderMode.FAST,
    )
    try {
      val vectorResult = engine.generate(vector.id) as ExportResult.Success
      val vectorReport = requireNotNull(tasks.runReport(vector.id))
      val fastResult = engine.generate(fast.id) as ExportResult.Success
      val fastReport = requireNotNull(tasks.runReport(fast.id))
      Log.i(
        TAG,
        "cold vector total=${vectorReport.totalMs}ms render=${vectorReport.renderMs} " +
          "graft=${vectorReport.mergeGraftMs} save=${vectorReport.mergeSaveMs} " +
          "bytes=${vectorResult.artifact.byteLength}",
      )
      Log.i(
        TAG,
        "cold fast total=${fastReport.totalMs}ms render=${fastReport.renderMs} " +
          "graft=${fastReport.mergeGraftMs} save=${fastReport.mergeSaveMs} " +
          "bytes=${fastResult.artifact.byteLength}",
      )

      assertTrue(
        "fast mode should be at least 25% faster " +
          "(vector=${vectorReport.totalMs}ms fast=${fastReport.totalMs}ms)",
        fastReport.totalMs * 4 <= vectorReport.totalMs * 3,
      )

      addStrokeToPage(notebooks, seeded.id, seeded.pageIds.first())
      val hotResult = engine.generate(vector.id) as ExportResult.Success
      val hotReport = requireNotNull(tasks.runReport(vector.id))
      Log.i(
        TAG,
        "hot vector total=${hotReport.totalMs}ms hits=${hotReport.cacheHits} " +
          "misses=${hotReport.cacheMisses} bytes=${hotResult.artifact.byteLength}",
      )
      assertEquals(BENCHMARK_PAGES - 1, hotReport.cacheHits)
      assertEquals(1, hotReport.cacheMisses)
      assertTrue("hot run must reuse at least 90% of pages", hotReport.cacheHits * 10 >= hotReport.pageCount * 9)
    } finally {
      runCatching { engine.deleteTask(vector.id) }
      runCatching { engine.deleteTask(fast.id) }
      notebooks.deleteNotebook(seeded.id)
      root.deleteRecursively()
      tasks.close()
      notebooks.close()
    }
  }

  private fun addStrokeToPage(notebooks: NotebookRepository, notebookId: UUID, pageId: UUID) {
    val notebook = requireNotNull(notebooks.loadNotebook(notebookId))
    val page = requireNotNull(notebooks.loadPage(pageId))
    page.addObject(newSampleStroke(BENCHMARK_STROKES_PER_PAGE + 1))
    notebook.attachPage(page)
    notebooks.persist(DocumentChange.fullPage(notebook, page, "test:benchmark-edit"))
  }

  private companion object {
    const val TAG = "ExportBenchmark"
    const val BENCHMARK_PAGES = 12
    const val BENCHMARK_STROKES_PER_PAGE = 60
  }
}
