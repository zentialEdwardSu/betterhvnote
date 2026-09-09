package com.betterhv.note.export

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.template.TemplateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExportWorkflowInstrumentedTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()

  @Test fun generationSurvivesConcurrentCleanupAndReusesArtifact() = runBlocking {
    val notebooks = NotebookRepository(app)
    val tasks = ExportTaskRepository(app)
    val id = notebooks.createBlankNotebook("Export regression ${UUID.randomUUID()}", 120f, 160f)
    val root = File(app.cacheDir, "export-test-${UUID.randomUUID()}")
    val engine = ExportEngine(notebooks, tasks, PageRenderer(File(app.filesDir, "documents"), TemplateStore.get(app)), root)
    val task = tasks.createTask(id, ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList())
    try {
      val rendering = CompletableDeferred<Unit>()
      val generation = async(Dispatchers.IO) {
        engine.generate(task.id) { if (it.stage == ExportProgress.Stage.RENDERING) rendering.complete(Unit) }
      }
      val cleanup = async(Dispatchers.IO) {
        rendering.await()
        engine.pruneOrphanFiles()
      }
      val result = withTimeout(30_000) { generation.await() }
      withTimeout(30_000) { cleanup.await() }
      assertTrue(result.toString(), result is ExportResult.Success)
      val artifact = (result as ExportResult.Success).artifact
      assertTrue(artifact.file.isFile)
      assertTrue(artifact.byteLength > 0)
      val reused = engine.generate(task.id) as ExportResult.Success
      assertEquals(artifact.id, reused.artifact.id)
    } finally {
      engine.deleteTask(task.id)
      notebooks.deleteNotebook(id)
      root.deleteRecursively()
      tasks.close()
      notebooks.close()
    }
  }

  @Test fun fileFailureHasStageCauseAndPersistentDiagnostic() = runBlocking {
    val notebooks = NotebookRepository(app)
    val tasks = ExportTaskRepository(app)
    val id = notebooks.createBlankNotebook("Export failure ${UUID.randomUUID()}", 120f, 160f)
    val root = File(app.cacheDir, "export-blocked-${UUID.randomUUID()}").apply { writeText("not a directory") }
    val task = tasks.createTask(id, ExportScope.SINGLE_PAGE, ExportFormat.PNG, notebooks.loadNotebook(id)!!.pageOrder)
    val engine = ExportEngine(notebooks, tasks, PageRenderer(File(app.filesDir, "documents"), TemplateStore.get(app)), root)
    try {
      val failure = engine.generate(task.id) as ExportResult.Failure
      val diagnostic = requireNotNull(failure.diagnostic)
      assertEquals(ExportProgress.Stage.PREPARING, diagnostic.stage)
      assertTrue(diagnostic.reason.contains("Could not create export directory"))
      assertTrue(diagnostic.details.contains(root.absolutePath))
      assertTrue(File(app.cacheDir, "export-diagnostics/${diagnostic.id}.log").isFile)
      assertEquals(ExportTaskState.FAILED, tasks.listSummaries().first { it.task.id == task.id }.state)
    } finally {
      tasks.deleteTask(task.id)
      notebooks.deleteNotebook(id)
      root.delete()
      tasks.close()
      notebooks.close()
    }
  }

  @Test fun creationQueuesAutomaticallyDeduplicatesAndSupportsCancelRetry() = runBlocking {
    val notebooks = NotebookRepository(app)
    val ids = List(2) { notebooks.createBlankNotebook("Queue test ${UUID.randomUUID()}", 100f, 140f) }
    val store = ViewModelStore()
    val vm = withContext(Dispatchers.Main) {
      ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[ExportViewModel::class.java]
    }
    val gate = CompletableDeferred<Boolean>()
    try {
      withContext(Dispatchers.Main) { vm.createTask(ids[0], ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList()) { gate.await() } }
      val first = withTimeout(10_000) { vm.state.first { it.activeTaskId != null } }.activeTaskId!!
      withContext(Dispatchers.Main) {
        vm.generate(first) { error("Duplicate ran") }
        vm.createTask(ids[1], ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList()) { true }
      }
      val queued = withTimeout(10_000) { vm.state.first { it.queuedTaskIds.isNotEmpty() } }.queuedTaskIds.single()
      withContext(Dispatchers.Main) { vm.cancel(queued) }
      withTimeout(10_000) { vm.state.first { queued !in it.queuedTaskIds } }
      gate.complete(true)
      withTimeout(30_000) { vm.state.first { it.tasks.any { task -> task.task.id == first && task.state == ExportTaskState.CURRENT } } }
      withContext(Dispatchers.Main) { vm.generate(queued) { true } }
      withTimeout(30_000) { vm.state.first { it.tasks.any { task -> task.task.id == queued && task.state == ExportTaskState.CURRENT } } }
      withContext(Dispatchers.Main) {
        vm.sendToNoteLink(first, { true }, { throw IllegalStateException("Discovery test failure") }) { _, _, _ -> error("Must not send") }
      }
      val failedSend = withTimeout(10_000) { vm.state.first { first in it.errors } }
      assertEquals(ExportProgress.Stage.DISCOVERING, failedSend.errors.getValue(first).stage)
      assertEquals(ExportTaskState.CURRENT, failedSend.tasks.first { it.task.id == first }.state)
    } finally {
      gate.complete(true)
      withContext(Dispatchers.Main) { store.clear() }
      val tasks = ExportTaskRepository(app)
      tasks.listSummaries().filter { it.task.notebookId in ids }.forEach { summary ->
        tasks.deleteTask(summary.task.id).forEach(File::delete)
      }
      ids.forEach(notebooks::deleteNotebook)
      tasks.close()
      notebooks.close()
    }
  }
}
