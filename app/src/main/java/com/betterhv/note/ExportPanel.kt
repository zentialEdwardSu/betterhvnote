package com.betterhv.note

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.betterhv.note.export.ExportActionAvailability
import com.betterhv.note.export.ExportArtifact
import com.betterhv.note.export.ExportFormat
import com.betterhv.note.export.ExportManagerState
import com.betterhv.note.export.ExportNotebookOption
import com.betterhv.note.export.ExportProgress
import com.betterhv.note.export.ExportRenderMode
import com.betterhv.note.export.ExportRunReport
import com.betterhv.note.export.ExportScope
import com.betterhv.note.export.ExportTaskState
import com.betterhv.note.export.ExportTaskSummary
import com.betterhv.note.export.ExportViewModel
import com.betterhv.note.export.determinateFraction
import com.betterhv.note.export.formatDuration
import com.betterhv.note.export.remainingMs
import com.betterhv.note.ui.EinkActionRow
import com.betterhv.note.ui.EinkChoiceCell
import com.betterhv.note.ui.EinkColors
import com.betterhv.note.ui.EinkDimens
import com.betterhv.note.ui.EinkIconButton
import com.betterhv.note.ui.EinkProgress
import com.betterhv.note.ui.EinkSurface
import com.betterhv.note.ui.EinkTheme
import com.betterhv.note.ui.EinkType
import com.betterhv.transfer.core.PairedDevice
import java.text.DateFormat
import java.util.Date
import java.util.UUID

data class ExportManagerInput(
  val viewModel: ExportViewModel,
  val initialNotebookId: UUID,
  val creationRequest: Long,
  val currentNotebookId: UUID,
  val pairedClients: List<PairedDevice>,
  val callbacks: ExportManagerCallbacks,
)

data class ExportManagerCallbacks(
  val pageBitmap: (UUID) -> Bitmap?,
  val requestThumbnails: (List<UUID>) -> Unit,
  val beforeExport: suspend (UUID) -> Boolean,
  val discoverClients: suspend () -> List<PairedDevice>,
  val sendToNoteLink: suspend (String, ExportArtifact, (Long, Long) -> Unit) -> Unit,
  val onNotice: (String) -> Unit,
  val onClose: () -> Unit,
)

data class ExportTaskCreatorCallbacks(
  val pageBitmap: (UUID) -> Bitmap?,
  val requestThumbnails: (List<UUID>) -> Unit,
  val onCreate: (UUID, ExportScope, ExportFormat, List<UUID>, ExportRenderMode) -> Unit,
  val onBack: () -> Unit,
)

data class ExportTaskRowState(
  val summary: ExportTaskSummary,
  val active: Boolean,
  val busy: Boolean,
  val phoneTransferAvailable: Boolean,
  val progress: ExportProgress?,
  val queued: Boolean = false,
  val cancelling: Boolean = false,
  val outcome: String? = null,
  val errorDetails: String? = null,
)

data class ExportTaskRowActions(
  val onSave: () -> Unit, val onSend: () -> Unit, val onDelete: () -> Unit,
  val onGenerate: () -> Unit, val onCancel: () -> Unit,
)

data class ExportManagerSurfaceState(
  val exportState: ExportManagerState,
  val viewModel: ExportViewModel,
  val initialNotebookId: UUID,
  val currentNotebookId: UUID,
  val pairedClients: List<PairedDevice>,
  val callbacks: ExportManagerCallbacks,
  val creating: Boolean,
)

data class ExportManagerSurfaceActions(
  val onCreatingChange: (Boolean) -> Unit,
  val onDeleteTask: (ExportTaskSummary) -> Unit,
  val onSendTask: (ExportTaskSummary) -> Unit,
)

data class ExportTaskListInput(
  val exportState: ExportManagerState,
  val viewModel: ExportViewModel,
  val pairedClients: List<PairedDevice>,
  val callbacks: ExportManagerCallbacks,
)

data class ExportTaskListActions(
  val onDeleteTask: (ExportTaskSummary) -> Unit,
  val onSendTask: (ExportTaskSummary) -> Unit,
)

@Composable
fun ExportManagerScreen(input: ExportManagerInput) {
  EinkTheme {
    ExportManagerScreenContent(input)
  }
}

