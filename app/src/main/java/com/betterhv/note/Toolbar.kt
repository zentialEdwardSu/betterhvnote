package com.betterhv.note

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.TextFields
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.betterhv.note.ink.PenType
import com.betterhv.note.toolbar.FloatingPresentation
import com.betterhv.note.toolbar.ToolbarCatalog
import com.betterhv.note.toolbar.ToolbarFlyoutRequest
import com.betterhv.note.toolbar.ToolbarGesture
import com.betterhv.note.toolbar.ToolbarIcon
import com.betterhv.note.toolbar.ToolbarToolUiState
import com.betterhv.note.toolbar.ToolbarWindowContent
import com.betterhv.note.toolbar.ToolbarWindowSpec
import com.betterhv.note.ui.EinkActionRow
import com.betterhv.note.ui.EinkChoiceCell
import com.betterhv.note.ui.EinkColors
import com.betterhv.note.ui.EinkDimens
import com.betterhv.note.ui.EinkFlyoutSurface
import com.betterhv.note.ui.EinkTheme
import com.betterhv.note.ui.EinkToolButton
import com.betterhv.transfer.core.ContentKind
import com.betterhv.note.notebook.NotebookBookIcon
import com.betterhv.note.settings.PenColorChoice
import com.betterhv.note.settings.PenPalette
import com.betterhv.note.settings.PenProfiles
import com.betterhv.note.settings.PenSettings
import com.betterhv.note.settings.PenToolbarSettings

/** Which edge of the screen the toolbar is currently docked to. */
enum class DockEdge { TOP, END, BOTTOM, START }

private val WIDTH_SAMPLE_DP = listOf(1.5f, 3f, 5f, 8f, 12f)

data class EditorToolbarState(
  val dockEdge: DockEdge,
  val dockFraction: Float,
  val toolbarHidden: Boolean,
  val tools: List<ToolbarToolUiState>,
  val penSettings: PenToolbarSettings,
  val openFlyout: ToolbarFlyoutRequest?,
  val dispatcher: CommandDispatcher,
)

data class EditorToolbarActions(
  val onDockEdgeChange: (DockEdge) -> Unit,
  val onDockFractionChange: (Float) -> Unit,
  val onToolbarHiddenChange: (Boolean) -> Unit,
  val onMenuDismiss: () -> Unit,
  val onToolbarDragStateChange: (Boolean) -> Unit,
  val onInteractionBlockChange: (Boolean) -> Unit,
)

/** Dockable editing toolbar with a pen-settings popup anchored to the pen button. */
@Composable
fun EditorToolbar(modifier: Modifier = Modifier, state: EditorToolbarState, actions: EditorToolbarActions) {
  EinkTheme { EditorToolbarContent(modifier, state, actions) }
}

