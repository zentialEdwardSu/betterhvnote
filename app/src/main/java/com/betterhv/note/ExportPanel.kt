package com.betterhv.note

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
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
import com.betterhv.note.export.ExportArtifact
import com.betterhv.note.export.ExportActionAvailability
import com.betterhv.note.export.ExportFormat
import com.betterhv.note.export.ExportManagerState
import com.betterhv.note.export.ExportNotebookOption
import com.betterhv.note.export.ExportProgress
import com.betterhv.note.export.ExportScope
import com.betterhv.note.export.ExportTaskState
import com.betterhv.note.export.ExportTaskSummary
import com.betterhv.note.export.ExportViewModel
import com.betterhv.transfer.core.PairedDevice
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private val ExportBorder = Color(0xFFE3E3E1)
private val ExportMuted = Color(0xFF787774)

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
    val sendToNoteLink: suspend (String, ExportArtifact, (Long, Long) -> Unit) -> Unit,
    val onNotice: (String) -> Unit,
    val onClose: () -> Unit,
)

data class ExportTaskCreatorCallbacks(
    val pageBitmap: (UUID) -> Bitmap?,
    val requestThumbnails: (List<UUID>) -> Unit,
    val onCreate: (UUID, ExportScope, ExportFormat, List<UUID>) -> Unit,
    val onBack: () -> Unit,
)

data class ExportTaskRowState(
    val summary: ExportTaskSummary,
    val active: Boolean,
    val busy: Boolean,
    val phoneTransferAvailable: Boolean,
    val progress: ExportProgress?,
)

data class ExportTaskRowActions(
    val onSave: () -> Unit,
    val onSend: () -> Unit,
    val onDelete: () -> Unit,
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
    val viewModel = input.viewModel
    val initialNotebookId = input.initialNotebookId
    val creationRequest = input.creationRequest
    val currentNotebookId = input.currentNotebookId
    val pairedClients = input.pairedClients
    val callbacks = input.callbacks
    val state by viewModel.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var deleteTask by remember { mutableStateOf<ExportTaskSummary?>(null) }
    var sendTask by remember { mutableStateOf<ExportTaskSummary?>(null) }

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
            onSendTask = { sendTask = it },
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

    sendTask?.let { summary ->
        ExportSendTaskDialog(
            clients = pairedClients,
            onDismiss = { sendTask = null },
            onSend = { client ->
                sendTask = null
                viewModel.sendToNoteLink(summary.task.id, callbacks.beforeExport) { artifact, progress ->
                    callbacks.sendToNoteLink(client.id, artifact, progress)
                }
            },
        )
    }
}

