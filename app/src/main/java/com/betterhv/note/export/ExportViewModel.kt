package com.betterhv.note.export

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.betterhv.note.noteText
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.template.TemplateStore
import com.betterhv.transfer.core.PairedDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ExportManagerState(
  val tasks: List<ExportTaskSummary> = emptyList(),
  val notebooks: List<ExportNotebookOption> = emptyList(),
  val activeTaskId: UUID? = null,
  val queuedTaskIds: Set<UUID> = emptySet(),
  val progress: ExportProgress? = null,
  val message: String? = null,
  val outcomes: Map<UUID, String> = emptyMap(),
  val errors: Map<UUID, ExportDiagnostic> = emptyMap(),
  val choosingClients: List<PairedDevice>? = null,
)

class ExportViewModel(application: Application) : AndroidViewModel(application) {
  private val taskRepository = ExportTaskRepository(application)
  private val notebookRepository = NotebookRepository(application)
  private val engine = ExportEngine(
    notebookRepository, taskRepository,
    PageRenderer(File(application.filesDir, "documents"), TemplateStore.get(application)),
    File(application.filesDir, "exports"),
  )
  private val downloads = ExportDownloads(application)
  private val diagnosticsDirectory = File(application.filesDir, "export-diagnostics")
  private val mutableState = MutableStateFlow(ExportManagerState())
  val state = mutableState.asStateFlow()
  private val execution = Mutex()
  private val jobs = LinkedHashMap<UUID, Job>()
  private var clientChoice: CompletableDeferred<PairedDevice>? = null

  init { refresh() }