@Composable
private fun EditorToolbarContent(
  modifier: Modifier,
  state: EditorToolbarState,
  actions: EditorToolbarActions,
) {
  val dockEdge = state.dockEdge
  val dockFraction = state.dockFraction
  val toolbarHidden = state.toolbarHidden
  val onDockEdgeChange = actions.onDockEdgeChange
  val onDockFractionChange = actions.onDockFractionChange
  val onToolbarHiddenChange = actions.onToolbarHiddenChange
  val onToolbarDragStateChange = actions.onToolbarDragStateChange
  val onInteractionBlockChange = actions.onInteractionBlockChange
  var containerSize by remember { mutableStateOf(IntSize.Zero) }
  var toolbarSize by remember { mutableStateOf(IntSize.Zero) }
  var dragDelta by remember { mutableStateOf(Offset.Zero) }
  val marginPx = with(LocalDensity.current) { 8.dp.roundToPx() }
  val base = toolbarPosition(containerSize, toolbarSize, dockEdge, dockFraction, marginPx)

  fun finishDrag() {
    if (containerSize == IntSize.Zero || toolbarSize == IntSize.Zero) return
    val left = (base.x + dragDelta.x).coerceIn(
      0f,
      (containerSize.width - toolbarSize.width).coerceAtLeast(0).toFloat(),
    )
    val top = (base.y + dragDelta.y).coerceIn(
      0f,
      (containerSize.height - toolbarSize.height).coerceAtLeast(0).toFloat(),
    )
    val edge = nearestDockEdge(containerSize, toolbarSize, Offset(left, top))
    val fraction = when (edge) {
      DockEdge.START, DockEdge.END ->
        (
          (top - marginPx) /
            (containerSize.height - toolbarSize.height - marginPx * 2).coerceAtLeast(1)
          ).coerceIn(0f, 1f)

      DockEdge.TOP, DockEdge.BOTTOM ->
        (
          (left - marginPx) /
            (containerSize.width - toolbarSize.width - marginPx * 2).coerceAtLeast(1)
          ).coerceIn(0f, 1f)
    }
    dragDelta = Offset.Zero
    onDockEdgeChange(edge)
    onDockFractionChange(fraction)
  }

  Box(modifier.onSizeChanged { containerSize = it }) {
    if (toolbarHidden) {
      val handleSize = with(LocalDensity.current) { EinkDimens.Touch.roundToPx() }
      val hiddenPosition = toolbarPosition(
        containerSize,
        IntSize(handleSize, handleSize),
        dockEdge,
        dockFraction,
        0,
      )
      HiddenToolbarHandle(
        edge = dockEdge,
        modifier = Modifier.offset { hiddenPosition },
        onClick = { onToolbarHiddenChange(false) },
      )
    } else {
      com.betterhv.note.ui.EinkSurface(
        modifier = Modifier
          .offset {
            IntOffset(
              (base.x + dragDelta.x).toInt().coerceIn(
                0,
                (containerSize.width - toolbarSize.width).coerceAtLeast(0),
              ),
              (base.y + dragDelta.y).toInt().coerceIn(
                0,
                (containerSize.height - toolbarSize.height).coerceAtLeast(0),
              ),
            )
          }
          .onSizeChanged { toolbarSize = it }
          .penInputGuard(onInteractionBlockChange),
        color = EinkColors.Chrome,
      ) {
        ToolbarContent(
          state = state,
          actions = actions,
          onHandleDrag = { dragDelta += it },
          onHandleDragStart = { onToolbarDragStateChange(true) },
          onHandleDragEnd = {
            finishDrag()
            onToolbarDragStateChange(false)
          },
          onHandleClick = { onToolbarHiddenChange(true) },
        )
      }
    }
  }
}

@Composable
private fun ToolbarContent(
  state: EditorToolbarState,
  actions: EditorToolbarActions,
  onHandleDrag: (Offset) -> Unit,
  onHandleDragStart: () -> Unit,
  onHandleDragEnd: () -> Unit,
  onHandleClick: () -> Unit,
) {
  val dispatcher = state.dispatcher
  val tools = state.tools
  val buttons: @Composable () -> Unit = {
    ToolbarDragHandle(
      dockEdge = state.dockEdge,
      onDragStart = { actions.onMenuDismiss(); onHandleDragStart() },
      onDrag = onHandleDrag,
      onDragEnd = onHandleDragEnd,
      onClick = { actions.onMenuDismiss(); onHandleClick() },
    )
    tools.filter { it.visible }.forEach { tool ->
      Box {
        EinkToolButton(
          icon = toolbarIcon(tool.icon),
          contentDescription = tool.contentDescription?.localized(NoteI18n.isChinese)
            ?: dispatcher.title(tool.primary),
          enabled = tool.enabled,
          selected = tool.selected,
          onClick = { dispatcher.dispatch(tool.primary) },
          onFunctionClick = tool.gestureAction(ToolbarGesture.SIDE_1, dispatcher),
          onSide2Click = tool.gestureAction(ToolbarGesture.SIDE_2, dispatcher),
          onSide3Click = tool.gestureAction(ToolbarGesture.SIDE_3, dispatcher),
          onDoubleClick = tool.gestureAction(ToolbarGesture.DOUBLE_CLICK, dispatcher),
        )
        val request = state.openFlyout
        val spec = request?.let { ToolbarCatalog.windows[it.window] }
        if (request != null && spec != null &&
          spec.presentation == FloatingPresentation.FLYOUT &&
          anchorFor(request, tools) == tool.id
        ) {
          ToolbarWindowPopup(
            spec = spec,
            request = request,
            allTools = tools,
            penSettings = state.penSettings,
            dockEdge = state.dockEdge,
            dispatcher = dispatcher,
            onDismiss = actions.onMenuDismiss,
          )
        }
      }
    }
  }
  if (state.dockEdge == DockEdge.TOP || state.dockEdge == DockEdge.BOTTOM) {
    Row(horizontalArrangement = Arrangement.spacedBy(EinkDimens.ToolbarGap)) { buttons() }
  } else {
    Column(verticalArrangement = Arrangement.spacedBy(EinkDimens.ToolbarGap)) { buttons() }
  }
}

