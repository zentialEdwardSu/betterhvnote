package com.betterhv.note.export

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.betterhv.note.noteText
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.template.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ExportManagerState(
  val tasks: List<ExportTaskSummary> = emptyList(),
  val notebooks: List<ExportNotebookOption> = emptyList(),
  val activeTaskId: UUID? = null,
  val progress: ExportProgress? = null,
  val message: String? = null,
)

class ExportViewModel(application: Application) : AndroidViewModel(application) {
  private val taskRepository = ExportTaskRepository(application)
  private val notebookRepository = NotebookRepository(application)
  private val engine = ExportEngine(
    notebookRepository,
    taskRepository,
    PageRenderer(File(application.filesDir, "documents"), TemplateStore.get(application)),
    File(application.filesDir, "exports"),
  )
  private val downloads = ExportDownloads(application)
  private val mutableState = MutableStateFlow(ExportManagerState())
  val state: StateFlow<ExportManagerState> = mutableState.asStateFlow()
  private var activeJob: Job? = null

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch(Dispatchers.IO) {
      engine.pruneOrphanFiles()
      val tasks = taskRepository.listSummaries()
      val notebooks = taskRepository.listNotebookOptions()
      withContext(Dispatchers.Main) {
        mutableState.value = mutableState.value.copy(tasks = tasks, notebooks = notebooks)
      }
    }
  }

  fun createTask(notebookId: UUID, scope: ExportScope, format: ExportFormat, pageIds: List<UUID>) {
    viewModelScope.launch(Dispatchers.IO) {
      runCatching { taskRepository.createTask(notebookId, scope, format, pageIds) }
        .onSuccess {
          val tasks = taskRepository.listSummaries()
          withContext(Dispatchers.Main) {
            mutableState.value = mutableState.value.copy(
              tasks = tasks,
              message = noteText("导出任务已创建", "Export task created"),
            )
          }
        }
        .onFailure { failure(it.message ?: noteText("创建导出任务失败", "Could not create export task")) }
    }
  }

  fun deleteTask(id: UUID) {
    if (mutableState.value.activeTaskId == id) return
    viewModelScope.launch(Dispatchers.IO) {
      engine.deleteTask(id)
      val tasks = taskRepository.listSummaries()
      withContext(Dispatchers.Main) {
        mutableState.value = mutableState.value.copy(
          tasks = tasks,
          message = noteText("导出任务已删除", "Export task deleted"),
        )
      }
    }
  }

  fun saveToDownloads(taskId: UUID, beforeExport: suspend (UUID) -> Boolean) {
    runTask(taskId, beforeExport) { artifact ->
      withContext(Dispatchers.IO) { downloads.save(artifact) }
      noteText("已保存到 Downloads/BetterHvNote", "Saved to Downloads/BetterHvNote")
    }
  }

  fun sendToNoteLink(
    taskId: UUID,
    beforeExport: suspend (UUID) -> Boolean,
    sender: suspend (ExportArtifact, (Long, Long) -> Unit) -> Unit,
  ) {
    runTask(taskId, beforeExport) { artifact ->
      mutableState.value = mutableState.value.copy(
        progress = ExportProgress(
          0,
          artifact.byteLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
          null,
          ExportProgress.Stage.SENDING,
        ),
      )
      sender(artifact) { sent, total ->
        mutableState.value = mutableState.value.copy(
          progress = ExportProgress(
            sent.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            null,
            ExportProgress.Stage.SENDING,
          ),
        )
      }
      noteText("已发送到 NoteLink", "Sent to NoteLink")
    }
  }

  fun cancel() {
    activeJob?.cancel()
  }

  fun consumeMessage() {
    mutableState.value = mutableState.value.copy(message = null)
  }

  private fun runTask(
    taskId: UUID,
    beforeExport: suspend (UUID) -> Boolean,
    destination: suspend (ExportArtifact) -> String,
  ) {
    if (activeJob?.isActive == true) return
    val task = mutableState.value.tasks.firstOrNull { it.task.id == taskId }?.task ?: return
    activeJob = viewModelScope.launch {
      mutableState.value = mutableState.value.copy(
        activeTaskId = taskId,
        progress = ExportProgress(0, 1, null, ExportProgress.Stage.PREPARING),
        message = null,
      )
      try {
        check(beforeExport(task.notebookId)) { noteText("保存当前笔记本失败", "Could not save the current notebook") }
        when (
          val result = engine.generate(taskId) { progress ->
            mutableState.value = mutableState.value.copy(progress = progress)
          }
        ) {
          is ExportResult.Success -> {
            val message = destination(result.artifact)
            mutableState.value = mutableState.value.copy(message = message)
          }

          is ExportResult.Failure -> mutableState.value = mutableState.value.copy(
            message = noteText("导出失败：${result.error}", "Export failed: ${result.error}"),
          )

          ExportResult.Cancelled -> mutableState.value = mutableState.value.copy(
            message = noteText("已取消导出", "Export cancelled"),
          )
        }
      } catch (_: kotlinx.coroutines.CancellationException) {
        mutableState.value = mutableState.value.copy(message = noteText("已取消导出", "Export cancelled"))
      } catch (t: Throwable) {
        mutableState.value = mutableState.value.copy(
          message = noteText(
            "导出失败：${t.message ?: t.javaClass.simpleName}",
            "Export failed: ${t.message ?: t.javaClass.simpleName}",
          ),
        )
      } finally {
        mutableState.value = mutableState.value.copy(activeTaskId = null, progress = null)
        refresh()
      }
    }
  }

  private suspend fun failure(message: String) = withContext(Dispatchers.Main) {
    mutableState.value = mutableState.value.copy(message = message)
  }

  override fun onCleared() {
    taskRepository.close()
    notebookRepository.close()
  }
}
