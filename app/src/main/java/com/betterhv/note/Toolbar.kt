package com.betterhv.note

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import android.view.MotionEvent
import com.betterhv.note.ink.PenType

/** Which edge of the screen the toolbar is currently docked to. */
enum class DockEdge { TOP, END, BOTTOM, START }

private val WIDTH_SAMPLE_DP = listOf(1.5f, 3f, 5f, 8f, 12f)

/** Dockable editing toolbar with a pen-settings popup anchored to the pen button. */
@Composable
fun EditorToolbar(
    modifier: Modifier = Modifier,
    dockEdge: DockEdge,
    onDockEdgeChange: (DockEdge) -> Unit,
    dockFraction: Float,
    onDockFractionChange: (Float) -> Unit,
    toolbarHidden: Boolean,
    onToolbarHiddenChange: (Boolean) -> Unit,
    visibleItems: Set<ToolbarItem>,
    requestedItem: ToolbarItem?,
    onRequestedItemConsumed: () -> Unit,
    hardwareShortcutAction: ShortcutAction?,
    onHardwareShortcutConsumed: () -> Unit,
    onShortcutSceneChange: (ShortcutScene?) -> Unit,
    toolKind: ToolKind,
    onToolSelected: (ToolKind) -> Unit,
    eraserMode: EraserMode,
    onEraserModeToggle: () -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
    hasSelection: Boolean,
    onDelete: () -> Unit,
    insertionActive: Boolean,
    onInsertImage: () -> Unit,
    onInsertText: () -> Unit,
    onInsertLocal: (com.betterhv.transfer.core.ContentKind) -> Unit,
    onInsertNoteLink: (com.betterhv.transfer.core.ContentKind) -> Unit,
    pageManagerOpen: Boolean,
    onPageManagerToggle: () -> Unit,
    onPageAdd: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    notebookManagerOpen: Boolean,
    onNotebookManagerToggle: () -> Unit,
    onNotebookCurrentPage: () -> Unit,
    onNotebookCurrentToEnd: () -> Unit,
    exportPanelOpen: Boolean,
    onExportPanelToggle: () -> Unit,
    settingsOpen: Boolean,
    debugMode: Boolean,
    onSettingsOpen: () -> Unit,
    onDebugToggle: () -> Unit,
    penToolbarSettings: PenToolbarSettings,
    onPenSlotSelected: (Int) -> Unit,
    onPenSettingsChange: (Int, PenSettings) -> Unit,
    onPenPanelVisibilityChange: (Boolean) -> Unit,
    onToolbarDragStateChange: (Boolean) -> Unit,
    onInteractionBlockChange: (Boolean) -> Unit
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var toolbarSize by remember { mutableStateOf(IntSize.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    val marginPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val base = toolbarPosition(containerSize, toolbarSize, dockEdge, dockFraction, marginPx)

    fun finishDrag() {
        if (containerSize == IntSize.Zero || toolbarSize == IntSize.Zero) return
        val left = (base.x + dragDelta.x).coerceIn(
            0f, (containerSize.width - toolbarSize.width).coerceAtLeast(0).toFloat()
        )
        val top = (base.y + dragDelta.y).coerceIn(
            0f, (containerSize.height - toolbarSize.height).coerceAtLeast(0).toFloat()
        )
        val edge = nearestDockEdge(containerSize, toolbarSize, Offset(left, top))
        val fraction = when (edge) {
            DockEdge.START, DockEdge.END ->
                ((top - marginPx) /
                    (containerSize.height - toolbarSize.height - marginPx * 2).coerceAtLeast(1)).coerceIn(0f, 1f)
            DockEdge.TOP, DockEdge.BOTTOM ->
                ((left - marginPx) /
                    (containerSize.width - toolbarSize.width - marginPx * 2).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
        dragDelta = Offset.Zero
        onDockEdgeChange(edge)
        onDockFractionChange(fraction)
    }

    Box(modifier.onSizeChanged { containerSize = it }) {
        if (toolbarHidden) {
            val handleSize = with(LocalDensity.current) { 44.dp.roundToPx() }
            val hiddenPosition = toolbarPosition(
                containerSize,
                IntSize(handleSize, handleSize),
                dockEdge,
                dockFraction,
                0
            )
            HiddenToolbarHandle(
                edge = dockEdge,
                modifier = Modifier.offset { hiddenPosition },
                onClick = { onToolbarHiddenChange(false) }
            )
        } else {
            Surface(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (base.x + dragDelta.x).toInt().coerceIn(
                                0, (containerSize.width - toolbarSize.width).coerceAtLeast(0)
                            ),
                            (base.y + dragDelta.y).toInt().coerceIn(
                                0, (containerSize.height - toolbarSize.height).coerceAtLeast(0)
                            )
                        )
                    }
                    .onSizeChanged { toolbarSize = it }
                    .penInputGuard(onInteractionBlockChange),
                shape = RectangleShape,
                color = Color(0xFFEFEFEF),
                contentColor = Color.Black,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                ToolbarContent(
                    dockEdge = dockEdge,
                    onHandleDrag = { dragDelta += it },
                    onHandleDragStart = { onToolbarDragStateChange(true) },
                    onHandleDragEnd = {
                        finishDrag()
                        onToolbarDragStateChange(false)
                    },
                    onHandleClick = { onToolbarHiddenChange(true) },
                    visibleItems = visibleItems,
                    requestedItem = requestedItem,
                    onRequestedItemConsumed = onRequestedItemConsumed,
                    hardwareShortcutAction = hardwareShortcutAction,
                    onHardwareShortcutConsumed = onHardwareShortcutConsumed,
                    onShortcutSceneChange = onShortcutSceneChange,
                    toolKind = toolKind,
                    onToolSelected = onToolSelected,
                    eraserMode = eraserMode,
                    onEraserModeToggle = onEraserModeToggle,
                    canUndo = canUndo,
                    onUndo = onUndo,
                    canRedo = canRedo,
                    onRedo = onRedo,
                    hasSelection = hasSelection,
                    onDelete = onDelete,
                    insertionActive = insertionActive,
                    onInsertImage = onInsertImage,
                    onInsertText = onInsertText,
                    onInsertLocal = onInsertLocal,
                    onInsertNoteLink = onInsertNoteLink,
                    pageManagerOpen = pageManagerOpen,
                    onPageManagerToggle = onPageManagerToggle,
                    onPageAdd = onPageAdd,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage,
                    notebookManagerOpen = notebookManagerOpen,
                    onNotebookManagerToggle = onNotebookManagerToggle,
                    onNotebookCurrentPage = onNotebookCurrentPage,
                    onNotebookCurrentToEnd = onNotebookCurrentToEnd,
                    exportPanelOpen = exportPanelOpen,
                    onExportPanelToggle = onExportPanelToggle,
                    settingsOpen = settingsOpen,
                    debugMode = debugMode,
                    onSettingsOpen = onSettingsOpen,
                    onDebugToggle = onDebugToggle,
                    penToolbarSettings = penToolbarSettings,
                    onPenSlotSelected = onPenSlotSelected,
                    onPenSettingsChange = onPenSettingsChange,
                    onPenPanelVisibilityChange = onPenPanelVisibilityChange
                )
            }
        }
    }
}

@Composable
private fun ToolbarContent(
    dockEdge: DockEdge,
    onHandleDrag: (Offset) -> Unit,
    onHandleDragStart: () -> Unit,
    onHandleDragEnd: () -> Unit,
    onHandleClick: () -> Unit,
    visibleItems: Set<ToolbarItem>,
    requestedItem: ToolbarItem?,
    onRequestedItemConsumed: () -> Unit,
    hardwareShortcutAction: ShortcutAction?,
    onHardwareShortcutConsumed: () -> Unit,
    onShortcutSceneChange: (ShortcutScene?) -> Unit,
    toolKind: ToolKind,
    onToolSelected: (ToolKind) -> Unit,
    eraserMode: EraserMode,
    onEraserModeToggle: () -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
    hasSelection: Boolean,
    onDelete: () -> Unit,
    insertionActive: Boolean,
    onInsertImage: () -> Unit,
    onInsertText: () -> Unit,
    onInsertLocal: (com.betterhv.transfer.core.ContentKind) -> Unit,
    onInsertNoteLink: (com.betterhv.transfer.core.ContentKind) -> Unit,
    pageManagerOpen: Boolean,
    onPageManagerToggle: () -> Unit,
    onPageAdd: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    notebookManagerOpen: Boolean,
    onNotebookManagerToggle: () -> Unit,
    onNotebookCurrentPage: () -> Unit,
    onNotebookCurrentToEnd: () -> Unit,
    exportPanelOpen: Boolean,
    onExportPanelToggle: () -> Unit,
    settingsOpen: Boolean,
    debugMode: Boolean,
    onSettingsOpen: () -> Unit,
    onDebugToggle: () -> Unit,
    penToolbarSettings: PenToolbarSettings,
    onPenSlotSelected: (Int) -> Unit,
    onPenSettingsChange: (Int, PenSettings) -> Unit,
    onPenPanelVisibilityChange: (Boolean) -> Unit
) {
    var editingPenSlot by remember { mutableStateOf<Int?>(null) }
    var insertExpanded by remember { mutableStateOf(false) }
    var quickPanelExpanded by remember { mutableStateOf(false) }
    var eraserExpanded by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            onPenPanelVisibilityChange(false)
            onShortcutSceneChange(null)
        }
    }
    fun closeFlyouts() {
        editingPenSlot = null
        insertExpanded = false
        quickPanelExpanded = false
        eraserExpanded = false
    }
    LaunchedEffect(editingPenSlot, insertExpanded, quickPanelExpanded, eraserExpanded) {
        onPenPanelVisibilityChange(
            editingPenSlot != null || insertExpanded || quickPanelExpanded || eraserExpanded
        )
        onShortcutSceneChange(if (insertExpanded) ShortcutScene.INSERT else null)
    }
    LaunchedEffect(requestedItem) {
        when (requestedItem) {
            ToolbarItem.TAIL_ERASER -> { closeFlyouts(); eraserExpanded = true }
            ToolbarItem.INSERT -> { closeFlyouts(); insertExpanded = true }
            ToolbarItem.MENU -> { closeFlyouts(); quickPanelExpanded = true }
            else -> Unit
        }
        if (requestedItem != null) onRequestedItemConsumed()
    }
    LaunchedEffect(hardwareShortcutAction) {
        if (hardwareShortcutAction?.scene == ShortcutScene.INSERT) closeFlyouts()
        if (hardwareShortcutAction != null) onHardwareShortcutConsumed()
    }

    val buttons: @Composable () -> Unit = {
        ToolbarDragHandle(
            dockEdge = dockEdge,
            onDragStart = {
                closeFlyouts()
                onHandleDragStart()
            },
            onDrag = { delta -> closeFlyouts(); onHandleDrag(delta) },
            onDragEnd = onHandleDragEnd,
            onClick = { closeFlyouts(); onHandleClick() }
        )
        repeat(PenToolbarSettings.SLOT_COUNT) { slot ->
            val toolbarItem = ToolbarItem.entries[slot]
            if (toolbarItem !in visibleItems) return@repeat
            val slotSettings = penToolbarSettings.settingsFor(slot)
            val selectSlot = {
                onPenSlotSelected(slot)
                onToolSelected(ToolKind.PEN)
            }
            Box {
                SquarePainterButton(
                    icon = penTypeIcon(slotSettings.activeType),
                    contentDescription = "Pen slot ${slot + 1}: ${penTypeName(slotSettings.activeType)}; " +
                        "double tap for pen settings",
                    enabled = true,
                    selected = toolKind == ToolKind.PEN && penToolbarSettings.activeSlot == slot,
                    onClick = selectSlot,
                    onFunctionClick = {
                        closeFlyouts()
                        selectSlot()
                        editingPenSlot = slot
                    },
                    onDoubleClick = {
                        closeFlyouts()
                        selectSlot()
                        editingPenSlot = slot
                    }
                )
                PenSettingsMenu(
                    expanded = editingPenSlot == slot,
                    onDismiss = { editingPenSlot = null },
                    dockEdge = dockEdge,
                    settings = slotSettings,
                    onSettingsChange = { onPenSettingsChange(slot, it) }
                )
            }
        }

        val eraserIcon = when (eraserMode) {
            EraserMode.WHOLE_STROKE -> Icons.Filled.Clear
            EraserMode.POINT -> Icons.Filled.RemoveCircleOutline
        }
        val eraserDesc = when (eraserMode) {
            EraserMode.WHOLE_STROKE -> "Tail eraser: whole-stroke (tap to switch to point)"
            EraserMode.POINT -> "Tail eraser: point (tap to switch to whole-stroke)"
        }
        if (ToolbarItem.TAIL_ERASER in visibleItems) Box {
            SquareIconButton(
                eraserIcon,
                eraserDesc,
                enabled = true,
                selected = eraserExpanded,
                onClick = { closeFlyouts(); eraserExpanded = true }
            )
            TailEraserMenu(
                expanded = eraserExpanded,
                onDismiss = { eraserExpanded = false },
                dockEdge = dockEdge,
                eraserMode = eraserMode,
                onToggle = onEraserModeToggle
            )
        }
        if (ToolbarItem.LASSO in visibleItems) SquareIconButton(
                Icons.Filled.CropFree,
                "Lasso select",
                enabled = true,
                selected = toolKind == ToolKind.LASSO
            ) { closeFlyouts(); onToolSelected(ToolKind.LASSO) }
        if (ToolbarItem.INSERT in visibleItems) Box {
            SquareIconButton(
                Icons.Filled.PostAdd,
                "插入图片或文字",
                enabled = true,
                selected = insertionActive,
                onClick = {
                    closeFlyouts()
                    insertExpanded = true
                }
            )
            InsertTypeMenu(
                expanded = insertExpanded,
                onDismiss = { insertExpanded = false },
                dockEdge = dockEdge,
                onLocal = { kind -> insertExpanded = false; onInsertLocal(kind) },
                onNoteLink = { kind -> insertExpanded = false; onInsertNoteLink(kind) }
            )
        }
        if (ToolbarItem.UNDO in visibleItems) SquareIconButton(
            Icons.AutoMirrored.Filled.Undo,
            "Undo",
            enabled = canUndo,
            selected = false,
            onClick = { closeFlyouts(); onUndo() }
        )
        if (ToolbarItem.REDO in visibleItems) SquareIconButton(
            Icons.AutoMirrored.Filled.Redo,
            "Redo",
            enabled = canRedo,
            selected = false,
            onClick = { closeFlyouts(); onRedo() }
        )
        if (ToolbarItem.DELETE in visibleItems) SquareIconButton(
            Icons.Filled.Delete,
            "Delete selection",
            enabled = hasSelection,
            selected = false,
            onClick = { closeFlyouts(); onDelete() }
        )
        if (ToolbarItem.PAGES in visibleItems) SquareIconButton(
            Icons.Filled.Layers,
            "Pages: tap to manage; Side1 add; Side2 previous; Side3 next",
            enabled = true,
            selected = pageManagerOpen,
            onFunctionClick = onPageAdd,
            onSide2Click = onPreviousPage,
            onSide3Click = onNextPage,
            onClick = { closeFlyouts(); onPageManagerToggle() }
        )
        if (ToolbarItem.NOTEBOOKS in visibleItems) SquareIconButton(
            NotebookBookIcon,
            "Notebooks: tap to manage; Side1 current page; Side2 current to end",
            enabled = true,
            selected = notebookManagerOpen,
            onFunctionClick = onNotebookCurrentPage,
            onSide2Click = onNotebookCurrentToEnd,
            onSide3Click = {},
            onClick = { closeFlyouts(); onNotebookManagerToggle() }
        )
        if (ToolbarItem.EXPORT in visibleItems) SquareIconButton(
            Icons.Filled.IosShare,
            "Export: tap to open export panel",
            enabled = true,
            selected = exportPanelOpen,
            onClick = { closeFlyouts(); onExportPanelToggle() }
        )
        Box {
            SquareIconButton(
                Icons.Filled.Menu,
                "Settings: tap to open; Side1 opens quick panel",
                enabled = true,
                selected = settingsOpen || quickPanelExpanded,
                onFunctionClick = {
                    closeFlyouts()
                    quickPanelExpanded = true
                },
                onClick = { closeFlyouts(); quickPanelExpanded = true }
            )
            QuickMenuPanel(
                expanded = quickPanelExpanded,
                onDismiss = { quickPanelExpanded = false },
                dockEdge = dockEdge,
                debugMode = debugMode,
                hiddenItems = ToolbarItem.entries.filterNot { it in visibleItems || it == ToolbarItem.MENU },
                onHiddenItem = { item ->
                    when (item) {
                        ToolbarItem.PEN_1, ToolbarItem.PEN_2, ToolbarItem.PEN_3 -> {
                            val slot = item.ordinal
                            onPenSlotSelected(slot)
                            onToolSelected(ToolKind.PEN)
                            quickPanelExpanded = false
                            editingPenSlot = slot
                        }
                        ToolbarItem.TAIL_ERASER -> { quickPanelExpanded = false; eraserExpanded = true }
                        ToolbarItem.LASSO -> { quickPanelExpanded = false; onToolSelected(ToolKind.LASSO) }
                        ToolbarItem.INSERT -> { quickPanelExpanded = false; insertExpanded = true }
                        ToolbarItem.UNDO -> { quickPanelExpanded = false; onUndo() }
                        ToolbarItem.REDO -> { quickPanelExpanded = false; onRedo() }
                        ToolbarItem.DELETE -> { quickPanelExpanded = false; onDelete() }
                        ToolbarItem.PAGES -> { quickPanelExpanded = false; onPageManagerToggle() }
                        ToolbarItem.NOTEBOOKS -> { quickPanelExpanded = false; onNotebookManagerToggle() }
                        ToolbarItem.EXPORT -> { quickPanelExpanded = false; onExportPanelToggle() }
                        ToolbarItem.MENU -> Unit
                    }
                },
                onSettings = {
                    quickPanelExpanded = false
                    onSettingsOpen()
                },
                onDebugToggle = {
                    quickPanelExpanded = false
                    onDebugToggle()
                }
            )
        }
    }

    if (dockEdge == DockEdge.TOP || dockEdge == DockEdge.BOTTOM) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { buttons() }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { buttons() }
    }
}

