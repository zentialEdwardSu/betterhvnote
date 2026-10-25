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
import com.betterhv.transfer.core.ContentKind

/** Which edge of the screen the toolbar is currently docked to. */
enum class DockEdge { TOP, END, BOTTOM, START }

private val WIDTH_SAMPLE_DP = listOf(1.5f, 3f, 5f, 8f, 12f)

data class EditorToolbarState(
  val dockEdge: DockEdge,
  val dockFraction: Float,
  val toolbarHidden: Boolean,
  val visibleItems: Set<ToolbarItem>,
  val hasPdf: Boolean,
  val eraserMode: EraserMode,
  val penToolbarSettings: PenToolbarSettings,
  val menu: ToolbarMenuRequest?,
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
      val handleSize = with(LocalDensity.current) { 44.dp.roundToPx() }
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
      Surface(
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
        shape = RectangleShape,
        color = Color(0xFFEFEFEF),
        contentColor = Color.Black,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
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
  val entries = CommandRegistry.toolbar.filter { descriptor ->
    descriptor.item != ToolbarItem.NAVIGATION || state.hasPdf
  }
  val buttons: @Composable () -> Unit = {
    ToolbarDragHandle(
      dockEdge = state.dockEdge,
      onDragStart = { actions.onMenuDismiss(); onHandleDragStart() },
      onDrag = onHandleDrag,
      onDragEnd = onHandleDragEnd,
      onClick = { actions.onMenuDismiss(); onHandleClick() },
    )
    entries.filter { it.item == ToolbarItem.MENU || it.item in state.visibleItems }.forEach { descriptor ->
      val binding = descriptor.click
      val perform: (CommandBinding?) -> Unit = { command -> command?.let { dispatcher.dispatch(it) } }
      Box {
        val penSlot = descriptor.item.ordinal.takeIf { it < PenToolbarSettings.SLOT_COUNT }
        if (penSlot != null) {
          SquarePainterButton(
            icon = penTypeIcon(state.penToolbarSettings.settingsFor(penSlot).activeType),
            contentDescription = CommandRegistry.title(binding),
            enabled = dispatcher.enabled(binding), selected = dispatcher.selected(binding),
            onClick = { perform(binding) }, onFunctionClick = { perform(descriptor.side1) },
            onDoubleClick = { perform(descriptor.doubleClick) },
          )
        } else {
          SquareIconButton(
            icon = toolbarIcon(descriptor.item, state.eraserMode),
            contentDescription = if (descriptor.item == ToolbarItem.TAIL_ERASER) {
              if (state.eraserMode == EraserMode.POINT) "局部擦除 · 点击切换" else "整笔擦除 · 点击切换"
            } else if (descriptor.item == ToolbarItem.MENU) {
              "Settings: tap to open; Side1 opens quick panel"
            } else dispatcher.title(binding),
            enabled = dispatcher.enabled(binding), selected = dispatcher.selected(binding),
            onClick = { perform(binding) }, onFunctionClick = { perform(descriptor.side1) },
            onSide2Click = { perform(descriptor.side2) }, onSide3Click = { perform(descriptor.side3) },
          )
        }
        val menu = state.menu
        val attached = when (menu?.id) {
          "pen" -> penSlot == menu.slot
          "insert" -> descriptor.item == ToolbarItem.INSERT
          "navigation" -> descriptor.item == ToolbarItem.NAVIGATION
          "menu" -> descriptor.item == ToolbarItem.MENU
          else -> false
        }
        // Hidden tools anchor their menus to Menu.
        val hiddenAnchor = descriptor.item == ToolbarItem.MENU && when (menu?.id) {
          "pen" -> ToolbarItem.entries[menu.slot] !in state.visibleItems
          "insert" -> ToolbarItem.INSERT !in state.visibleItems
          "navigation" -> ToolbarItem.NAVIGATION !in state.visibleItems
          else -> false
        }
        if (menu != null && (attached || hiddenAnchor)) {
          if (menu.id == "pen") {
            PenSettingsMenu(true, actions.onMenuDismiss, state.dockEdge,
              state.penToolbarSettings.settingsFor(menu.slot), menu.slot, dispatcher::dispatch)
          } else {
            val menuBindings = buildList {
              if (menu.id == "menu") addAll(entries.filter {
                it.item != ToolbarItem.MENU && it.item !in state.visibleItems
              }.map { it.click })
              addAll(CommandRegistry.menus[menu.id].orEmpty().map(::CommandBinding))
            }
            AttachedToolbarFlyout("菜单", state.dockEdge, actions.onMenuDismiss) {
              Column(Modifier.verticalScroll(rememberScrollState())) {
                menuBindings.forEach { command ->
                  androidx.compose.material3.TextButton(
                    onClick = { dispatcher.dispatch(command) }, enabled = dispatcher.enabled(command),
                    modifier = Modifier.semantics { contentDescription = dispatcher.title(command) },
                  ) { Text(dispatcher.title(command)) }
                }
              }
            }
          }
        }
      }
    }
  }
  if (state.dockEdge == DockEdge.TOP || state.dockEdge == DockEdge.BOTTOM) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { buttons() }
  } else {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { buttons() }
  }
}