@Composable
private fun ToolbarWindowPopup(
  spec: ToolbarWindowSpec,
  request: ToolbarFlyoutRequest,
  allTools: List<ToolbarToolUiState>,
  penSettings: PenToolbarSettings,
  dockEdge: DockEdge,
  dispatcher: CommandDispatcher,
  onDismiss: () -> Unit,
) {
  when (val content = spec.content) {
    ToolbarWindowContent.PenSettings -> {
      val slot = request.arguments["slot"]
        ?.takeIf { it != "current" }
        ?.toIntOrNull()
        ?: penSettings.activeSlot
      PenSettingsMenu(
        true,
        onDismiss,
        dockEdge,
        penSettings.settingsFor(slot),
        slot,
        dispatcher::dispatch,
      )
    }

    is ToolbarWindowContent.Commands -> AttachedToolbarFlyout(
      spec.title.localized(NoteI18n.isChinese),
      dockEdge,
      onDismiss,
    ) {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        content.entries.forEach { ToolbarCommandButton(it, dispatcher) }
      }
    }

    is ToolbarWindowContent.Menu -> {
      val hidden = allTools.filter { it.menuEligible && !it.visible }
      AttachedToolbarFlyout(
        spec.title.localized(NoteI18n.isChinese),
        dockEdge,
        onDismiss,
      ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          hidden.forEach { ToolbarCommandButton(it.primary, dispatcher) }
          content.entries.forEach { ToolbarCommandButton(it, dispatcher) }
        }
      }
    }

    ToolbarWindowContent.PageManager -> Unit
  }
}

@Composable
private fun ToolbarCommandButton(binding: CommandBinding, dispatcher: CommandDispatcher) {
  EinkActionRow(
    label = dispatcher.title(binding),
    enabled = dispatcher.enabled(binding),
    onClick = { dispatcher.dispatch(binding) },
  )
}

private fun ToolbarToolUiState.gestureAction(
  gesture: ToolbarGesture,
  dispatcher: CommandDispatcher,
): (() -> Unit)? = gestures[gesture]?.let { binding -> { dispatcher.dispatch(binding) } }

private fun anchorFor(
  request: ToolbarFlyoutRequest,
  tools: List<ToolbarToolUiState>,
): ToolbarItem =
  if (tools.firstOrNull { it.id == request.anchor }?.visible == true) request.anchor
  else ToolbarItem.MENU

