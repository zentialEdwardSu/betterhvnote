package com.betterhv.note.toolbar

import com.betterhv.note.CommandContext
import com.betterhv.note.EraserMode
import com.betterhv.note.PenToolbarSettings
import com.betterhv.note.ShortcutScene
import com.betterhv.note.ToolKind
import com.betterhv.note.ToolbarItem

/** Stable metadata shared by every concrete toolbar tool. */
data class ToolbarToolMeta(
  val id: ToolbarItem,
  val label: ToolbarText,
  val commandLabel: String,
  val category: String,
  val scenes: Set<ShortcutScene>,
  val defaultVisible: Boolean,
)

/**
 * One object per toolbar button. The object owns identity, command metadata,
 * primary and alternate gestures, availability and presentation. It never
 * performs a side effect: [primaryEffects] returns pure requests that the app
 * layer interprets.
 */
sealed class ToolbarTool(val meta: ToolbarToolMeta) {
  val id: ToolbarItem get() = meta.id
  val label: ToolbarText get() = meta.label
  val commandLabel: String get() = meta.commandLabel
  val category: String get() = meta.category
  val scenes: Set<ShortcutScene> get() = meta.scenes
  val defaultVisible: Boolean get() = meta.defaultVisible

  abstract fun primaryEffects(arguments: Map<String, String>): List<ToolbarEffect>

  open val gestures: Map<ToolbarGesture, ToolbarActionRef> = emptyMap()

  open fun isAvailable(context: CommandContext): Boolean = true

  /** Whether the tool can appear at all in the current document context. */
  open fun isEligible(state: ToolbarUiState): Boolean = true

  /** Whether the tool is rendered on the toolbar. Hidden tools move into Menu. */
  open fun isVisibleInToolbar(state: ToolbarUiState): Boolean =
    id == ToolbarItem.MENU || (isEligible(state) && id in state.visibleItems)

  open fun isSelected(state: ToolbarUiState): Boolean = false

  open fun icon(state: ToolbarUiState): ToolbarIcon =
    ToolbarIcon.Static(requireNotNull(staticIconId) { "No icon declared for $id" })

  /** Optional override; the UI falls back to the command title when null. */
  open fun contentDescription(state: ToolbarUiState): ToolbarText? = null

  open fun title(state: ToolbarUiState): ToolbarText =
    ToolbarText(commandLabel, commandLabel)

  protected open val staticIconId: ToolbarIcon.StaticId? = null
}

sealed class CanvasTool(meta: ToolbarToolMeta) : ToolbarTool(meta)

sealed class FloatingWindowTool(meta: ToolbarToolMeta) : ToolbarTool(meta)

sealed class FullScreenTool(meta: ToolbarToolMeta) : ToolbarTool(meta)

sealed class ImmediateActionTool(meta: ToolbarToolMeta) : ToolbarTool(meta)

private val editorScenes: Set<ShortcutScene> = setOf(ShortcutScene.EDITOR)
private val insertScenes: Set<ShortcutScene> = editorScenes + ShortcutScene.INSERT
private val pageScenes: Set<ShortcutScene> = editorScenes + ShortcutScene.PAGE_MANAGER

private fun penItem(slot: Int): ToolbarItem = when (slot) {
  0 -> ToolbarItem.PEN_1
  1 -> ToolbarItem.PEN_2
  2 -> ToolbarItem.PEN_3
  else -> error("Invalid pen slot: $slot")
}

private fun penSettingsRef(slot: Int): ToolbarActionRef =
  ToolbarActionRef("pen.settings", mapOf("slot" to "$slot"))

private fun penMeta(slot: Int): ToolbarToolMeta = ToolbarToolMeta(
  id = penItem(slot),
  label = ToolbarText("笔 ${slot + 1}", "Pen ${slot + 1}"),
  commandLabel = "笔 ${slot + 1}",
  category = "画笔",
  scenes = editorScenes,
  defaultVisible = true,
)

class PenSlotTool(val slot: Int) : CanvasTool(penMeta(slot)) {
  init {
    require(slot in 0 until PenToolbarSettings.SLOT_COUNT) { "Invalid pen slot: $slot" }
  }

  override fun primaryEffects(arguments: Map<String, String>): List<ToolbarEffect> = listOf(
    ToolbarEffect.SelectPenSlot(slot),
    ToolbarEffect.SetCanvasTool(ToolKind.PEN),
  )

  override val gestures: Map<ToolbarGesture, ToolbarActionRef> = mapOf(
    ToolbarGesture.DOUBLE_CLICK to penSettingsRef(slot),
    ToolbarGesture.SIDE_1 to penSettingsRef(slot),
  )

  override fun isSelected(state: ToolbarUiState): Boolean =
    state.toolKind == ToolKind.PEN && state.penSettings.activeSlot == slot

