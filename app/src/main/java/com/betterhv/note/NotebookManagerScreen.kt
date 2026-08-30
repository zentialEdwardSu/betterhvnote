package com.betterhv.note

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.betterhv.note.storage.NotebookSummary
import kotlinx.coroutines.delay
import java.util.UUID

private const val NOTEBOOK_DELETE_CONFIRM_MS = 3_000L
private val NotionBorder = Color(0xFFE3E3E1)
private val NotionMuted = Color(0xFF787774)
private val NotionHover = Color(0xFFF7F7F5)

private enum class NotebookCreateAction { CURRENT_TO_END, SELECTION, BLANK }

data class NotebookManagerState(
    val summaries: List<NotebookSummary>,
    val pages: List<PageUiInfo>,
    val currentToEndIds: Set<UUID>,
    val busy: Boolean,
    val thumbnailSize: DpSize,
)

data class NotebookManagerActions(
    val coverBitmap: (NotebookSummary) -> Bitmap?,
    val pageBitmap: (UUID) -> Bitmap?,
    val requestCovers: (List<NotebookSummary>) -> Unit,
    val requestPages: (List<UUID>) -> Unit,
    val onSwitchNotebook: (UUID) -> Unit,
    val onCreateFromCurrentToEnd: (Set<UUID>) -> Unit,
    val onCreateFromSelection: (Set<UUID>) -> Unit,
    val onCreateBlank: () -> Unit,
    val onImportLocalPdf: (Uri) -> Unit,
    val onImportNoteLinkPdf: () -> Unit,
    val onDeleteNotebook: (UUID) -> Unit,
    val onExportNotebook: (UUID) -> Unit,
    val onNotice: (String) -> Unit,
    val onClose: () -> Unit,
)

private data class NotebookTopBarState(
    val selecting: Boolean,
    val notebookCount: Int,
    val selectedCount: Int,
    val busy: Boolean,
    val canCreateCurrentToEnd: Boolean,
    val canSelectPages: Boolean,
)

private data class NotebookTopBarActions(
    val onBack: () -> Unit,
    val onCreateCurrentToEnd: () -> Unit,
    val onSelectPages: () -> Unit,
    val onCreateBlank: () -> Unit,
    val onImportPdf: () -> Unit,
    val onConfirmSelection: () -> Unit,
)

private data class NotebookGridState(
    val summaries: List<NotebookSummary>,
    val thumbnailSize: DpSize,
    val busy: Boolean,
    val pendingDeleteId: UUID?,
)

private data class NotebookGridActions(
    val coverBitmap: (NotebookSummary) -> Bitmap?,
    val onOpen: (NotebookSummary) -> Unit,
    val onDelete: (NotebookSummary) -> Unit,
    val onExport: (NotebookSummary) -> Unit,
)

private data class NotebookSummaryCardState(
    val summary: NotebookSummary,
    val bitmap: Bitmap?,
    val thumbnailSize: DpSize,
    val enabled: Boolean,
    val pendingDelete: Boolean,
)

private data class NotebookSummaryCardActions(
    val onClick: () -> Unit,
    val onDelete: () -> Unit,
    val onExport: () -> Unit,
)

private data class NotionIconButtonState(
    val icon: ImageVector,
    val contentDescription: String,
    val enabled: Boolean,
    val modifier: Modifier = Modifier,
    val size: Dp = 40.dp,
    val outlined: Boolean = false,
    val active: Boolean = false,
)