@Composable
private fun ExportManagerSurface(
    state: ExportManagerSurfaceState,
    actions: ExportManagerSurfaceActions,
) {
    val exportState = state.exportState
    val viewModel = state.viewModel
    val callbacks = state.callbacks
    Surface(Modifier.fillMaxSize(), color = Color.White, shape = RectangleShape) {
        if (state.creating) {
            ExportTaskCreator(
                state = exportState,
                initialNotebookId = state.initialNotebookId,
                currentNotebookId = state.currentNotebookId,
                callbacks = ExportTaskCreatorCallbacks(
                    pageBitmap = callbacks.pageBitmap,
                    requestThumbnails = callbacks.requestThumbnails,
                    onCreate = { notebookId, scope, format, ids ->
                        viewModel.createTask(notebookId, scope, format, ids)
                        actions.onCreatingChange(false)
                    },
                    onBack = { actions.onCreatingChange(false) },
                ),
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                ExportTopBar(
                    taskCount = exportState.tasks.size,
                    busy = exportState.activeTaskId != null,
                    onBack = callbacks.onClose,
                    onAdd = { actions.onCreatingChange(true) },
                )
                HorizontalDivider(color = ExportBorder)
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
            Text(noteText("暂无导出任务", "No export tasks"), color = ExportMuted)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(exportState.tasks, key = { it.task.id }) { summary ->
            ExportTaskRow(
                state = ExportTaskRowState(
                    summary = summary,
                    active = exportState.activeTaskId == summary.task.id,
                    busy = exportState.activeTaskId != null,
                    phoneTransferAvailable = input.pairedClients.isNotEmpty(),
                    progress = exportState.progress,
                ),
                actions = ExportTaskRowActions(
                    onSave = {
                        input.viewModel.saveToDownloads(summary.task.id, input.callbacks.beforeExport)
                    },
                    onSend = {
                        if (input.pairedClients.size == 1) {
                            val clientId = input.pairedClients.single().id
                            input.viewModel.sendToNoteLink(summary.task.id, input.callbacks.beforeExport) { artifact, progress ->
                                input.callbacks.sendToNoteLink(clientId, artifact, progress)
                            }
                        } else {
                            actions.onSendTask(summary)
                        }
                    },
                    onDelete = { actions.onDeleteTask(summary) },
                ),
            )
            HorizontalDivider(color = ExportBorder)
        }
    }
}

@Composable
private fun ExportDeleteTaskDialog(onDismiss: () -> Unit, onDelete: () -> Unit) {
    EinkModalOverlay(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(noteText("删除导出任务？", "Delete export task?"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(noteText("内部生成文件和增量缓存将被删除，Downloads 与 NoteLink 中的副本不受影响。", "Generated files and incremental caches will be deleted. Copies in Downloads and NoteLink are unaffected."))
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
private fun ExportSendTaskDialog(
    clients: List<PairedDevice>,
    onDismiss: () -> Unit,
    onSend: (PairedDevice) -> Unit,
) {
    EinkModalOverlay(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(noteText("发送到 NoteLink", "Send to NoteLink"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            clients.sortedByDescending(PairedDevice::lastUsedAt).forEach { client ->
                EinkDialogAction(client.name) { onSend(client) }
            }
            EinkDialogAction(noteText("取消", "Cancel"), onClick = onDismiss)
        }
    }
}

@Composable
private fun ExportTopBar(taskCount: Int, busy: Boolean, onBack: () -> Unit, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, enabled = !busy) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, noteText("返回", "Back"))
        }
        Text(noteText("导出", "Export"), fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(noteText("$taskCount 项", "$taskCount tasks"), color = ExportMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onAdd, enabled = !busy) { Icon(Icons.Filled.Add, noteText("新建导出任务", "New export task")) }
    }
}

@Composable
private fun ExportTaskRow(
    state: ExportTaskRowState,
    actions: ExportTaskRowActions,
) {
    val summary = state.summary
    val active = state.active
    val availability = ExportActionAvailability.resolve(
        summary.state,
        state.busy,
        state.phoneTransferAvailable,
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(taskTitle(summary), fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    noteText("${summary.notebookTitle} · ${summary.pageCount} 页 · ${summary.task.format.name}", "${summary.notebookTitle} · ${summary.pageCount} pages · ${summary.task.format.name}"),
                    color = ExportMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(taskStatus(summary), color = stateColor(summary.state), fontSize = 13.sp)
            }
            if (active) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Color.Black)
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = actions.onSave, enabled = availability.saveEnabled) {
                Icon(Icons.Filled.Download, noteText("保存 ${summary.notebookTitle} 到 Downloads", "Save ${summary.notebookTitle} to Downloads"))
            }
            IconButton(onClick = actions.onSend, enabled = availability.sendEnabled) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    if (state.phoneTransferAvailable) noteText("发送 ${summary.notebookTitle} 到 NoteLink", "Send ${summary.notebookTitle} to NoteLink")
                    else noteText("NoteLink 未配对或不可用", "NoteLink is not paired or unavailable")
                )
            }
            IconButton(onClick = actions.onDelete, enabled = availability.deleteEnabled) {
                Icon(Icons.Filled.Delete, noteText("删除导出任务", "Delete export task"))
            }
        }
        if (active && state.progress != null) {
            val progress = state.progress
            val ratio = if (progress.total <= 0) 0f else progress.current.toFloat() / progress.total
            LinearProgressIndicator(
                progress = { ratio.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                color = Color.Black
            )
        }
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
    var selected by remember(notebookId, scope) { mutableStateOf(emptySet<UUID>()) }
    var notebookMenu by remember { mutableStateOf(false) }
    var notebookMenuWidth by remember { mutableStateOf(0) }
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
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, noteText("返回", "Back")) }
            Text(noteText("新建导出任务", "New export task"), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            val valid = notebookId != null && (scope == ExportScope.ALL_PAGES || selected.isNotEmpty())
            IconButton(
                enabled = valid,
                onClick = { notebookId?.let { callbacks.onCreate(it, scope, format, selected.toList()) } }
            ) { Icon(Icons.Filled.Check, noteText("创建", "Create")) }
        }
        HorizontalDivider(color = ExportBorder)
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box {
                Row(
                    Modifier.fillMaxWidth().onSizeChanged { notebookMenuWidth = it.width }
                        .border(1.dp, ExportBorder).clickable { notebookMenu = true }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(notebook?.title ?: noteText("选择笔记本", "Select notebook"), modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ExpandMore, null)
                }
                NotebookPickerPopup(
                    expanded = notebookMenu,
                    widthPx = notebookMenuWidth,
                    options = state.notebooks,
                    onSelect = { option -> notebookId = option.id; notebookMenu = false },
                    onDismiss = { notebookMenu = false }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScopeButton(noteText("单页", "Single page"), scope == ExportScope.SINGLE_PAGE) { scope = ExportScope.SINGLE_PAGE }
                ScopeButton(noteText("选择页面", "Selected pages"), scope == ExportScope.SELECTED_PAGES) { scope = ExportScope.SELECTED_PAGES }
                ScopeButton(noteText("全部页面", "All pages"), scope == ExportScope.ALL_PAGES) { scope = ExportScope.ALL_PAGES; format = ExportFormat.PDF }
            }
            if (scope == ExportScope.SINGLE_PAGE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScopeButton("PDF", format == ExportFormat.PDF) { format = ExportFormat.PDF }
                    ScopeButton("PNG", format == ExportFormat.PNG) { format = ExportFormat.PNG }
                }
            }
        }
        if (scope == ExportScope.ALL_PAGES) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(noteText("将持续跟随 ${pages.size} 个页面，并增量更新变化内容", "Tracks ${pages.size} pages and incrementally updates changes"), color = ExportMuted)
            }
        } else {
            PageSelectionGrid(
                pages = pages,
                selectedIds = selected,
                    pageBitmap = { id -> if (notebookId == currentNotebookId) callbacks.pageBitmap(id) else null },
                onToggle = { id ->
                    selected = if (scope == ExportScope.SINGLE_PAGE) setOf(id)
                    else if (id in selected) selected - id else selected + id
                }
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
    onDismiss: () -> Unit
) {
    if (!expanded || widthPx <= 0) return
    val density = LocalDensity.current
    val offset = with(density) { IntOffset(0, 52.dp.roundToPx()) }
    val width = with(density) { widthPx.toDp() }
    Popup(
        alignment = Alignment.TopStart,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Surface(
            modifier = Modifier.width(width).heightIn(max = 360.dp).border(1.dp, Color.Black, RectangleShape),
            shape = RectangleShape,
            color = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Text(
                        option.title,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(option) }.padding(14.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    HorizontalDivider(color = ExportBorder)
                }
            }
        }
    }
}

