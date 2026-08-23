package com.betterhv.note

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.math.floor

internal val PAGE_MANAGER_HEADER_HEIGHT = 36.dp
internal val PAGE_MANAGER_TAB_RAIL_WIDTH = 52.dp
internal val PAGE_MANAGER_SIDE_EXTRA_WIDTH = 48.dp
internal val PAGE_MANAGER_CONTENT_PADDING = 6.dp
internal val PAGE_MANAGER_HORIZONTAL_TITLE_WIDTH = 132.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PageManagerPanel(
    modifier: Modifier,
    dockEdge: DockEdge,
    thumbnailSize: DpSize,
    pages: List<PageUiInfo>,
    currentPageId: UUID?,
    thumbnail: (UUID) -> android.graphics.Bitmap?,
    requestThumbnails: (List<UUID>) -> Unit,
    onSelect: (UUID) -> Unit,
    onAddAfter: (UUID) -> UUID,
    onDelete: (UUID) -> Boolean,
    onMove: (UUID, Int) -> Boolean,
    onNotice: (String) -> Unit
) {
    var bookmarkedOnly by remember { mutableStateOf(false) }
    val displayedPages = if (bookmarkedOnly) pages.filter(PageUiInfo::bookmarked) else pages
    val currentIndex = displayedPages.indexOfFirst { it.id == currentPageId }.coerceAtLeast(0)
    var model by remember { mutableStateOf(PageManagerModel().openAt(currentIndex)) }
    var panelWidth by remember { mutableFloatStateOf(1f) }
    var panelHeight by remember { mutableFloatStateOf(1f) }
    var side1Armed by remember { mutableStateOf(false) }
    var side1Delta by remember { mutableStateOf(0) }
    var draggingId by remember { mutableStateOf<UUID?>(null) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }
    var edgeDirection by remember { mutableStateOf(0) }
    var edgeSince by remember { mutableLongStateOf(0L) }
    val vertical = dockEdge == DockEdge.START || dockEdge == DockEdge.END
    val scrollState = rememberScrollState()

    LaunchedEffect(currentPageId, displayedPages.map(PageUiInfo::id)) {
        model = model.afterStructureChange(currentIndex, displayedPages.size)
    }
    LaunchedEffect(model.pendingDeleteId, model.pendingDeleteAt) {
        if (model.pendingDeleteId != null) {
            val stamp = model.pendingDeleteAt
            delay(PageManagerModel.DELETE_CONFIRM_MS)
            if (model.pendingDeleteAt == stamp) model = model.cancelDelete()
        }
    }
    LaunchedEffect(model.chunkIndex, vertical) {
        scrollState.scrollTo(0)
    }
    val visibleIndices = model.visibleRange(displayedPages.size).toList()
    val visiblePages = visibleIndices.mapNotNull(displayedPages::getOrNull)
    LaunchedEffect(visiblePages.map { it.id to it.contentRevision }) {
        requestThumbnails(visiblePages.map { it.id })
    }

    val side1Input = Modifier.pointerInteropFilter { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tool = if (event.pointerCount > 0) event.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
                val modifierKey = PenFunctionKey.classifyClickModifier(
                    tool, event.buttonState or PenButtonTracker.currentButtonState, event.source
                )
                side1Armed = modifierKey == PenSideButton.SIDE_1
                val main = if (vertical) event.y else event.x
                val mainSize = if (vertical) panelHeight else panelWidth
                side1Delta = if (main < mainSize / 2f) -1 else 1
                side1Armed
            }
            MotionEvent.ACTION_UP -> {
                if (!side1Armed) return@pointerInteropFilter false
                side1Armed = false
                PenButtonTracker.consumeClickModifier()
                val (updated, changed) = model.changeChunk(side1Delta, displayedPages.size)
                model = updated
                if (!changed) onNotice(if (side1Delta < 0) "已经是第一组缩略图" else "已经是最后一组缩略图")
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumed = side1Armed
                side1Armed = false
                consumed
            }
            else -> side1Armed
        }
    }

    val dragInput = Modifier.pointerInput(vertical, displayedPages.map { it.id }) {
        val slotExtent = if (vertical) thumbnailSize.height.toPx() else thumbnailSize.width.toPx()
        detectDragGesturesAfterLongPress(
            onDragStart = { position ->
                model = model.cancelDelete()
                val main = (if (vertical) position.y else position.x) + scrollState.value
                val slot = floor(main / slotExtent).toInt()
                    .coerceIn(0, PageManagerModel.CHUNK_SIZE - 1)
                val index = model.chunkIndex * PageManagerModel.CHUNK_SIZE + slot
                draggingId = displayedPages.getOrNull(index)?.id
                dragTargetIndex = index.takeIf { it in displayedPages.indices }
                edgeDirection = 0
                edgeSince = 0L
            },
            onDrag = { change, _ ->
                val id = draggingId ?: return@detectDragGesturesAfterLongPress
                change.consume()
                val mainSize = if (vertical) panelHeight else panelWidth
                val viewportMain = if (vertical) change.position.y else change.position.x
                val main = viewportMain + scrollState.value
                val slot = floor(main / slotExtent).toInt().coerceIn(0, PageManagerModel.CHUNK_SIZE - 1)
                dragTargetIndex = (model.chunkIndex * PageManagerModel.CHUNK_SIZE + slot)
                    .coerceIn(0, displayedPages.lastIndex)
                val edge = when {
                    viewportMain < slotExtent * 0.55f -> -1
                    viewportMain > mainSize - slotExtent * 0.55f -> 1
                    else -> 0
                }
                val now = SystemClock.uptimeMillis()
                if (edge == 0) {
                    edgeDirection = 0
                    edgeSince = 0L
                } else if (edge != edgeDirection) {
                    edgeDirection = edge
                    edgeSince = now
                } else if (now - edgeSince >= PageManagerModel.EDGE_DWELL_MS) {
                    val (updated, changed) = model.changeChunk(edge, displayedPages.size)
                    if (changed) {
                        model = updated
                        dragTargetIndex = if (edge < 0) {
                            minOf(
                                (model.chunkIndex + 1) * PageManagerModel.CHUNK_SIZE - 1,
                                displayedPages.lastIndex
                            )
                        } else {
                            model.chunkIndex * PageManagerModel.CHUNK_SIZE
                        }
                    }
                    edgeSince = now
                }
                @Suppress("UNUSED_VARIABLE") val keepGestureAlive = id
            },
            onDragEnd = {
                val id = draggingId
                val target = dragTargetIndex
                if (id != null && target != null) {
                    val targetPageId = displayedPages.getOrNull(target)?.id
                    val fullTarget = targetPageId?.let { targetId ->
                        pages.indexOfFirst { it.id == targetId }
                    }?.takeIf { it >= 0 } ?: target
                    onMove(id, fullTarget)
                }
                draggingId = null
                dragTargetIndex = null
            },
            onDragCancel = {
                draggingId = null
                dragTargetIndex = null
            }
        )
    }

    val card: @Composable (Int, Modifier) -> Unit = { slot, cardModifier ->
        val pageIndex = model.chunkIndex * PageManagerModel.CHUNK_SIZE + slot
        val info = displayedPages.getOrNull(pageIndex)
        if (info == null) {
            Box(cardModifier)
        } else {
            PageThumbnailCard(
                modifier = cardModifier.padding(2.dp),
                info = info,
                bitmap = thumbnail(info.id),
                current = info.id == currentPageId,
                pendingDelete = info.id == model.pendingDeleteId,
                dragging = info.id == draggingId,
                onNormalClick = {
                    model = model.cancelDelete()
                    onSelect(info.id)
                },
                onSide2Click = {
                    model = model.cancelDelete()
                    onAddAfter(info.id)
                    if (bookmarkedOnly) bookmarkedOnly = false
                    val fullIndex = pages.indexOfFirst { it.id == info.id }.coerceAtLeast(0)
                    model = model.afterStructureChange(fullIndex + 1, pages.size + 1)
                },
                onSide3Click = {
                    val now = SystemClock.uptimeMillis()
                    val (updated, decision) = model.deleteClick(info.id, now)
                    model = updated
                    if (decision == DeleteDecision.ARMED) {
                        onNotice("再次按 Side3 删除第 ${info.pageNumber} 页")
                    } else if (onDelete(info.id)) {
                        onNotice("已删除第 ${info.pageNumber} 页")
                        model = model.afterStructureChange(
                            currentIndex,
                            (displayedPages.size - 1).coerceAtLeast(0)
                        )
                    }
                }
            )
        }
    }
    val showAll = {
        bookmarkedOnly = false
        model = PageManagerModel().openAt(
            pages.indexOfFirst { it.id == currentPageId }.coerceAtLeast(0)
        )
    }
    val showBookmarks = {
        bookmarkedOnly = true
        val bookmarkIndex = pages.filter(PageUiInfo::bookmarked)
            .indexOfFirst { it.id == currentPageId }
            .coerceAtLeast(0)
        model = PageManagerModel().openAt(bookmarkIndex)
    }
    val viewportModifier = Modifier
        .fillMaxSize()
        .padding(PAGE_MANAGER_CONTENT_PADDING)
        .onSizeChanged { panelWidth = it.width.toFloat(); panelHeight = it.height.toFloat() }
        .then(side1Input)
        .then(dragInput)

    ToolbarFlyoutSurface(
        title = "页面 ${currentIndex + 1}/${displayedPages.size.coerceAtLeast(1)}",
        dockEdge = dockEdge,
        modifier = modifier,
        compactHeaderWidth = if (vertical) null else PAGE_MANAGER_HORIZONTAL_TITLE_WIDTH,
        wrapContentWidth = false
    ) {
        if (vertical) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(PAGE_MANAGER_TAB_RAIL_WIDTH)) {
                    PageManagerTab(
                        selected = !bookmarkedOnly,
                        icon = Icons.Filled.Layers,
                        description = "全部页面",
                        modifier = Modifier.weight(1f),
                        onClick = showAll
                    )
                    PageManagerTab(
                        selected = bookmarkedOnly,
                        icon = Icons.Filled.Bookmark,
                        description = "书签页面",
                        modifier = Modifier.weight(1f),
                        onClick = showBookmarks
                    )
                }
                Column(
                    viewportModifier.verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    repeat(PageManagerModel.CHUNK_SIZE) { slot ->
                        card(slot, Modifier.size(thumbnailSize))
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(PAGE_MANAGER_TAB_RAIL_WIDTH).fillMaxHeight()) {
                    PageManagerTab(
                        selected = !bookmarkedOnly,
                        icon = Icons.Filled.Layers,
                        description = "全部页面",
                        modifier = Modifier.weight(1f),
                        onClick = showAll
                    )
                    PageManagerTab(
                        selected = bookmarkedOnly,
                        icon = Icons.Filled.Bookmark,
                        description = "书签页面",
                        modifier = Modifier.weight(1f),
                        onClick = showBookmarks
                    )
                }
                Row(
                    viewportModifier.horizontalScroll(scrollState),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(PageManagerModel.CHUNK_SIZE) { slot ->
                        card(slot, Modifier.size(thumbnailSize))
                    }
                }
            }
        }
    }
}