@Composable
fun NotebookManagerScreen(
    state: NotebookManagerState,
    actions: NotebookManagerActions,
) {
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<UUID>()) }
    var pendingDeleteId by remember { mutableStateOf<UUID?>(null) }
    var pendingDeleteAt by remember { mutableLongStateOf(0L) }
    var pdfSourceChooserOpen by remember { mutableStateOf(false) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(actions.onImportLocalPdf)
    }

    fun cancelDeleteConfirmation() {
        pendingDeleteId = null
        pendingDeleteAt = 0L
    }

    LaunchedEffect(state.summaries.map { it.id to it.cover?.contentRevision }) {
        actions.requestCovers(state.summaries)
    }
    LaunchedEffect(selecting, state.pages.map(PageUiInfo::id)) {
        if (selecting) actions.requestPages(state.pages.map(PageUiInfo::id))
    }
    LaunchedEffect(pendingDeleteId, pendingDeleteAt) {
        if (pendingDeleteId != null) {
            delay(NOTEBOOK_DELETE_CONFIRM_MS)
            cancelDeleteConfirmation()
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color.White, shape = RectangleShape) {
        Column(Modifier.fillMaxSize()) {
            NotebookTopBar(
                state = NotebookTopBarState(
                    selecting = selecting,
                    notebookCount = state.summaries.size,
                    selectedCount = selectedIds.size,
                    busy = state.busy,
                    canCreateCurrentToEnd = state.currentToEndIds.isNotEmpty(),
                    canSelectPages = state.pages.isNotEmpty(),
                ),
                actions = NotebookTopBarActions(
                    onBack = {
                        cancelDeleteConfirmation()
                        if (selecting) {
                            selecting = false
                            selectedIds = emptySet()
                        } else {
                            actions.onClose()
                        }
                    },
                    onCreateCurrentToEnd = {
                        cancelDeleteConfirmation()
                        actions.onCreateFromCurrentToEnd(state.currentToEndIds)
                    },
                    onSelectPages = {
                        cancelDeleteConfirmation()
                        selecting = true
                    },
                    onCreateBlank = {
                        cancelDeleteConfirmation()
                        actions.onCreateBlank()
                    },
                    onImportPdf = { pdfSourceChooserOpen = true },
                    onConfirmSelection = { actions.onCreateFromSelection(selectedIds) },
                ),
            )

            if (selecting) {
                PageSelectionGrid(
                    pages = state.pages,
                    selectedIds = selectedIds,
                    pageBitmap = actions.pageBitmap,
                    onToggle = { pageId ->
                        selectedIds = if (pageId in selectedIds) {
                            selectedIds - pageId
                        } else {
                            selectedIds + pageId
                        }
                    }
                )
            } else {
                NotebookGrid(
                    state = NotebookGridState(
                        summaries = state.summaries,
                        thumbnailSize = state.thumbnailSize,
                        busy = state.busy,
                        pendingDeleteId = pendingDeleteId,
                    ),
                    actions = NotebookGridActions(
                        coverBitmap = actions.coverBitmap,
                        onOpen = { summary ->
                            cancelDeleteConfirmation()
                            actions.onSwitchNotebook(summary.id)
                        },
                        onDelete = { summary ->
                            val now = SystemClock.uptimeMillis()
                            if (pendingDeleteId == summary.id &&
                                now - pendingDeleteAt <= NOTEBOOK_DELETE_CONFIRM_MS
                            ) {
                                cancelDeleteConfirmation()
                                actions.onDeleteNotebook(summary.id)
                            } else {
                                pendingDeleteId = summary.id
                                pendingDeleteAt = now
                                actions.onNotice("再次点击删除图标以删除“${summary.title}”")
                            }
                        },
                        onExport = { summary -> actions.onExportNotebook(summary.id) },
                    ),
                )
            }
        }
        if (state.busy) {
            Box(
                Modifier.fillMaxSize().background(Color(0x66FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RectangleShape,
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Black),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) { Text("处理中…", Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) }
            }
        }
        if (pdfSourceChooserOpen && !state.busy) {
            EinkChoiceOverlay(
                title = "导入 PDF",
                description = "选择 PDF 来源。导入后会创建独立的 PDF 笔记本。",
                onDismissRequest = { pdfSourceChooserOpen = false },
                choices = listOf(
                    "本地文件" to {
                        pdfSourceChooserOpen = false
                        pdfPicker.launch(arrayOf("application/pdf"))
                    },
                    "NoteLink" to {
                        pdfSourceChooserOpen = false
                        actions.onImportNoteLinkPdf()
                    }
                )
            )
        }
    }
}

@Composable
private fun NotebookTopBar(
    state: NotebookTopBarState,
    actions: NotebookTopBarActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NotionIconButton(
            state = NotionIconButtonState(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                enabled = !state.busy,
            ),
            onClick = actions.onBack,
        )
        Text(
            if (state.selecting) "选择页面" else "笔记本",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 8.dp)
        )
        Text(
            if (state.selecting) "${state.selectedCount} 已选择" else "${state.notebookCount} 本",
            color = NotionMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 10.dp)
        )
        Spacer(Modifier.weight(1f))
        NotebookTopBarActions(state, actions)
    }
    HorizontalDivider(color = NotionBorder, thickness = 1.dp)
}

@Composable
private fun NotebookTopBarActions(state: NotebookTopBarState, actions: NotebookTopBarActions) {
    if (state.selecting) {
        NotionIconButton(
            state = NotionIconButtonState(
                icon = Icons.Filled.Check,
                contentDescription = "将选中的 ${state.selectedCount} 页创建为新笔记本",
                enabled = state.selectedCount > 0 && !state.busy,
                outlined = true,
            ),
            onClick = actions.onConfirmSelection,
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotionIconButton(
                state = NotionIconButtonState(
                    icon = Icons.Filled.PictureAsPdf,
                    contentDescription = "导入 PDF",
                    enabled = !state.busy,
                    outlined = true,
                ),
                onClick = actions.onImportPdf,
            )
            CreateNotebookIconButton(
                action = NotebookCreateAction.CURRENT_TO_END,
                contentDescription = "当前页至末页创建新笔记本",
                enabled = state.canCreateCurrentToEnd && !state.busy,
                onClick = actions.onCreateCurrentToEnd,
            )
            CreateNotebookIconButton(
                action = NotebookCreateAction.SELECTION,
                contentDescription = "选择页面创建新笔记本",
                enabled = state.canSelectPages && !state.busy,
                onClick = actions.onSelectPages,
            )
            CreateNotebookIconButton(
                action = NotebookCreateAction.BLANK,
                contentDescription = "创建空白笔记本",
                enabled = !state.busy,
                onClick = actions.onCreateBlank,
            )
        }
    }
}