  override fun icon(state: ToolbarUiState): ToolbarIcon =
    ToolbarIcon.Pen(state.penSettings.settingsFor(slot).activeType)
}

object TailEraserTool : CanvasTool(
  ToolbarToolMeta(
    id = ToolbarItem.TAIL_ERASER,
    label = ToolbarText("笔尾橡皮模式", "Tail eraser mode"),
    commandLabel = "切换橡皮模式",
    category = "橡皮",
    scenes = editorScenes,
    defaultVisible = true,
  ),
) {
  override val staticIconId: ToolbarIcon.StaticId = ToolbarIcon.StaticId.TAIL_ERASER

  override fun primaryEffects(arguments: Map<String, String>): List<ToolbarEffect> =
    listOf(ToolbarEffect.ToggleEraserMode)

  override fun icon(state: ToolbarUiState): ToolbarIcon =
    ToolbarIcon.Eraser(state.eraserMode)

  override fun contentDescription(state: ToolbarUiState): ToolbarText = title(state)

  override fun title(state: ToolbarUiState): ToolbarText =
    if (state.eraserMode == EraserMode.POINT) {
      ToolbarText("局部擦除 · 点击切换", "局部擦除 · 点击切换")
    } else {
      ToolbarText("整笔擦除 · 点击切换", "整笔擦除 · 点击切换")
    }
}

object LassoTool : CanvasTool(
  ToolbarToolMeta(
    id = ToolbarItem.LASSO,
    label = ToolbarText("套索", "Lasso"),
    commandLabel = "套索",
    category = "编辑",
    scenes = editorScenes,
    defaultVisible = true,
  ),
) {
  override val staticIconId: ToolbarIcon.StaticId = ToolbarIcon.StaticId.LASSO

  override fun primaryEffects(arguments: Map<String, String>): List<ToolbarEffect> =
    listOf(ToolbarEffect.SetCanvasTool(ToolKind.LASSO))

  override fun isSelected(state: ToolbarUiState): Boolean = state.toolKind == ToolKind.LASSO
}

object PdfNavigationTool : CanvasTool(
  ToolbarToolMeta(
    id = ToolbarItem.NAVIGATION,
    label = ToolbarText("PDF 导航", "PDF navigation"),
    commandLabel = "PDF 导航",
    category = "PDF 导航",
    scenes = editorScenes,
    defaultVisible = true,
  ),
) {
  override val staticIconId: ToolbarIcon.StaticId = ToolbarIcon.StaticId.NAVIGATION

  override fun primaryEffects(arguments: Map<String, String>): List<ToolbarEffect> = listOf(
    ToolbarEffect.SetCanvasTool(ToolKind.NAVIGATION),
    ToolbarEffect.ShowFloatingWindow(
      ToolbarWindowId.PDF_NAVIGATION,
      ToolbarItem.NAVIGATION,
    ),
  )

  override fun isAvailable(context: CommandContext): Boolean = context.hasPdf

  override fun isEligible(state: ToolbarUiState): Boolean = state.commandContext.hasPdf

  override fun isSelected(state: ToolbarUiState): Boolean = state.toolKind == ToolKind.NAVIGATION
}

object InsertTool : FloatingWindowTool(
  ToolbarToolMeta(
    id = ToolbarItem.INSERT,
    label = ToolbarText("插入", "Insert"),
    commandLabel = "插入图片或文字",
    category = "插入",
    scenes = insertScenes,
    defaultVisible = true,
  ),
) {
  override val staticIconId: ToolbarIcon.StaticId = ToolbarIcon.StaticId.INSERT

  override fun primaryEffects(arguments: Map<String, String>): List<ToolbarEffect> =
    listOf(ToolbarEffect.ShowFloatingWindow(ToolbarWindowId.INSERT, ToolbarItem.INSERT))

  override fun isSelected(state: ToolbarUiState): Boolean =
    state.openFlyout?.window == ToolbarWindowId.INSERT
}

object MenuTool : FloatingWindowTool(
  ToolbarToolMeta(
    id = ToolbarItem.MENU,
    label = ToolbarText("Menu", "Menu"),
    commandLabel = "打开菜单",
    category = "菜单",
    scenes = editorScenes,
    defaultVisible = false,
  ),
) {
  override val staticIconId: ToolbarIcon.StaticId = ToolbarIcon.StaticId.MENU

  override fun primaryEffects(arguments: Map<String, String>): List<ToolbarEffect> =
    listOf(ToolbarEffect.ShowFloatingWindow(ToolbarWindowId.MENU, ToolbarItem.MENU))

  override fun isSelected(state: ToolbarUiState): Boolean =
    state.openFlyout?.window == ToolbarWindowId.MENU

  override fun contentDescription(state: ToolbarUiState): ToolbarText =
    ToolbarText(
      "Settings: tap to open; Side1 opens quick panel",
      "Settings: tap to open; Side1 opens quick panel",
    )
}