@Composable
private fun penTypeIcon(type: PenType): Painter = when (type) {
    PenType.NormalPen -> rememberVectorPainter(Icons.Filled.Edit)
    PenType.Pencil -> painterResource(R.drawable.ic_ink_pen)
    PenType.Marker -> painterResource(R.drawable.ic_ink_highlighter)
}

private fun penTypeName(type: PenType): String = when (type) {
    PenType.NormalPen -> "pen"
    PenType.Pencil -> "pencil"
    PenType.Marker -> "marker"
}

@Composable
private fun InsertTypeMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    dockEdge: DockEdge,
    onLocal: (com.betterhv.transfer.core.ContentKind) -> Unit,
    onNoteLink: (com.betterhv.transfer.core.ContentKind) -> Unit
) {
    if (!expanded) return
    var kind by remember { mutableStateOf<com.betterhv.transfer.core.ContentKind?>(null) }
    AttachedToolbarFlyout(title = "插入内容", dockEdge = dockEdge, onDismiss = onDismiss) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (kind == null) {
                PopupIconButton(
                    icon = Icons.Filled.AddPhotoAlternate,
                    contentDescription = "插入图片",
                    onClick = { kind = com.betterhv.transfer.core.ContentKind.IMAGE }
                )
                PopupIconButton(
                    icon = Icons.Filled.TextFields,
                    contentDescription = "插入文字",
                    onClick = { kind = com.betterhv.transfer.core.ContentKind.TEXT }
                )
            } else {
                PopupTextButton(
                    if (kind == com.betterhv.transfer.core.ContentKind.IMAGE) "系统文件" else "手动输入",
                    onClick = { onLocal(requireNotNull(kind)) }
                )
                PopupTextButton("NoteLink", onClick = { onNoteLink(requireNotNull(kind)) })
            }
        }
    }
}