private fun toolbarIcon(item: ToolbarItem, mode: EraserMode): ImageVector = when (item) {
  ToolbarItem.TAIL_ERASER -> if (mode == EraserMode.POINT) Icons.Filled.RemoveCircleOutline else Icons.Filled.Clear
  ToolbarItem.LASSO -> Icons.Filled.CropFree
  ToolbarItem.NAVIGATION -> Icons.Filled.Navigation
  ToolbarItem.INSERT -> Icons.Filled.PostAdd
  ToolbarItem.UNDO -> Icons.AutoMirrored.Filled.Undo
  ToolbarItem.REDO -> Icons.AutoMirrored.Filled.Redo
  ToolbarItem.DELETE -> Icons.Filled.Delete
  ToolbarItem.PAGES -> Icons.Filled.Layers
  ToolbarItem.NOTEBOOKS -> NotebookBookIcon
  ToolbarItem.EXPORT -> Icons.Filled.IosShare
  else -> Icons.Filled.Menu
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
    ToolbarFlyoutSurface(
      title = title,
      dockEdge = dockEdge,
      modifier = modifier,
      content = content,
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
  content: @Composable () -> Unit,
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
    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Black),
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
          contentAlignment = Alignment.CenterStart,
        ) {
          Text(
            title,
            color = Color.Black,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
          )
        }
      }
      Box(Modifier.background(Color(0xFFF2F2F2))) { content() }
    }
  }
}

@Composable
private fun PopupTextButton(label: String, onClick: () -> Unit, selected: Boolean = false) {
  Box(
    Modifier
      .width(172.dp)
      .height(48.dp)
      .background(if (selected) Color.Black else Color(0xFFF2F2F2))
      .border(0.5.dp, Color(0xFF707070))
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    Text(label, color = if (selected) Color.White else Color.Black)
  }
}

@Composable
private fun PopupIconButton(
  icon: ImageVector,
  contentDescription: String,
  selected: Boolean = false,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(48.dp)
      .background(if (selected) Color(0xFFB0C4DE) else Color.White)
      .border(1.dp, if (selected) Color(0xFF516780) else Color(0xFFB0B0B0))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
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
  Box(
    modifier = Modifier
      .width(82.dp)
      .height(56.dp)
      .background(if (selected) Color(0xFFB0C4DE) else Color(0xFFF5F5F5))
      .border(1.dp, if (selected) Color(0xFF516780) else Color(0xFFB0B0B0))
      .clickable { onSettingsChange(settings.selectType(type)) },
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = label)
  }
}

@Composable
private fun WidthChoice(level: Int, sampleWidthDp: Float, selected: Boolean, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .size(46.dp)
      .background(if (selected) Color(0xFFB0C4DE) else Color(0xFFF5F5F5))
      .border(1.dp, if (selected) Color(0xFF516780) else Color(0xFFB0B0B0))
      .semantics { contentDescription = noteText("粗细 ${level + 1}", "Width ${level + 1}") }
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(Modifier.size(34.dp)) {
      drawLine(
        color = Color.Black,
        start = Offset(3.dp.toPx(), size.height / 2f),
        end = Offset(size.width - 3.dp.toPx(), size.height / 2f),
        strokeWidth = sampleWidthDp.dp.toPx(),
      )
    }
  }
}

@Composable
private fun ColorChoice(choice: PenColorChoice, selected: Boolean, onClick: () -> Unit) {
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
      .semantics { contentDescription = choice.localizedLabel() }
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    if (selected) {
      val checkColor = if (choice.argb == 0xFFFFFFFF.toInt() ||
        choice.argb == 0xFFEDED05.toInt()
      ) {
        Color.Black
      } else {
        Color.White
      }
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
  onClick: () -> Unit,
) = SquarePainterButton(
  icon = rememberVectorPainter(icon),
  contentDescription = contentDescription,
  enabled = enabled,
  selected = selected,
  onFunctionClick = onFunctionClick,
  onSide2Click = onSide2Click,
  onSide3Click = onSide3Click,
  onDoubleClick = onDoubleClick,
  onClick = onClick,
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
  onClick: () -> Unit,
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
      onDoubleClick = onDoubleClick,
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
    contentAlignment = Alignment.Center,
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
  onClick: () -> Unit,
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
          },
        )
      }
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      Icons.Filled.OpenWith,
      contentDescription = noteText("拖动工具栏；点击隐藏", "Drag toolbar; tap to hide"),
      tint = Color.Black,
    )
  }
}

@Composable
private fun HiddenToolbarHandle(edge: DockEdge, modifier: Modifier, onClick: () -> Unit) {
  Box(
    modifier.size(44.dp).clickable(onClick = onClick),
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
        .background(Color.Black),
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