@Composable
private fun toolbarIcon(icon: ToolbarIcon): Painter = when (icon) {
  is ToolbarIcon.Static -> when (icon.id) {
    ToolbarIcon.StaticId.TAIL_ERASER -> rememberVectorPainter(Icons.Filled.Clear)
    ToolbarIcon.StaticId.LASSO -> rememberVectorPainter(Icons.Filled.CropFree)
    ToolbarIcon.StaticId.NAVIGATION -> rememberVectorPainter(Icons.Filled.Navigation)
    ToolbarIcon.StaticId.INSERT -> rememberVectorPainter(Icons.Filled.PostAdd)
    ToolbarIcon.StaticId.UNDO -> rememberVectorPainter(Icons.AutoMirrored.Filled.Undo)
    ToolbarIcon.StaticId.REDO -> rememberVectorPainter(Icons.AutoMirrored.Filled.Redo)
    ToolbarIcon.StaticId.DELETE -> rememberVectorPainter(Icons.Filled.Delete)
    ToolbarIcon.StaticId.PAGES -> rememberVectorPainter(Icons.Filled.Layers)
    ToolbarIcon.StaticId.NOTEBOOKS -> rememberVectorPainter(NotebookBookIcon)
    ToolbarIcon.StaticId.EXPORT -> rememberVectorPainter(Icons.Filled.IosShare)
    ToolbarIcon.StaticId.MENU -> rememberVectorPainter(Icons.Filled.Menu)
  }
  is ToolbarIcon.Pen -> penTypeIcon(icon.type)
  is ToolbarIcon.Eraser ->
    rememberVectorPainter(
      if (icon.mode == EraserMode.POINT) Icons.Filled.RemoveCircleOutline else Icons.Filled.Clear,
    )
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
private fun AttachedToolbarFlyout(
  title: String,
  dockEdge: DockEdge,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
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
      dismissOnClickOutside = true,
    ),
  ) {
    EinkTheme {
      ToolbarFlyoutSurface(
        title = title,
        dockEdge = dockEdge,
        modifier = modifier,
        content = content,
      )
    }
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
  content: @Composable () -> Unit,
) {
  EinkFlyoutSurface(
    title = title,
    dockEdge = dockEdge,
    modifier = modifier,
    compactHeaderWidth = compactHeaderWidth,
    wrapContentWidth = wrapContentWidth,
    content = content,
  )
}

@Composable
private fun PenSettingsMenu(
  expanded: Boolean,
  onDismiss: () -> Unit,
  dockEdge: DockEdge,
  settings: PenSettings,
  slot: Int,
  dispatch: (CommandBinding) -> Boolean,
) {
  fun send(id: String, value: String) { dispatch(CommandBinding(id, mapOf("slot" to "$slot", "value" to value))) }
  if (!expanded) return

  AttachedToolbarFlyout(
    title = noteText("笔设置", "Pen settings"),
    dockEdge = dockEdge,
    onDismiss = onDismiss,
    modifier = Modifier.width(292.dp),
  ) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      Spacer(Modifier.height(6.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BrushChoice(
          "Pen",
          rememberVectorPainter(Icons.Filled.Edit),
          PenType.NormalPen,
          settings,
          { send("pen.type", PenProfiles.stableId(it.activeType)) },
        )
        BrushChoice(
          "Pencil",
          painterResource(R.drawable.ic_ink_pen),
          PenType.Pencil,
          settings,
          { send("pen.type", PenProfiles.stableId(it.activeType)) },
        )
        BrushChoice(
          "Marker",
          painterResource(R.drawable.ic_ink_highlighter),
          PenType.Marker,
          settings,
          { send("pen.type", PenProfiles.stableId(it.activeType)) },
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
              send("pen.width", "$level")
            },
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
                send("pen.color", choice.id)
              },
            )
          }
        }
        if (rowIndex == 0) Spacer(Modifier.height(8.dp))
      }
    }
  }
}

/** Places the settings panel outside the toolbar, with a fixed physical gap. */
internal class PenPanelPositionProvider(private val dockEdge: DockEdge, private val gapPx: Int) :
  PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
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
      y = raw.y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
  }
}

@Composable
private fun BrushChoice(
  label: String,
  icon: Painter,
  type: PenType,
  settings: PenSettings,
  onSettingsChange: (PenSettings) -> Unit,
) {
  val selected = settings.activeType == type
  EinkChoiceCell(
    modifier = Modifier.width(82.dp).height(56.dp),
    selected = selected,
    contentDescription = label,
    onClick = { onSettingsChange(settings.selectType(type)) },
  ) {
    Icon(icon, contentDescription = null)
  }
}