@Composable
private fun ExportManagerScreenContent(input: ExportManagerInput) {
  val viewModel = input.viewModel
  val initialNotebookId = input.initialNotebookId
  val creationRequest = input.creationRequest
  val currentNotebookId = input.currentNotebookId
  val pairedClients = input.pairedClients
  val callbacks = input.callbacks
  val state by viewModel.state.collectAsState()
  var creating by remember { mutableStateOf(false) }
  var deleteTask by remember { mutableStateOf<ExportTaskSummary?>(null) }

  LaunchedEffect(creationRequest) {
    if (creationRequest > 0) creating = true
  }
  LaunchedEffect(state.message) {
    state.message?.let {
      callbacks.onNotice(it)
      viewModel.consumeMessage()
    }
  }

  ExportManagerSurface(
    state = ExportManagerSurfaceState(
      exportState = state,
      viewModel = viewModel,
      initialNotebookId = initialNotebookId,
      currentNotebookId = currentNotebookId,
      pairedClients = pairedClients,
      callbacks = callbacks,
      creating = creating,
    ),
    actions = ExportManagerSurfaceActions(
      onCreatingChange = { creating = it },
      onDeleteTask = { deleteTask = it },
      onSendTask = { summary ->
        viewModel.sendToNoteLink(
          summary.task.id, callbacks.beforeExport, callbacks.discoverClients, callbacks.sendToNoteLink,
        )
      },
    ),
  )

  deleteTask?.let { summary ->
    ExportDeleteTaskDialog(
      onDismiss = { deleteTask = null },
      onDelete = {
        viewModel.deleteTask(summary.task.id)
        deleteTask = null
      },
    )
  }

  state.choosingClients?.let { clients ->
    ExportSendTaskDialog(
      clients = clients,
      onDismiss = { viewModel.cancel() },
      onSend = viewModel::chooseClient,
    )
  }
}

@Composable
private fun ExportManagerSurface(state: ExportManagerSurfaceState, actions: ExportManagerSurfaceActions) {
  val exportState = state.exportState
  val viewModel = state.viewModel
  val callbacks = state.callbacks
  Surface(Modifier.fillMaxSize(), color = EinkColors.Paper, shape = RectangleShape) {
    if (state.creating) {
      ExportTaskCreator(
        state = exportState,
        initialNotebookId = state.initialNotebookId,
        currentNotebookId = state.currentNotebookId,
        callbacks = ExportTaskCreatorCallbacks(
          pageBitmap = callbacks.pageBitmap,
          requestThumbnails = callbacks.requestThumbnails,
          onCreate = { notebookId, scope, format, ids, renderMode ->
            viewModel.createTask(notebookId, scope, format, ids, renderMode, callbacks.beforeExport)
            actions.onCreatingChange(false)
          },
          onBack = { actions.onCreatingChange(false) },
        ),
      )
    } else {
      Column(Modifier.fillMaxSize()) {
        ExportTopBar(
          taskCount = exportState.tasks.size,
          onBack = callbacks.onClose,
          onAdd = { actions.onCreatingChange(true) },
        )
        HorizontalDivider(color = EinkColors.Ink)
        ExportTaskList(
          input = ExportTaskListInput(
            exportState = exportState,
            viewModel = viewModel,
            pairedClients = state.pairedClients,
            callbacks = callbacks,
          ),
          actions = ExportTaskListActions(
            onDeleteTask = actions.onDeleteTask,
            onSendTask = actions.onSendTask,
          ),
        )
      }
    }
  }
}