@Composable
private fun PageManagerTab(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .fillMaxSize()
            .background(if (selected) Color.Black else Color(0xFFE5E5E5))
            .border(0.5.dp, Color(0xFF808080))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = if (selected) Color.White else Color.Black)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PageThumbnailCard(
    modifier: Modifier,
    info: PageUiInfo,
    bitmap: android.graphics.Bitmap?,
    current: Boolean,
    pendingDelete: Boolean,
    dragging: Boolean,
    onNormalClick: () -> Unit,
    onSide2Click: () -> Unit,
    onSide3Click: () -> Unit
) {
    val stylus = remember(info.id) { StylusClickResolver() }
    val border = when {
        pendingDelete -> Color(0xFFB00020)
        current -> Color.Black
        else -> Color(0xFF888888)
    }
    @Suppress("DEPRECATION")
    Box(
        modifier = modifier
            .background(if (dragging) Color(0xFFD0D0D0) else Color.White)
            .border(if (current || pendingDelete) 3.dp else 1.dp, border)
            .pointerInteropFilter { event -> stylus.observe(event); false }
            .clickable {
                when (stylus.consume()) {
                    PenSideButton.SIDE_2 -> onSide2Click()
                    PenSideButton.SIDE_3 -> onSide3Click()
                    PenSideButton.SIDE_1 -> Unit // Parent consumes Side1 for chunk paging.
                    PenSideButton.NONE -> onNormalClick()
                }
            }
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page ${info.pageNumber}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
        Text(
            text = info.pageNumber.toString(),
            modifier = Modifier.align(Alignment.TopStart).background(Color(0xDFFFFFFF)).padding(2.dp),
            color = Color.Black
        )
        if (info.bookmarked) {
            Icon(
                Icons.Filled.Bookmark,
                contentDescription = "Bookmarked",
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(16.dp),
                tint = Color.Black
            )
        }
    }
}