@Composable
private fun QuickMenuPanel(
    expanded: Boolean,
    onDismiss: () -> Unit,
    dockEdge: DockEdge,
    debugMode: Boolean,
    hiddenItems: List<ToolbarItem>,
    onHiddenItem: (ToolbarItem) -> Unit,
    onSettings: () -> Unit,
    onDebugToggle: () -> Unit
) {
    if (!expanded) return
    AttachedToolbarFlyout(title = "Menu", dockEdge = dockEdge, onDismiss = onDismiss) {
        val vertical = dockEdge == DockEdge.START || dockEdge == DockEdge.END
        val entries: @Composable () -> Unit = {
            hiddenItems.forEach { item ->
                PopupTextButton(item.label, onClick = { onHiddenItem(item) })
            }
            PopupTextButton("设置", onSettings)
            PopupTextButton(if (debugMode) "关闭 Debug" else "开启 Debug", onDebugToggle, debugMode)
        }
        if (vertical) {
            Column(Modifier.verticalScroll(rememberScrollState())) { entries() }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState())) { entries() }
        }
    }
}

@Composable
private fun TailEraserMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    dockEdge: DockEdge,
    eraserMode: EraserMode,
    onToggle: () -> Unit
) {
    if (!expanded) return
    AttachedToolbarFlyout(title = "笔尾橡皮模式", dockEdge = dockEdge, onDismiss = onDismiss) {
        Row(Modifier.padding(4.dp)) {
            PopupTextButton("整笔擦除", onClick = { if (eraserMode != EraserMode.WHOLE_STROKE) onToggle() },
                selected = eraserMode == EraserMode.WHOLE_STROKE)
            PopupTextButton("局部擦除", onClick = { if (eraserMode != EraserMode.POINT) onToggle() },
                selected = eraserMode == EraserMode.POINT)
        }
    }
}