@Composable
private fun ExportTaskList(input: ExportTaskListInput, actions: ExportTaskListActions) {
  val exportState = input.exportState
  if (exportState.tasks.isEmpty()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(noteText("暂无导出任务", "No export tasks"), color = EinkColors.Muted, style = EinkType.Body)
    }
    return
  }
  LazyColumn(Modifier.fillMaxSize()) {
    items(exportState.tasks, key = { it.task.id }) { summary ->
      ExportTaskRow(
        state = ExportTaskRowState(
          summary = summary,
          active = exportState.activeTaskId == summary.task.id,
          busy = exportState.activeTaskId == summary.task.id || summary.task.id in exportState.queuedTaskIds,
          phoneTransferAvailable = input.pairedClients.isNotEmpty(),
          progress = exportState.progress,
          queued = summary.task.id in exportState.queuedTaskIds,
          cancelling = summary.task.id in exportState.cancellingTaskIds,
          outcome = exportState.outcomes[summary.task.id],
          errorDetails = exportState.errors[summary.task.id]?.details,
        ),
        actions = ExportTaskRowActions(
          onSave = {
            input.viewModel.saveToDownloads(summary.task.id, input.callbacks.beforeExport)
          },
          onSend = { actions.onSendTask(summary) },
          onDelete = { actions.onDeleteTask(summary) },
          onGenerate = { input.viewModel.generate(summary.task.id, input.callbacks.beforeExport) },
          onCancel = { input.viewModel.cancel(summary.task.id) },
        ),
      )
      HorizontalDivider(color = EinkColors.Ink)
    }
  }
}

@Composable
private fun ExportDeleteTaskDialog(onDismiss: () -> Unit, onDelete: () -> Unit) {
  EinkModalOverlay(
    onDismissRequest = onDismiss,
    borderWidth = EinkDimens.Hairline,
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(noteText("删除导出任务？", "Delete export task?"), style = EinkType.SectionTitle)
      Text(
        noteText(
          "内部生成文件和增量缓存将被删除，Downloads 与 NoteLink 中的副本不受影响。",
          "Generated files and incremental caches will be deleted. Copies in Downloads and NoteLink are unaffected.",
        ),
      )
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
      ) {
        EinkDialogAction(noteText("取消", "Cancel"), onClick = onDismiss)
        EinkDialogAction(noteText("删除", "Delete"), onClick = onDelete)
      }
    }
  }
}

@Composable
private fun ExportSendTaskDialog(clients: List<PairedDevice>, onDismiss: () -> Unit, onSend: (PairedDevice) -> Unit) {
  EinkModalOverlay(
    onDismissRequest = onDismiss,
    borderWidth = EinkDimens.Hairline,
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(noteText("发送到 NoteLink", "Send to NoteLink"), style = EinkType.SectionTitle)
      clients.sortedByDescending(PairedDevice::lastUsedAt).forEach { client ->
        EinkDialogAction(client.name, onClick = { onSend(client) })
      }
      EinkDialogAction(noteText("取消", "Cancel"), onClick = onDismiss)
    }
  }
}

@Composable
private fun ExportTopBar(taskCount: Int, onBack: () -> Unit, onAdd: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().height(EinkDimens.ScreenHeader).padding(horizontal = EinkDimens.SpaceLg),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    EinkIconButton(
      icon = Icons.AutoMirrored.Filled.ArrowBack,
      contentDescription = noteText("返回", "Back"),
      onClick = onBack,
    )
    Text(noteText("导出", "Export"), style = EinkType.SectionTitle)
    Text(
      noteText("$taskCount 项", "$taskCount tasks"),
      color = EinkColors.Muted,
      style = EinkType.Caption,
      modifier = Modifier.padding(start = EinkDimens.SpaceSm),
    )
    Spacer(Modifier.weight(1f))
    EinkIconButton(
      icon = Icons.Filled.Add,
      contentDescription = noteText("新建导出任务", "New export task"),
      onClick = onAdd,
    )
  }
}

