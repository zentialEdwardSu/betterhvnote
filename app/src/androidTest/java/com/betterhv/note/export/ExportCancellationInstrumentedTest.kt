package com.betterhv.note.export

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.template.TemplateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExportCancellationInstrumentedTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()

  @Test fun cancelDuringRenderingLeavesNoArtifactAndKeepsPageCachesReusable() = runBlocking {
    val notebooks = NotebookRepository(app)
    val tasks = ExportTaskRepository(app)
    val root = File(app.cacheDir, "export-cancel-${UUID.randomUUID()}")
    val engine = ExportEngine(
      notebooks,
      tasks,
      PageRenderer(File(app.filesDir, "documents"), TemplateStore.get(app)),
      root,
    )
    val seeded = seedInkNotebook(notebooks, "Cancel ${UUID.randomUUID()}", pageCount = 4, strokesPerPage = 20)
    val task = tasks.createTask(
      seeded.id, ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList(), ExportRenderMode.VECTOR,
    )
    try {
      val started = CompletableDeferred<Unit>()
      val job = async(Dispatchers.IO) {
        engine.generate(task.id) { progress ->
          if (progress.stage == ExportProgress.Stage.RENDERING && progress.current >= 1) {
            started.complete(Unit)
          }
        }
      }
      withTimeout(60_000) { started.await() }
      job.cancelAndJoin()

      assertTrue(
        "cancelled exports must not publish an artifact",
        File(root, "artifacts").listFiles().orEmpty().none { it.isFile },
      )
      assertTrue(
        "cancelled exports must not leave page temporaries",
        File(root, "pages").listFiles().orEmpty().none { it.name.endsWith(".tmp") },
      )
      assertEquals(
        ExportRunResult.CANCELLED,
        requireNotNull(tasks.runReport(task.id)).result,
      )

      val retried = withTimeout(120_000) { engine.generate(task.id) }
      assertTrue("completed page caches must be reusable after cancel", retried is ExportResult.Success)
    } finally {
      runCatching { engine.deleteTask(task.id) }
      notebooks.deleteNotebook(seeded.id)
      root.deleteRecursively()
      tasks.close()
      notebooks.close()
    }
  }

  @Test fun cancelDuringAssemblyLeavesNoArtifact() = runBlocking {
    val notebooks = NotebookRepository(app)
    val tasks = ExportTaskRepository(app)
    val root = File(app.cacheDir, "export-cancel-merge-${UUID.randomUUID()}")
    val engine = ExportEngine(
      notebooks,
      tasks,
      PageRenderer(File(app.filesDir, "documents"), TemplateStore.get(app)),
      root,
    )
    val seeded = seedInkNotebook(notebooks, "Cancel merge ${UUID.randomUUID()}", pageCount = 10, strokesPerPage = 3)
    val task = tasks.createTask(
      seeded.id, ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList(), ExportRenderMode.FAST,
    )
    try {
      val started = CompletableDeferred<Unit>()
      val job = async(Dispatchers.IO) {
        engine.generate(task.id) { progress ->
          if (progress.stage == ExportProgress.Stage.ASSEMBLING) started.complete(Unit)
        }
      }
      withTimeout(120_000) { started.await() }
      job.cancelAndJoin()

      assertTrue(
        "cancelled merge must not publish an artifact",
        File(root, "artifacts").listFiles().orEmpty().none { it.isFile },
      )
      assertTrue(
        "cancelled merge must not leave temporaries",
        File(root, "artifacts").listFiles().orEmpty().none { it.name.endsWith(".tmp") },
      )
    } finally {
      runCatching { engine.deleteTask(task.id) }
      notebooks.deleteNotebook(seeded.id)
      root.deleteRecursively()
      tasks.close()
      notebooks.close()
    }
  }
}