@Composable
private fun AttachedToolbarFlyout(
    title: String,
    dockEdge: DockEdge,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val gapPx = 0
    Popup(
        popupPositionProvider = remember(dockEdge, gapPx) {
            PenPanelPositionProvider(dockEdge, gapPx)
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        ToolbarFlyoutSurface(
            title = title,
            dockEdge = dockEdge,
            modifier = modifier,
            content = content
        )
    }
}

/**
 * Shared visual shell for every panel attached to the editing toolbar. It is also used by
 * the page manager, whose large viewport is laid out by the editor rather than a Popup.
 */
@Composable
internal fun ToolbarFlyoutSurface(
    title: String,
    dockEdge: DockEdge,
    modifier: Modifier = Modifier,
    compactHeaderWidth: Dp? = null,
    wrapContentWidth: Boolean = true,
    content: @Composable () -> Unit
) {
    val resolvedHeaderWidth = compactHeaderWidth.takeIf {
        dockEdge == DockEdge.TOP || dockEdge == DockEdge.BOTTOM
    }
    Surface(
        modifier = modifier,
        shape = RectangleShape,
        color = Color(0xFFF2F2F2),
        contentColor = Color.Black,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Black)
    ) {
        // Popup supplies window-sized maximum constraints. Resolve the panel from its
        // content's intrinsic width first so a small Insert/Menu flyout does not expand
        // across the whole screen. Explicitly-sized consumers (the page manager and pen
        // settings) still win through [modifier].
        Column(if (wrapContentWidth) Modifier.width(IntrinsicSize.Max) else Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(36.dp).background(Color(0xFFF2F2F2))) {
                Box(
                    Modifier
                        .then(if (resolvedHeaderWidth == null) Modifier.fillMaxWidth() else Modifier.width(resolvedHeaderWidth))
                        .height(36.dp)
                        .background(Color(0xFFF2F2F2))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        title,
                        color = Color.Black,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
            Box(Modifier.background(Color(0xFFF2F2F2))) { content() }
        }
    }
}

@Composable
private fun PopupTextButton(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false
) {
    Box(
        Modifier
            .width(172.dp)
            .height(48.dp)
            .background(if (selected) Color.Black else Color(0xFFF2F2F2))
            .border(0.5.dp, Color(0xFF707070))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(label, color = if (selected) Color.White else Color.Black)
    }
}

@Composable
private fun PopupIconButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(if (selected) Color(0xFFB0C4DE) else Color.White)
            .border(1.dp, if (selected) Color(0xFF516780) else Color(0xFFB0B0B0))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.Black)
    }
}