@Composable
private fun ExportTaskRow(state: ExportTaskRowState, actions: ExportTaskRowActions) {
  val summary = state.summary
  val active = state.active
  var detailsOpen by remember { mutableStateOf(false) }
  val availability = ExportActionAvailability.resolve(
    summary.state,
    state.busy,
    state.phoneTransferAvailable,
  )
  Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          taskTitle(summary),
          style = EinkType.Body,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          taskMetaLine(summary),
          color = EinkColors.Muted,
          style = EinkType.Caption,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val statusText = when {
          state.cancelling -> noteText("正在取消…", "Cancelling…")
          state.queued -> noteText("排队中", "Queued")
          active -> state.progress?.let { progressLabel(it) + progressTiming(it) }
            ?: noteText("准备中", "Preparing")
          else -> state.outcome ?: taskStatus(summary)
        }
        val statusMarker = when {
          state.errorDetails != null || summary.state == ExportTaskState.FAILED ||
            summary.state == ExportTaskState.SOURCE_MISSING -> "! "
          summary.state == ExportTaskState.CURRENT -> "✓ "
          else -> ""
        }
        Text(
          statusMarker + statusText,
          color = if (statusMarker == "! ") EinkColors.Ink else EinkColors.Muted,
          style = EinkType.Caption,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
        summary.lastRun?.let { lastRun ->
          Text(
            noteText(
              "上次导出 ${formatDuration(lastRun.totalMs)} · ${modeLabel(lastRun.mode)}",
              "Last export ${formatDuration(lastRun.totalMs)} · ${modeLabel(lastRun.mode)}",
            ),
            color = EinkColors.Muted,
            style = EinkType.Caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      if (active) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = EinkColors.Ink)
        Spacer(Modifier.width(EinkDimens.SpaceXs))
      }
      if (state.errorDetails != null || summary.lastRun != null) {
        EinkIconButton(
          icon = Icons.Filled.Info,
          contentDescription = noteText("导出详情", "Export details"),
          onClick = { detailsOpen = true },
        )
      }
      if (state.busy) {
        EinkIconButton(
          icon = Icons.Filled.Close,
          contentDescription = noteText("取消", "Cancel"),
          onClick = actions.onCancel,
        )
      } else {
        EinkIconButton(
          icon = Icons.Filled.Refresh,
          contentDescription = noteText("生成或重试", "Generate or retry"),
          enabled = availability.saveEnabled,
          onClick = actions.onGenerate,
        )
      }
      EinkIconButton(
        icon = Icons.Filled.Download,
        contentDescription = noteText(
          "保存 ${summary.notebookTitle} 到 Downloads",
          "Save ${summary.notebookTitle} to Downloads",
        ),
        enabled = availability.saveEnabled,
        onClick = actions.onSave,
      )
      EinkIconButton(
        icon = Icons.AutoMirrored.Filled.Send,
        contentDescription = noteText(
          "发送 ${summary.notebookTitle} 到 NoteLink",
          "Send ${summary.notebookTitle} to NoteLink",
        ),
        enabled = availability.sendEnabled,
        onClick = actions.onSend,
      )
      EinkIconButton(
        icon = Icons.Filled.Delete,
        contentDescription = noteText("删除导出任务", "Delete export task"),
        enabled = availability.deleteEnabled,
        onClick = actions.onDelete,
      )
    }
    if (active && state.progress != null) {
      EinkProgress(
        progress = state.progress.determinateFraction(),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
      )
    }
  }
  if (detailsOpen) {
    EinkModalOverlay(onDismissRequest = { detailsOpen = false }) {
      Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
        Text(noteText("导出详情", "Export details"), style = EinkType.SectionTitle)
        summary.lastRun?.let { report ->
          Spacer(Modifier.height(EinkDimens.SpaceSm))
          Text(runReportText(report), style = EinkType.Caption)
        }
        state.errorDetails?.let { details ->
          Spacer(Modifier.height(EinkDimens.SpaceSm))
          HorizontalDivider(color = EinkColors.Muted)
          androidx.compose.foundation.text.selection.SelectionContainer {
            Text(details, style = EinkType.Caption)
          }
        }
        Spacer(Modifier.height(EinkDimens.SpaceMd))
        EinkDialogAction(noteText("关闭", "Close"), onClick = { detailsOpen = false })
      }
    }
  }
}

private fun taskMetaLine(summary: ExportTaskSummary): String {
  val mode = if (summary.task.format == ExportFormat.PDF) {
    " · ${modeLabel(summary.task.renderMode)}"
  } else {
    ""
  }
  return noteText(
    "${summary.notebookTitle} · ${summary.pageCount} 页 · ${summary.task.format.name}$mode",
    "${summary.notebookTitle} · ${summary.pageCount} pages · ${summary.task.format.name}$mode",
  )
}

private fun modeLabel(mode: ExportRenderMode): String = when (mode) {
  ExportRenderMode.VECTOR -> noteText("可编辑笔迹", "Editable ink")
  ExportRenderMode.FAST -> noteText("快速模式", "Fast mode")
}

