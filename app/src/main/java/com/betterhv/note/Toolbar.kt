package com.betterhv.note

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import android.view.MotionEvent
import com.betterhv.note.ink.PenType

/** Which edge of the screen the toolbar is currently docked to. */
enum class DockEdge { TOP, END, BOTTOM, START }

private val DOCK_CYCLE = listOf(DockEdge.START, DockEdge.TOP, DockEdge.END, DockEdge.BOTTOM)
private val WIDTH_SAMPLE_DP = listOf(1.5f, 3f, 5f, 8f, 12f)

/** Dockable editing toolbar with a pen-settings popup anchored to the pen button. */
@Composable
fun EditorToolbar(
    modifier: Modifier = Modifier,
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
    penToolbarSettings: PenToolbarSettings,
    onPenSlotSelected: (Int) -> Unit,
    onPenSettingsChange: (Int, PenSettings) -> Unit,
    onPenPanelVisibilityChange: (Boolean) -> Unit,
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit
) {
    var dockEdge by rememberSaveable { mutableStateOf(DockEdge.START) }

    Box(modifier) {
        Box(
            modifier = Modifier
                .align(alignmentFor(dockEdge))
                .padding(8.dp)
        ) {
            Surface(
                shape = RectangleShape,
                color = Color(0xFFEFEFEF),
                contentColor = Color.Black,
                tonalElevation = 4.dp
            ) {
                ToolbarContent(
                    dockEdge = dockEdge,
                    onReposition = {
                        dockEdge = DOCK_CYCLE[(DOCK_CYCLE.indexOf(dockEdge) + 1) % DOCK_CYCLE.size]
                    },
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
                    penToolbarSettings = penToolbarSettings,
                    onPenSlotSelected = onPenSlotSelected,
                    onPenSettingsChange = onPenSettingsChange,
                    onPenPanelVisibilityChange = onPenPanelVisibilityChange,
                    debugMode = debugMode,
                    onDebugModeChange = onDebugModeChange
                )
            }
        }
    }
}

@Composable
private fun ToolbarContent(
    dockEdge: DockEdge,
    onReposition: () -> Unit,
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
    penToolbarSettings: PenToolbarSettings,
    onPenSlotSelected: (Int) -> Unit,
    onPenSettingsChange: (Int, PenSettings) -> Unit,
    onPenPanelVisibilityChange: (Boolean) -> Unit,
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var editingPenSlot by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(editingPenSlot) {
        onPenPanelVisibilityChange(editingPenSlot != null)
    }

    val buttons: @Composable () -> Unit = {
        SquareIconButton(
            Icons.Filled.OpenWith,
            "Reposition toolbar",
            enabled = true,
            selected = false,
            onClick = onReposition
        )
        repeat(PenToolbarSettings.SLOT_COUNT) { slot ->
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
                        selectSlot()
                        editingPenSlot = slot
                    },
                    onDoubleClick = {
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
        SquareIconButton(
            eraserIcon,
            eraserDesc,
            enabled = true,
            selected = false,
            onClick = onEraserModeToggle
        )
        SquareIconButton(
            Icons.Filled.CropFree,
            "Lasso select",
            enabled = true,
            selected = toolKind == ToolKind.LASSO
        ) { onToolSelected(ToolKind.LASSO) }
        SquareIconButton(
            Icons.AutoMirrored.Filled.Undo,
            "Undo",
            enabled = canUndo,
            selected = false,
            onClick = onUndo
        )
        SquareIconButton(
            Icons.AutoMirrored.Filled.Redo,
            "Redo",
            enabled = canRedo,
            selected = false,
            onClick = onRedo
        )
        SquareIconButton(
            Icons.Filled.Delete,
            "Delete selection",
            enabled = hasSelection,
            selected = false,
            onClick = onDelete
        )
        Box {
            SquareIconButton(Icons.Filled.Menu, "More", enabled = true, selected = menuExpanded) {
                menuExpanded = true
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                Row(
                    modifier = Modifier
                        .clickable { onDebugModeChange(!debugMode) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.BugReport, contentDescription = null)
                    Text("Debug mode", modifier = Modifier.padding(horizontal = 8.dp))
                    Switch(checked = debugMode, onCheckedChange = onDebugModeChange)
                }
            }
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
private fun PenSettingsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    dockEdge: DockEdge,
    settings: PenSettings,
    onSettingsChange: (PenSettings) -> Unit
) {
    if (!expanded) return

    val gapPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val positionProvider = remember(dockEdge, gapPx) {
        PenPanelPositionProvider(dockEdge, gapPx)
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier.width(292.dp),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp
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
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit
) = SquarePainterButton(
    icon = rememberVectorPainter(icon),
    contentDescription = contentDescription,
    enabled = enabled,
    selected = selected,
    onFunctionClick = onFunctionClick,
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
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    var functionClickArmed by remember { mutableStateOf(false) }
    val background = if (selected) Color(0xFFB0C4DE) else Color.Transparent
    val tint = if (enabled) Color.Black else Color.Gray
    val resolvedClick = {
        val functionPressed = onFunctionClick != null &&
            (functionClickArmed || PenButtonTracker.consumeSideKey1Click())
        if (functionPressed) onFunctionClick?.invoke() else onClick()
        functionClickArmed = false
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
    val functionKeyDetector = if (onFunctionClick != null) {
        @Suppress("DEPRECATION")
        Modifier.pointerInteropFilter { event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                functionClickArmed = event.pointerCount > 0 &&
                    PenFunctionKey.isPressed(event.getToolType(0), event.buttonState, event.source)
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

private fun alignmentFor(edge: DockEdge): Alignment = when (edge) {
    DockEdge.TOP -> Alignment.TopCenter
    DockEdge.BOTTOM -> Alignment.BottomCenter
    DockEdge.START -> Alignment.CenterStart
    DockEdge.END -> Alignment.CenterEnd
}