@Composable
private fun PenSettingsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    dockEdge: DockEdge,
    settings: PenSettings,
    onSettingsChange: (PenSettings) -> Unit
) {
    if (!expanded) return

    AttachedToolbarFlyout(
        title = "笔设置",
        dockEdge = dockEdge,
        onDismiss = onDismiss,
        modifier = Modifier.width(292.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrushChoice(
                        "Pen", rememberVectorPainter(Icons.Filled.Edit),
                        PenType.NormalPen, settings, onSettingsChange
                    )
                    BrushChoice(
                        "Pencil", painterResource(R.drawable.ic_ink_pen),
                        PenType.Pencil, settings, onSettingsChange
                    )
                    BrushChoice(
                        "Marker", painterResource(R.drawable.ic_ink_highlighter),
                        PenType.Marker, settings, onSettingsChange
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WIDTH_SAMPLE_DP.forEachIndexed { level, sampleWidth ->
                        WidthChoice(
                            level = level,
                            sampleWidthDp = sampleWidth,
                            selected = settings.activePreset().widthLevel == level,
                            onClick = {
                                onSettingsChange(
                                    settings.updateActivePreset { it.copy(widthLevel = level) }
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                PenPalette.colors.chunked(4).forEachIndexed { rowIndex, rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowColors.forEach { choice ->
                            val colorIndex = PenPalette.colors.indexOf(choice)
                            ColorChoice(
                                choice = choice,
                                selected = settings.activePreset().colorIndex == colorIndex,
                                onClick = {
                                    onSettingsChange(
                                        settings.updateActivePreset { it.copy(colorIndex = colorIndex) }
                                    )
                                }
                            )
                        }
                    }
                    if (rowIndex == 0) Spacer(Modifier.height(8.dp))
                }
        }
    }
}

/** Places the settings panel outside the toolbar, with a fixed physical gap. */
internal class PenPanelPositionProvider(
    private val dockEdge: DockEdge,
    private val gapPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val centeredX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val centeredY = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2
        val raw = when (dockEdge) {
            DockEdge.START -> IntOffset(anchorBounds.right + gapPx, centeredY)
            DockEdge.END -> IntOffset(anchorBounds.left - gapPx - popupContentSize.width, centeredY)
            DockEdge.TOP -> IntOffset(centeredX, anchorBounds.bottom + gapPx)
            DockEdge.BOTTOM -> IntOffset(centeredX, anchorBounds.top - gapPx - popupContentSize.height)
        }
        return IntOffset(
            x = raw.x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y = raw.y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        )
    }
}

@Composable
private fun BrushChoice(
    label: String,
    icon: Painter,
    type: PenType,
    settings: PenSettings,
    onSettingsChange: (PenSettings) -> Unit
) {
    val selected = settings.activeType == type
    Box(
        modifier = Modifier
            .width(82.dp)
            .height(56.dp)
            .background(if (selected) Color(0xFFB0C4DE) else Color(0xFFF5F5F5))
            .border(1.dp, if (selected) Color(0xFF516780) else Color(0xFFB0B0B0))
            .clickable { onSettingsChange(settings.selectType(type)) },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun WidthChoice(
    level: Int,
    sampleWidthDp: Float,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(if (selected) Color(0xFFB0C4DE) else Color(0xFFF5F5F5))
            .border(1.dp, if (selected) Color(0xFF516780) else Color(0xFFB0B0B0))
            .semantics { contentDescription = "粗细 ${level + 1}" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(34.dp)) {
            drawLine(
                color = Color.Black,
                start = Offset(3.dp.toPx(), size.height / 2f),
                end = Offset(size.width - 3.dp.toPx(), size.height / 2f),
                strokeWidth = sampleWidthDp.dp.toPx()
            )
        }
    }
}

@Composable
private fun ColorChoice(
    choice: PenColorChoice,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = when {
        selected -> Color(0xFF2B579A)
        choice.argb == 0xFFFFFFFF.toInt() -> Color(0xFF555555)
        else -> Color(0xFF9A9A9A)
    }
    Box(
        modifier = Modifier
            .size(58.dp, 42.dp)
            .background(Color(choice.argb))
            .border(if (selected) 3.dp else 1.dp, borderColor)
            .semantics { contentDescription = choice.label }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            val checkColor = if (choice.argb == 0xFFFFFFFF.toInt() ||
                choice.argb == 0xFFEDED05.toInt()
            ) Color.Black else Color.White
            Icon(Icons.Filled.Check, contentDescription = null, tint = checkColor)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    selected: Boolean,
    onFunctionClick: (() -> Unit)? = null,
    onSide2Click: (() -> Unit)? = null,
    onSide3Click: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit
) = SquarePainterButton(
    icon = rememberVectorPainter(icon),
    contentDescription = contentDescription,
    enabled = enabled,
    selected = selected,
    onFunctionClick = onFunctionClick,
    onSide2Click = onSide2Click,
    onSide3Click = onSide3Click,
    onDoubleClick = onDoubleClick,
    onClick = onClick
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun SquarePainterButton(
    icon: Painter,
    contentDescription: String,
    enabled: Boolean,
    selected: Boolean,
    onFunctionClick: (() -> Unit)? = null,
    onSide2Click: (() -> Unit)? = null,
    onSide3Click: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val stylusClick = remember { StylusClickResolver() }
    val background = if (selected) Color.Black else Color.Transparent
    val tint = when {
        !enabled -> Color.Gray
        selected -> Color.White
        else -> Color.Black
    }
    val resolvedClick = {
        when (stylusClick.consume()) {
            PenSideButton.SIDE_1 -> (onFunctionClick ?: onClick).invoke()
            PenSideButton.SIDE_2 -> (onSide2Click ?: onClick).invoke()
            PenSideButton.SIDE_3 -> (onSide3Click ?: onClick).invoke()
            PenSideButton.NONE -> onClick()
        }
    }
    val gestures = if (onDoubleClick != null) {
        Modifier.combinedClickable(
            enabled = enabled,
            onClick = resolvedClick,
            onDoubleClick = onDoubleClick
        )
    } else {
        Modifier.clickable(enabled = enabled, onClick = resolvedClick)
    }
    val functionKeyDetector = if (
        onFunctionClick != null || onSide2Click != null || onSide3Click != null
    ) {
        @Suppress("DEPRECATION")
        Modifier.pointerInteropFilter { event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                stylusClick.observe(event)
                PenButtonTracker.observeMotion(event, "pen-icon")
            }
            false
        }
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(background, RectangleShape)
            .then(functionKeyDetector)
            .then(gestures),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}

@Composable
private fun ToolbarDragHandle(
    dockEdge: DockEdge,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(44.dp)
            .pointerInput(dockEdge) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount)
                    }
                )
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.OpenWith, contentDescription = "拖动工具栏；点击隐藏", tint = Color.Black)
    }
}

@Composable
private fun HiddenToolbarHandle(edge: DockEdge, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = when (edge) {
        DockEdge.START -> Alignment.CenterStart
        DockEdge.END -> Alignment.CenterEnd
        DockEdge.TOP -> Alignment.TopCenter
        DockEdge.BOTTOM -> Alignment.BottomCenter
    }) {
        Box(
            Modifier
                .size(
                    width = if (edge == DockEdge.START || edge == DockEdge.END) 12.dp else 40.dp,
                    height = if (edge == DockEdge.TOP || edge == DockEdge.BOTTOM) 12.dp else 40.dp
                )
                .background(Color.Black)
        )
    }
}