private fun progressTiming(progress: ExportProgress): String {
  val remaining = progress.remainingMs()
  return if (remaining != null) {
    noteText(
      " · 已用 ${formatDuration(progress.elapsedMs)}，预计剩余 ${formatDuration(remaining)}",
      " · ${formatDuration(progress.elapsedMs)} elapsed, ~${formatDuration(remaining)} left",
    )
  } else {
    noteText(
      " · 已用 ${formatDuration(progress.elapsedMs)}",
      " · ${formatDuration(progress.elapsedMs)} elapsed",
    )
  }
}

private fun runReportText(report: ExportRunReport): String = buildString {
  appendLine(noteText("模式：${modeLabel(report.mode)}", "Mode: ${modeLabel(report.mode)}"))
  appendLine(noteText("结果：${runResultLabel(report.result)}", "Result: ${runResultLabel(report.result)}"))
  appendLine(noteText("总耗时：${formatDuration(report.totalMs)}", "Total: ${formatDuration(report.totalMs)}"))
  appendLine(
    noteText(
      "页面生成：${formatDuration(report.renderMs)}",
      "Page render: ${formatDuration(report.renderMs)}",
    ),
  )
  appendLine(
    noteText(
      "合并 graft：${formatDuration(report.mergeGraftMs)}",
      "Merge graft: ${formatDuration(report.mergeGraftMs)}",
    ),
  )
  appendLine(
    noteText(
      "最终写入：${formatDuration(report.mergeSaveMs)}",
      "Final write: ${formatDuration(report.mergeSaveMs)}",
    ),
  )
  appendLine(
    noteText(
      "校验：${formatDuration(report.verifyMs)} · 收尾：${formatDuration(report.finalizeMs)}",
      "Verify: ${formatDuration(report.verifyMs)} · Finalize: ${formatDuration(report.finalizeMs)}",
    ),
  )
  appendLine(
    noteText(
      "页数：${report.pageCount}（缓存命中 ${report.cacheHits} / 未命中 ${report.cacheMisses}）",
      "Pages: ${report.pageCount} (cache hits ${report.cacheHits} / misses ${report.cacheMisses})",
    ),
  )
  appendLine(
    noteText(
      "输出大小：${formatBytes(report.outputBytes)} · 栅格缩放：${"%.2f".format(report.rasterScale)}×",
      "Output: ${formatBytes(report.outputBytes)} · raster scale: ${"%.2f".format(report.rasterScale)}×",
    ),
  )
  report.failedStage?.let { appendLine(noteText("阶段：$it", "Stage: $it")) }
}

private fun runResultLabel(result: com.betterhv.note.export.ExportRunResult): String = when (result) {
  com.betterhv.note.export.ExportRunResult.SUCCESS -> noteText("成功", "Succeeded")
  com.betterhv.note.export.ExportRunResult.FAILED -> noteText("失败", "Failed")
  com.betterhv.note.export.ExportRunResult.CANCELLED -> noteText("已取消", "Cancelled")
}

private fun formatBytes(bytes: Long): String = when {
  bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
  bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
  else -> "$bytes B"
}

private fun progressLabel(progress: ExportProgress): String {
  val elapsed = formatDuration(progress.elapsedMs)
  return when (progress.stage) {
    ExportProgress.Stage.PREPARING -> noteText("准备中", "Preparing")
    ExportProgress.Stage.RENDERING -> noteText(
      "生成第 ${progress.current}/${progress.total} 页",
      "Rendering ${progress.current}/${progress.total}",
    )
    ExportProgress.Stage.ASSEMBLING -> noteText(
      "合并第 ${progress.current}/${progress.total} 页",
      "Merging ${progress.current}/${progress.total}",
    )
    ExportProgress.Stage.WRITING -> noteText("正在写入 PDF · $elapsed", "Writing PDF · $elapsed")
    ExportProgress.Stage.VERIFYING -> noteText("正在校验文件 · $elapsed", "Verifying file · $elapsed")
    ExportProgress.Stage.PUBLISHING -> noteText("正在保存 · $elapsed", "Saving · $elapsed")
    ExportProgress.Stage.DISCOVERING -> noteText("发现设备中", "Discovering devices")
    ExportProgress.Stage.SENDING -> noteText(
      "发送中 ${progress.current}/${progress.total}", "Sending ${progress.current}/${progress.total}",
    )
    ExportProgress.Stage.CANCELLING -> noteText("正在取消…", "Cancelling…")
  }
}