@Composable
private fun WidthChoice(level: Int, sampleWidthDp: Float, selected: Boolean, onClick: () -> Unit) {
  EinkChoiceCell(
    modifier = Modifier.size(46.dp),
    selected = selected,
    contentDescription = noteText("粗细 ${level + 1}", "Width ${level + 1}"),
    onClick = onClick,
  ) {
    Canvas(Modifier.size(34.dp)) {
      drawLine(
        color = if (selected) EinkColors.Paper else EinkColors.Ink,
        start = Offset(3.dp.toPx(), size.height / 2f),
        end = Offset(size.width - 3.dp.toPx(), size.height / 2f),
        strokeWidth = sampleWidthDp.dp.toPx(),
      )
    }
  }
}

@Composable
private fun ColorChoice(choice: PenColorChoice, selected: Boolean, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .size(58.dp, EinkDimens.Touch)
      .background(Color(choice.argb))
      .border(
        if (selected) EinkDimens.BorderEmphasis else EinkDimens.Hairline,
        EinkColors.Ink,
        RectangleShape,
      )
      .semantics { contentDescription = choice.localizedLabel() }
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    if (selected) {
      val checkColor = if (choice.argb == 0xFFFFFFFF.toInt() ||
        choice.argb == 0xFFEDED05.toInt()
      ) {
        EinkColors.Ink
      } else {
        EinkColors.Paper
      }
      Icon(Icons.Filled.Check, contentDescription = null, tint = checkColor)
    }
  }
}

@Composable
private fun ToolbarDragHandle(
  dockEdge: DockEdge,
  onDragStart: () -> Unit,
  onDrag: (Offset) -> Unit,
  onDragEnd: () -> Unit,
  onClick: () -> Unit,
) {
  Box(
    Modifier
      .size(EinkDimens.Touch)
      .pointerInput(dockEdge) {
        detectDragGestures(
          onDragStart = { onDragStart() },
          onDragEnd = onDragEnd,
          onDragCancel = onDragEnd,
          onDrag = { change, amount ->
            change.consume()
            onDrag(amount)
          },
        )
      }
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      Icons.Filled.OpenWith,
      contentDescription = noteText("拖动工具栏；点击隐藏", "Drag toolbar; tap to hide"),
      tint = EinkColors.Ink,
    )
  }
}

@Composable
private fun HiddenToolbarHandle(edge: DockEdge, modifier: Modifier, onClick: () -> Unit) {
  Box(
    modifier.size(EinkDimens.Touch).clickable(onClick = onClick),
    contentAlignment = when (edge) {
      DockEdge.START -> Alignment.CenterStart
      DockEdge.END -> Alignment.CenterEnd
      DockEdge.TOP -> Alignment.TopCenter
      DockEdge.BOTTOM -> Alignment.BottomCenter
    },
  ) {
    Box(
      Modifier
        .size(
          width = if (edge == DockEdge.START || edge == DockEdge.END) 12.dp else 40.dp,
          height = if (edge == DockEdge.TOP || edge == DockEdge.BOTTOM) 12.dp else 40.dp,
        )
        .background(EinkColors.Ink),
    )
  }
}

internal fun toolbarPosition(
  container: IntSize,
  toolbar: IntSize,
  edge: DockEdge,
  fraction: Float,
  margin: Int,
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
internal fun nearestDockEdge(container: IntSize, toolbar: IntSize, topLeft: Offset): DockEdge = listOf(
  DockEdge.TOP to topLeft.y.coerceAtLeast(0f),
  DockEdge.BOTTOM to (container.height - topLeft.y - toolbar.height).coerceAtLeast(0f),
  DockEdge.START to topLeft.x.coerceAtLeast(0f),
  DockEdge.END to (container.width - topLeft.x - toolbar.width).coerceAtLeast(0f),
).minBy { it.second }.first