internal fun toolbarPosition(
    container: IntSize,
    toolbar: IntSize,
    edge: DockEdge,
    fraction: Float,
    margin: Int
): IntOffset {
    val availableX = (container.width - toolbar.width - margin * 2).coerceAtLeast(0)
    val availableY = (container.height - toolbar.height - margin * 2).coerceAtLeast(0)
    val alongX = margin + (availableX * fraction.coerceIn(0f, 1f)).toInt()
    val alongY = margin + (availableY * fraction.coerceIn(0f, 1f)).toInt()
    return when (edge) {
        DockEdge.START -> IntOffset(margin, alongY)
        DockEdge.END -> IntOffset((container.width - toolbar.width - margin).coerceAtLeast(0), alongY)
        DockEdge.TOP -> IntOffset(alongX, margin)
        DockEdge.BOTTOM -> IntOffset(alongX, (container.height - toolbar.height - margin).coerceAtLeast(0))
    }
}

/**
 * Chooses the edge the toolbar bounds are physically closest to. Top/bottom are
 * deliberately checked first so a toolbar released exactly in a corner becomes
 * horizontal instead of remaining vertical.
 */
internal fun nearestDockEdge(
    container: IntSize,
    toolbar: IntSize,
    topLeft: Offset
): DockEdge = listOf(
    DockEdge.TOP to topLeft.y.coerceAtLeast(0f),
    DockEdge.BOTTOM to (container.height - topLeft.y - toolbar.height).coerceAtLeast(0f),
    DockEdge.START to topLeft.x.coerceAtLeast(0f),
    DockEdge.END to (container.width - topLeft.x - toolbar.width).coerceAtLeast(0f)
).minBy { it.second }.first

private fun alignmentFor(edge: DockEdge): Alignment = when (edge) {
    DockEdge.TOP -> Alignment.TopCenter
    DockEdge.BOTTOM -> Alignment.BottomCenter
    DockEdge.START -> Alignment.CenterStart
    DockEdge.END -> Alignment.CenterEnd
}