@Composable
private fun ExportTaskCreator(
  state: ExportManagerState,
  initialNotebookId: UUID,
  currentNotebookId: UUID,
  callbacks: ExportTaskCreatorCallbacks,
) {
  var notebookId by remember(state.notebooks, initialNotebookId) {
    mutableStateOf(state.notebooks.firstOrNull { it.id == initialNotebookId }?.id ?: state.notebooks.firstOrNull()?.id)
  }
  var scope by remember { mutableStateOf(ExportScope.SINGLE_PAGE) }
  var format by remember { mutableStateOf(ExportFormat.PDF) }
  var renderMode by remember { mutableStateOf(ExportRenderMode.VECTOR) }
  var selected by remember(notebookId, scope) { mutableStateOf(emptySet<UUID>()) }
  var notebookMenu by remember { mutableStateOf(false) }
  var notebookMenuWidth by remember { mutableIntStateOf(0) }
  val notebook = state.notebooks.firstOrNull { it.id == notebookId }
  val pages = notebook?.pages.orEmpty().mapIndexed { index, source ->
    PageUiInfo(source.id, index + 1, false, source.contentRevision)
  }

  LaunchedEffect(notebookId, scope) {
    if (notebookId == currentNotebookId && scope != ExportScope.ALL_PAGES) {
      callbacks.requestThumbnails(pages.map(PageUiInfo::id))
    }
  }

  Column(Modifier.fillMaxSize()) {
    Row(
      Modifier.fillMaxWidth().height(EinkDimens.ScreenHeader).padding(horizontal = EinkDimens.SpaceLg),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      EinkIconButton(
        icon = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = noteText("返回", "Back"),
        onClick = callbacks.onBack,
      )
      Text(noteText("新建导出任务", "New export task"), style = EinkType.SectionTitle)
      Spacer(Modifier.weight(1f))
      val valid = notebookId != null && (scope == ExportScope.ALL_PAGES || selected.isNotEmpty())
      EinkIconButton(
        icon = Icons.Filled.Check,
        contentDescription = noteText("创建", "Create"),
        enabled = valid,
        onClick = {
          notebookId?.let {
            callbacks.onCreate(
              it,
              scope,
              format,
              selected.toList(),
              if (format == ExportFormat.PDF) renderMode else ExportRenderMode.VECTOR,
            )
          }
        },
      )
    }
    HorizontalDivider(color = EinkColors.Ink)
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Box {
        Row(
          Modifier.fillMaxWidth().onSizeChanged { notebookMenuWidth = it.width }
            .border(EinkDimens.Hairline, EinkColors.Ink).clickable { notebookMenu = true }
            .padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(notebook?.title ?: noteText("选择笔记本", "Select notebook"), modifier = Modifier.weight(1f))
          Icon(Icons.Filled.ExpandMore, null)
        }
        NotebookPickerPopup(
          expanded = notebookMenu,
          widthPx = notebookMenuWidth,
          options = state.notebooks,
          onSelect = { option ->
            notebookId = option.id;
            notebookMenu = false
          },
          onDismiss = { notebookMenu = false },
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScopeButton(noteText("单页", "Single page"), scope == ExportScope.SINGLE_PAGE) { scope = ExportScope.SINGLE_PAGE }
        ScopeButton(
          noteText("选择页面", "Selected pages"),
          scope == ExportScope.SELECTED_PAGES,
        ) { scope = ExportScope.SELECTED_PAGES }
        ScopeButton(
          noteText("全部页面", "All pages"),
          scope == ExportScope.ALL_PAGES,
        ) {
          scope = ExportScope.ALL_PAGES;
          format = ExportFormat.PDF
        }
      }
      if (scope == ExportScope.SINGLE_PAGE) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ScopeButton("PDF", format == ExportFormat.PDF) { format = ExportFormat.PDF }
          ScopeButton("PNG", format == ExportFormat.PNG) { format = ExportFormat.PNG }
        }
      }
      if (format == ExportFormat.PDF) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ScopeButton(
            noteText("可编辑笔迹", "Editable ink"),
            renderMode == ExportRenderMode.VECTOR,
          ) { renderMode = ExportRenderMode.VECTOR }
          ScopeButton(
            noteText("快速模式", "Fast mode"),
            renderMode == ExportRenderMode.FAST,
          ) { renderMode = ExportRenderMode.FAST }
        }
        if (renderMode == ExportRenderMode.FAST) {
          Text(
            noteText(
              "每页笔迹合成为整页位图，导出更快，但 PDF 中不能逐笔编辑。",
              "Each page is rasterized into one bitmap: faster export, but ink is not editable per stroke in the PDF.",
            ),
            color = EinkColors.Muted,
            style = EinkType.Caption,
          )
        }
      }
    }
    if (scope == ExportScope.ALL_PAGES) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          noteText(
            "将持续跟随 ${pages.size} 个页面，并增量更新变化内容",
            "Tracks ${pages.size} pages and incrementally updates changes",
          ),
          color = EinkColors.Muted,
          style = EinkType.Body,
        )
      }
    } else {
      PageSelectionGrid(
        pages = pages,
        selectedIds = selected,
        pageBitmap = { id -> if (notebookId == currentNotebookId) callbacks.pageBitmap(id) else null },
        onToggle = { id ->
          selected = if (scope == ExportScope.SINGLE_PAGE) {
            setOf(id)
          } else if (id in selected) {
            selected - id
          } else {
            selected + id
          }
        },
      )
    }
  }
}