@Composable
private fun CreateNotebookIconButton(
    action: NotebookCreateAction,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val baseIcon = when (action) {
        NotebookCreateAction.CURRENT_TO_END -> Icons.Filled.ContentCut
        NotebookCreateAction.SELECTION -> Icons.Filled.Workspaces
        NotebookCreateAction.BLANK -> NotebookBookIcon
    }
    val foreground = if (enabled) Color.Black else Color(0xFFB3B3B1)
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 40.dp)
            .background(if (enabled) Color.White else NotionHover, RectangleShape)
            .border(1.dp, NotionBorder, RectangleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(28.dp)) {
            Icon(
                baseIcon,
                contentDescription = contentDescription,
                tint = foreground,
                modifier = Modifier.align(Alignment.CenterStart).size(23.dp)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(15.dp)
                    .background(Color.White, RectangleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = foreground,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun NotebookGrid(
    state: NotebookGridState,
    actions: NotebookGridActions,
) {
    val gap = 24.dp
    val cardWidth = state.thumbnailSize.width + 16.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cardWidth),
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        items(state.summaries, key = NotebookSummary::id) { summary ->
            NotebookSummaryCard(
                state = NotebookSummaryCardState(
                    summary = summary,
                    bitmap = actions.coverBitmap(summary),
                    thumbnailSize = state.thumbnailSize,
                    enabled = !state.busy,
                    pendingDelete = state.pendingDeleteId == summary.id,
                ),
                actions = NotebookSummaryCardActions(
                    onClick = { actions.onOpen(summary) },
                    onDelete = { actions.onDelete(summary) },
                    onExport = { actions.onExport(summary) },
                ),
            )
        }
    }
}

@Composable
private fun NotebookSummaryCard(
    state: NotebookSummaryCardState,
    actions: NotebookSummaryCardActions,
) {
    val summary = state.summary
    Column(
        modifier = Modifier
            .width(state.thumbnailSize.width + 16.dp)
            .background(if (summary.isCurrent) NotionHover else Color.White, RectangleShape)
            .clickable(enabled = state.enabled, onClick = actions.onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        NotebookCover(state)
        NotebookSummaryFooter(state, actions)
    }
}

@Composable
private fun NotebookCover(state: NotebookSummaryCardState) {
    val summary = state.summary
    Box(
        Modifier
            .size(state.thumbnailSize)
            .background(Color.White, RectangleShape)
            .border(
                if (summary.isCurrent) 2.dp else 1.dp,
                if (state.pendingDelete) Color(0xFFB00020) else if (summary.isCurrent) Color.Black else NotionBorder,
                RectangleShape,
            ),
    ) {
        state.bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = "${summary.title} 封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        if (summary.isWorkingCopy) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .size(30.dp)
                    .background(Color.White, RectangleShape)
                    .border(1.dp, NotionBorder, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    NotebookBookIcon,
                    contentDescription = "Working Copy",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun NotebookSummaryFooter(
    state: NotebookSummaryCardState,
    actions: NotebookSummaryCardActions,
) {
    val summary = state.summary
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                summary.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(summary.pageCount)
                    append(" 页")
                    if (summary.isCurrent) append("  ·  当前")
                    if (summary.isWorkingCopy) append("  ·  Working Copy")
                },
                color = if (state.pendingDelete) Color(0xFFB00020) else NotionMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        NotionIconButton(
            state = NotionIconButtonState(
                icon = Icons.Filled.IosShare,
                contentDescription = "导出 ${summary.title}",
                enabled = state.enabled,
                size = 34.dp,
            ),
            onClick = actions.onExport,
        )
        if (!summary.isWorkingCopy) {
            NotionIconButton(
                state = NotionIconButtonState(
                    icon = Icons.Filled.Delete,
                    contentDescription = if (state.pendingDelete) {
                        "确认删除 ${summary.title}"
                    } else {
                        "删除 ${summary.title}"
                    },
                    enabled = state.enabled,
                    active = state.pendingDelete,
                    size = 34.dp,
                ),
                onClick = actions.onDelete,
            )
        }
    }
}

@Composable
private fun NotionIconButton(
    state: NotionIconButtonState,
    onClick: () -> Unit
) {
    val foreground = when {
        !state.enabled -> Color(0xFFB3B3B1)
        state.active -> Color.White
        else -> Color.Black
    }
    val background = when {
        state.active -> Color(0xFFB00020)
        else -> Color.Transparent
    }
    Box(
        modifier = state.modifier
            .size(state.size)
            .background(background, RectangleShape)
            .then(if (state.outlined) Modifier.border(1.dp, NotionBorder, RectangleShape) else Modifier)
            .clickable(enabled = state.enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            state.icon,
            contentDescription = state.contentDescription,
            tint = foreground,
            modifier = Modifier.size(state.size * 0.55f)
        )
    }
}