@Composable
private fun ScopeButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (selected) Color.Black else Color.White, RectangleShape)
            .border(1.dp, if (selected) Color.Black else ExportBorder, RectangleShape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)
    ) { Text(text, color = if (selected) Color.White else Color.Black) }
}

private fun taskTitle(summary: ExportTaskSummary): String = when (summary.task.scope) {
    ExportScope.SINGLE_PAGE -> noteText("单页导出", "Single-page export")
    ExportScope.SELECTED_PAGES -> noteText("选定页面", "Selected pages")
    ExportScope.ALL_PAGES -> noteText("全部页面", "All pages")
}

private fun taskStatus(summary: ExportTaskSummary): String = when (summary.state) {
    ExportTaskState.NEVER_GENERATED -> noteText("从未生成", "Never generated")
    ExportTaskState.CURRENT -> summary.lastGeneratedAt?.let {
        noteText("最新", "Current") + " · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}"
    } ?: noteText("最新", "Current")
    ExportTaskState.OUTDATED -> if (summary.stalePageCount == 0) noteText("页面集合或顺序待更新", "Page set or order changed")
        else noteText("${summary.stalePageCount} 页待更新", "${summary.stalePageCount} pages need updating")
    ExportTaskState.SOURCE_MISSING -> noteText("源页面已删除", "Source page deleted")
    ExportTaskState.FAILED -> summary.lastError?.let { noteText("失败 · $it", "Failed · $it") }
        ?: noteText("生成失败", "Generation failed")
}

private fun stateColor(state: ExportTaskState): Color = when (state) {
    ExportTaskState.CURRENT -> Color(0xFF2E7D32)
    ExportTaskState.SOURCE_MISSING, ExportTaskState.FAILED -> Color(0xFFB00020)
    else -> ExportMuted
}