@Composable
private fun NotebookPickerPopup(
  expanded: Boolean,
  widthPx: Int,
  options: List<ExportNotebookOption>,
  onSelect: (ExportNotebookOption) -> Unit,
  onDismiss: () -> Unit,
) {
  if (!expanded || widthPx <= 0) return
  val density = LocalDensity.current
  val offset = with(density) { IntOffset(0, 52.dp.roundToPx()) }
  val width = with(density) { widthPx.toDp() }
  Popup(
    alignment = Alignment.TopStart,
    offset = offset,
    onDismissRequest = onDismiss,
    properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
  ) {
    EinkTheme {
      EinkSurface(
        modifier = Modifier.width(width).heightIn(max = 360.dp),
        color = EinkColors.Paper,
      ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          options.forEach { option ->
            EinkActionRow(
              label = option.title,
              onClick = { onSelect(option) },
            )
            HorizontalDivider(color = EinkColors.Muted)
          }
        }
      }
    }
  }
}

@Composable
private fun ScopeButton(text: String, selected: Boolean, onClick: () -> Unit) {
  EinkChoiceCell(
    selected = selected,
    contentDescription = text,
    onClick = onClick,
    modifier = Modifier.heightIn(min = EinkDimens.Row),
  ) {
    Text(
      text,
      modifier = Modifier.padding(horizontal = EinkDimens.SpaceLg, vertical = EinkDimens.SpaceSm),
      style = EinkType.Action,
    )
  }
}

private fun taskTitle(summary: ExportTaskSummary): String = when (summary.task.scope) {
  ExportScope.SINGLE_PAGE -> noteText("单页导出", "Single-page export")
  ExportScope.SELECTED_PAGES -> noteText("选定页面", "Selected pages")
  ExportScope.ALL_PAGES -> noteText("全部页面", "All pages")
}

private fun taskStatus(summary: ExportTaskSummary): String = when (summary.state) {
  ExportTaskState.NEVER_GENERATED -> noteText("从未生成", "Never generated")

  ExportTaskState.CURRENT -> summary.lastGeneratedAt?.let {
    noteText(
      "最新",
      "Current",
    ) + " · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}"
  } ?: noteText("最新", "Current")

  ExportTaskState.OUTDATED -> if (summary.stalePageCount == 0) {
    noteText("页面集合或顺序待更新", "Page set or order changed")
  } else {
    noteText("${summary.stalePageCount} 页待更新", "${summary.stalePageCount} pages need updating")
  }

  ExportTaskState.SOURCE_MISSING -> "Source page deleted"

  ExportTaskState.FAILED -> summary.lastError?.let { "Failed - $it" }
    ?: "Generation failed"
}