object PageManagerTool : FloatingWindowTool(
  ToolbarToolMeta(
    id = ToolbarItem.PAGES,
    label = ToolbarText("页面", "Pages"),
    commandLabel = "页面管理",
    category = "页面",
    scenes = pageScenes,
    defaultVisible = true,
  ),
) {
  override val staticIconId: ToolbarIcon.StaticId = ToolbarIcon.StaticId.PAGES

  override fun primaryEffects(arguments: Map<String, String>): List<ToolbarEffect> =
    listOf(
      ToolbarEffect.ShowFloatingWindow(
        ToolbarWindowId.PAGE_MANAGER,
        ToolbarItem.PAGES,
      ),
    )

  override val gestures: Map<ToolbarGesture, ToolbarActionRef> = mapOf(
    ToolbarGesture.SIDE_1 to ToolbarActionRef("page.add"),
    ToolbarGesture.SIDE_2 to ToolbarActionRef("page.previous"),
    ToolbarGesture.SIDE_3 to ToolbarActionRef("page.next"),
  )

  override fun isSelected(state: ToolbarUiState): Boolean = state.pageManagerOpen
}

object NotebooksTool : FullScreenTool(
  ToolbarToolMeta(
    id = ToolbarItem.NOTEBOOKS,
    label = ToolbarText("笔记本", "Notebooks"),
    commandLabel = "笔记本管理",
    category = "笔记本",
    scenes = editorScenes,
    defaultVisible = true,
  ),
) {
  override val staticIconId: ToolbarIcon.StaticId = ToolbarIcon.StaticId.NOTEBOOKS

  override fun primaryEffects(arguments: Map<String, String>): List<ToolbarEffect> =
    listOf(ToolbarEffect.OpenFullScreen(ToolbarScreenId.NOTEBOOK_MANAGER))

  override val gestures: Map<ToolbarGesture, ToolbarActionRef> = mapOf(
    ToolbarGesture.SIDE_1 to ToolbarActionRef("notebook.current"),
    ToolbarGesture.SIDE_2 to ToolbarActionRef("notebook.remaining"),
  )
}

object ExportTool : FullScreenTool(
  ToolbarToolMeta(
    id = ToolbarItem.EXPORT,
    label = ToolbarText("导出", "Export"),
    commandLabel = "导出管理",
    category = "导出",
    scenes = editorScenes,
    defaultVisible = true,
  ),
) {
  override val staticIconId: ToolbarIcon.StaticId = ToolbarIcon.StaticId.EXPORT

  override fun primaryEffects(arguments: Map<String, String>): List<ToolbarEffect> =
    listOf(ToolbarEffect.OpenFullScreen(ToolbarScreenId.EXPORT_MANAGER))
}

object UndoTool : ImmediateActionTool(
  ToolbarToolMeta(
    id = ToolbarItem.UNDO,
    label = ToolbarText("撤销", "Undo"),
    commandLabel = "撤销",
    category = "编辑",
    scenes = editorScenes,
    defaultVisible = true,
  ),
) {
  override val staticIconId: ToolbarIcon.StaticId = ToolbarIcon.StaticId.UNDO

  override fun primaryEffects(arguments: Map<String, String>): List<ToolbarEffect> =
    listOf(ToolbarEffect.Undo)

  override fun isAvailable(context: CommandContext): Boolean = context.canUndo
}

object RedoTool : ImmediateActionTool(
  ToolbarToolMeta(
    id = ToolbarItem.REDO,
    label = ToolbarText("重做", "Redo"),
    commandLabel = "重做",
    category = "编辑",
    scenes = editorScenes,
    defaultVisible = true,
  ),
) {
  override val staticIconId: ToolbarIcon.StaticId = ToolbarIcon.StaticId.REDO

  override fun primaryEffects(arguments: Map<String, String>): List<ToolbarEffect> =
    listOf(ToolbarEffect.Redo)

  override fun isAvailable(context: CommandContext): Boolean = context.canRedo
}

object DeleteTool : ImmediateActionTool(
  ToolbarToolMeta(
    id = ToolbarItem.DELETE,
    label = ToolbarText("删除所选", "Delete selection"),
    commandLabel = "删除所选",
    category = "编辑",
    scenes = editorScenes,
    defaultVisible = true,
  ),
) {
  override val staticIconId: ToolbarIcon.StaticId = ToolbarIcon.StaticId.DELETE

  override fun primaryEffects(arguments: Map<String, String>): List<ToolbarEffect> =
    listOf(ToolbarEffect.DeleteSelection)

  override fun isAvailable(context: CommandContext): Boolean = context.selection
}