  fun refresh() {
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) { engine.pruneOrphanFiles() }
        reload()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        mutableState.update { it.copy(message = "Could not refresh exports: ${error.message}") }
      }
    }
  }

  private suspend fun reload() {
    val (tasks, notebooks) = withContext(Dispatchers.IO) {
      taskRepository.listSummaries() to taskRepository.listNotebookOptions()
    }
    mutableState.update { it.copy(tasks = tasks, notebooks = notebooks) }
  }

  fun createTask(
    notebookId: UUID, scope: ExportScope, format: ExportFormat, pageIds: List<UUID>,
    beforeExport: suspend (UUID) -> Boolean,
  ) {
    viewModelScope.launch {
      try {
        val task = withContext(Dispatchers.IO) { taskRepository.createTask(notebookId, scope, format, pageIds) }
        reload()
        generate(task.id, beforeExport)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        mutableState.update { it.copy(message = "Could not create export task: ${error.message}") }
      }
    }
  }

  fun generate(taskId: UUID, beforeExport: suspend (UUID) -> Boolean) {
    enqueue(taskId) {
      generateArtifact(taskId, beforeExport)
      noteText("已生成", "Generated")
    }
  }

  fun deleteTask(id: UUID) {
    enqueue(id) {
      withContext(Dispatchers.IO) { engine.deleteTask(id) }
      mutableState.update { it.copy(outcomes = it.outcomes - id, errors = it.errors - id) }
      noteText("导出任务已删除", "Export task deleted")
    }
  }

  fun saveToDownloads(taskId: UUID, beforeExport: suspend (UUID) -> Boolean) {
    enqueue(taskId) {
      val artifact = generateArtifact(taskId, beforeExport)
      setStage(ExportProgress.Stage.SAVING)
      withContext(Dispatchers.IO) { downloads.save(artifact) }
      noteText("已保存到 Downloads/BetterHvNote", "Saved to Downloads/BetterHvNote")
    }
  }

  fun sendToNoteLink(
    taskId: UUID,
    beforeExport: suspend (UUID) -> Boolean,
    discover: suspend () -> List<PairedDevice>,
    sender: suspend (String, ExportArtifact, (Long, Long) -> Unit) -> Unit,
  ) {
    enqueue(taskId) {
      setStage(ExportProgress.Stage.DISCOVERING)
      val clients = discover()
      check(clients.isNotEmpty()) {
        noteText("未发现在线的已配对设备，请打开 NoteLink 后重试", "No paired device found. Open NoteLink and retry")
      }
      val client = if (clients.size == 1) clients.single() else {
        val choice = CompletableDeferred<PairedDevice>()
        clientChoice = choice
        mutableState.update { it.copy(choosingClients = clients) }
        try { choice.await() } finally {
          clientChoice = null
          mutableState.update { it.copy(choosingClients = null) }
        }
      }
      val artifact = generateArtifact(taskId, beforeExport)
      setStage(ExportProgress.Stage.SENDING)
      sender(client.id, artifact) { sent, total ->
        mutableState.update {
          it.copy(progress = ExportProgress(
            sent.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), null, ExportProgress.Stage.SENDING,
          ))
        }
      }
      noteText("已发送到 NoteLink", "Sent to NoteLink")
    }
  }

  fun chooseClient(client: PairedDevice) { clientChoice?.complete(client) }
  fun cancel(taskId: UUID? = mutableState.value.activeTaskId) { jobs[taskId]?.cancel() }
  fun consumeMessage() { mutableState.update { it.copy(message = null) } }

  private fun setStage(stage: ExportProgress.Stage) {
    mutableState.update { it.copy(progress = ExportProgress(0, 0, null, stage)) }
  }

  private suspend fun generateArtifact(taskId: UUID, beforeExport: suspend (UUID) -> Boolean): ExportArtifact {
    setStage(ExportProgress.Stage.PREPARING)
    val task = withContext(Dispatchers.IO) { taskRepository.loadTask(taskId) } ?: error("Export task does not exist")
    check(beforeExport(task.notebookId)) { "Could not save the current notebook" }
    val result = engine.generate(taskId) { progress -> mutableState.update { it.copy(progress = progress) } }
    return when (result) {
      is ExportResult.Success -> result.artifact
      is ExportResult.Failure -> throw GenerationFailure(result)
      ExportResult.Cancelled -> throw CancellationException("Export cancelled")
    }
  }

  private fun enqueue(taskId: UUID, operation: suspend () -> String) {
    if (taskId in jobs) return
    mutableState.update { it.copy(queuedTaskIds = it.queuedTaskIds + taskId) }
    val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
      try {
        execution.withLock {
          mutableState.update {
            it.copy(activeTaskId = taskId, queuedTaskIds = it.queuedTaskIds - taskId,
              progress = ExportProgress(0, 0, null, ExportProgress.Stage.PREPARING),
              errors = it.errors - taskId, outcomes = it.outcomes - taskId)
          }
          try {
            val message = operation()
            mutableState.update { it.copy(message = message, outcomes = it.outcomes + (taskId to message)) }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            val diagnostic = (error as? GenerationFailure)?.result?.diagnostic ?: withContext(Dispatchers.IO) {
              ExportDiagnostic.capture(taskId, mutableState.value.progress, error, diagnosticsDirectory)
            }
            mutableState.update {
              it.copy(message = diagnostic.summary, errors = it.errors + (taskId to diagnostic),
                outcomes = it.outcomes + (taskId to diagnostic.summary))
            }
          } finally {
            mutableState.update { it.copy(activeTaskId = null, progress = null) }
          }
        }
      } catch (_: CancellationException) {
        mutableState.update { it.copy(outcomes = it.outcomes + (taskId to noteText("已取消", "Cancelled"))) }
      } finally {
        jobs.remove(taskId)
        mutableState.update { it.copy(queuedTaskIds = it.queuedTaskIds - taskId) }
        withContext(NonCancellable) { runCatching { reload() } }
      }
    }
    jobs[taskId] = job
    job.start()
  }

  private class GenerationFailure(val result: ExportResult.Failure) : Exception(result.error)

  override fun onCleared() {
    val pending = viewModelScope.coroutineContext[Job]?.children?.toList().orEmpty()
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
      pending.forEach { it.cancel(); it.join() }
      taskRepository.close()
      notebookRepository.close()
    }
  }
}
