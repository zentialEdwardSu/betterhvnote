package com.betterhv.note.export

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.template.TemplateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ExportCacheInstrumentedTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()

  @Test fun pageFilesAreSharedAcrossTasksAndIsolatedByRenderMode() = runBlocking {
    val notebooks = NotebookRepository(app)
    val root = File(app.cacheDir, "export-cache-${UUID.randomUUID()}")
    val tasks = ExportTaskRepository(app, File(root, "reports"))
    val engine = ExportEngine(
      notebooks,
      tasks,
      PageRenderer(File(app.filesDir, "documents"), TemplateStore.get(app)),
      root,
    )
    val seeded = seedInkNotebook(notebooks, "Cache ${UUID.randomUUID()}", pageCount = 2, strokesPerPage = 2)
    val single = tasks.createTask(
      seeded.id, ExportScope.SINGLE_PAGE, ExportFormat.PDF, listOf(seeded.pageIds.first()),
      ExportRenderMode.VECTOR,
    )
    val allVector = tasks.createTask(
      seeded.id, ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList(), ExportRenderMode.VECTOR,
    )
    val allFast = tasks.createTask(
      seeded.id, ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList(), ExportRenderMode.FAST,
    )
    try {
      val stages = CopyOnWriteArrayList<ExportProgress.Stage>()
      val singleResult = engine.generate(single.id) { stages += it.stage } as ExportResult.Success
      assertFalse("single-page export must not merge", stages.contains(ExportProgress.Stage.ASSEMBLING))
      assertNotNull(stages.firstOrNull { it == ExportProgress.Stage.RENDERING })

      val sources = tasks.resolveSources(requireNotNull(tasks.loadTask(allVector.id)))
      val sharedPage = requireNotNull(tasks.cachedPage(sources[0], ExportRenderMode.VECTOR))
      assertArrayEquals(
        "single-page artifact is a copy of the page cache",
        sharedPage.readBytes(),
        singleResult.artifact.file.readBytes(),
      )

      val firstReport = requireNotNull(tasks.runReport(single.id))
      assertEquals(1, firstReport.cacheMisses)
      assertEquals(0, firstReport.cacheHits)

      engine.generate(allVector.id)
      val vectorReport = requireNotNull(tasks.runReport(allVector.id))
      assertEquals(1, vectorReport.cacheHits)
      assertEquals(1, vectorReport.cacheMisses)

      engine.generate(allFast.id)
      val fastReport = requireNotNull(tasks.runReport(allFast.id))
      assertEquals("fast mode must not reuse vector page files", 0, fastReport.cacheHits)
      assertEquals(2, fastReport.cacheMisses)

      engine.pruneOrphanFiles()
      assertNotNull("orphan pruning must retain referenced page files", tasks.cachedPage(sources[0], ExportRenderMode.FAST))
      assertNotNull("orphan pruning must retain run reports", tasks.runReport(allFast.id))
      assertNotNull("orphan pruning must retain the vector page referenced by another task", tasks.cachedPage(sources[0], ExportRenderMode.VECTOR))

      engine.deleteTask(single.id)
      assertNotNull(
        "a page file referenced by another task must survive task deletion",
        tasks.cachedPage(sources[0], ExportRenderMode.VECTOR),
      )

      engine.deleteTask(allVector.id)
      assertNull(
        "the last reference must remove the page file",
        tasks.cachedPage(sources[0], ExportRenderMode.VECTOR),
      )
    } finally {
      listOf(single, allVector, allFast).forEach { task -> runCatching { engine.deleteTask(task.id) } }
      notebooks.deleteNotebook(seeded.id)
      root.deleteRecursively()
      tasks.close()
      notebooks.close()
    }
  }
}
