package com.betterhv.note.toolbar

import com.betterhv.note.AppCommand
import com.betterhv.note.CommandBinding
import com.betterhv.note.CommandDispatcher
import com.betterhv.note.ToolbarItem

/**
 * Single explicit source of toolbar tools, commands and window metadata.
 * Registration is intentionally manual: there is no reflection or classpath
 * scanning, so order and command ids stay deterministic.
 */
object ToolbarCatalog {
  val tools: List<ToolbarTool> = listOf(
    PenSlotTool(0),
    PenSlotTool(1),
    PenSlotTool(2),
    TailEraserTool,
    LassoTool,
    PdfNavigationTool,
    InsertTool,
    UndoTool,
    RedoTool,
    DeleteTool,
    PageManagerTool,
    NotebooksTool,
    ExportTool,
    MenuTool,
  )

  val actions: List<ToolbarActionSpec> = buildList {
    tools.forEach { tool -> add(primaryAction(tool)) }
    addAll(SharedToolbarActions.all)
  }

  val actionsByCommandId: Map<String, ToolbarActionSpec> =
    actions.associateBy { it.command.id }

  val commands: List<AppCommand> = actions.map { it.command }

  val windows: Map<ToolbarWindowId, ToolbarWindowSpec> = mapOf(
    ToolbarWindowId.PEN_SETTINGS to ToolbarWindowSpec(
      id = ToolbarWindowId.PEN_SETTINGS,
      presentation = FloatingPresentation.FLYOUT,
      anchor = null,
      openPolicy = FloatingOpenPolicy.OPEN,
      title = ToolbarText("笔设置", "Pen settings"),
      content = ToolbarWindowContent.PenSettings,
    ),
    ToolbarWindowId.INSERT to ToolbarWindowSpec(
      id = ToolbarWindowId.INSERT,
      presentation = FloatingPresentation.FLYOUT,
      anchor = ToolbarItem.INSERT,
      openPolicy = FloatingOpenPolicy.OPEN,
      title = ToolbarText("菜单", "菜单"),
      content = ToolbarWindowContent.Commands(
        listOf(CommandBinding("insert.image"), CommandBinding("insert.text")),
      ),
    ),
    ToolbarWindowId.PDF_NAVIGATION to ToolbarWindowSpec(
      id = ToolbarWindowId.PDF_NAVIGATION,
      presentation = FloatingPresentation.FLYOUT,
      anchor = ToolbarItem.NAVIGATION,
      openPolicy = FloatingOpenPolicy.OPEN,
      title = ToolbarText("菜单", "菜单"),
      content = ToolbarWindowContent.Commands(
        listOf(CommandBinding("pdf.fit"), CommandBinding("pdf.mode")),
      ),
    ),
    ToolbarWindowId.MENU to ToolbarWindowSpec(
      id = ToolbarWindowId.MENU,
      presentation = FloatingPresentation.FLYOUT,
      anchor = ToolbarItem.MENU,
      openPolicy = FloatingOpenPolicy.OPEN,
      title = ToolbarText("菜单", "菜单"),
      content = ToolbarWindowContent.Menu(
        listOf(
          CommandBinding("settings.document"),
          CommandBinding("settings.app"),
          CommandBinding("editor.debug"),
        ),
      ),
    ),
    ToolbarWindowId.PAGE_MANAGER to ToolbarWindowSpec(
      id = ToolbarWindowId.PAGE_MANAGER,
      presentation = FloatingPresentation.PANEL,
      anchor = ToolbarItem.PAGES,
      openPolicy = FloatingOpenPolicy.TOGGLE,
      title = ToolbarText("页面管理", "Page manager"),
      content = ToolbarWindowContent.PageManager,
    ),
  )

  val defaultVisibleItems: Set<ToolbarItem> =
    tools.filter { it.defaultVisible }.mapTo(linkedSetOf()) { it.id }

  init {
    validate()
  }

  fun actionFor(commandId: String): ToolbarActionSpec? = actionsByCommandId[commandId]

  fun toolFor(id: ToolbarItem): ToolbarTool = tools.first { it.id == id }

  fun penToolForSlot(slot: Int): ToolbarTool =
    tools.first { it is PenSlotTool && it.slot == slot }

  fun commandTitles(state: ToolbarUiState): Map<String, ToolbarText> =
    tools.associate { tool ->
      "editor.${tool.id.storageId}" to tool.title(state)
    }

  fun selectedCommandIds(state: ToolbarUiState): Set<String> =
    tools.filter { it.isSelected(state) }.mapTo(linkedSetOf()) { tool ->
      "editor.${tool.id.storageId}"
    }

  fun present(
    state: ToolbarUiState,
    dispatcher: CommandDispatcher,
  ): List<ToolbarToolUiState> = tools.map { tool ->
    val primary = CommandBinding("editor.${tool.id.storageId}")
    ToolbarToolUiState(
      id = tool.id,
      primary = primary,
      gestures = tool.gestures.mapValues { (_, ref) ->
        CommandBinding(ref.commandId, ref.arguments)
      },
      visible = tool.isVisibleInToolbar(state),
      menuEligible = tool.isEligible(state) && tool.id != ToolbarItem.MENU,
      enabled = dispatcher.enabled(primary),
      selected = tool.isSelected(state),
      icon = tool.icon(state),
      contentDescription = tool.contentDescription(state),
      label = tool.label,
    )
  }

  /**
   * Fails fast on malformed catalogs. This runs during object initialization so
   * a missing or duplicate registration can never be silently hidden at runtime.
   */
  fun validate() {
    require(tools.map { it.id } == ToolbarItem.entries.toList()) {
      "ToolbarCatalog tools must match ToolbarItem order exactly: " +
        "expected=${ToolbarItem.entries}, actual=${tools.map { it.id }}"
    }
    require(actions.map { it.command.id }.toSet().size == actions.size) {
      "Duplicate toolbar action id: " + actions.groupBy { it.command.id }
        .filterValues { it.size > 1 }
        .keys
    }
    require(commands.map { it.id }.toSet().size == commands.size) {
      "Duplicate toolbar command id: " + commands.groupBy { it.id }
        .filterValues { it.size > 1 }
        .keys
    }
    require(windows.keys == ToolbarWindowId.entries.toSet()) {
      "Missing toolbar window spec: ${ToolbarWindowId.entries - windows.keys}"
    }

    windows.values.forEach { window ->
      val anchor = window.anchor
      if (window.id == ToolbarWindowId.PEN_SETTINGS) return@forEach
      require(anchor != null && tools.any { it.id == anchor }) {
        "Window ${window.id} has no known anchor: $anchor"
      }
    }

    tools.forEach { tool ->
      tool.gestures.forEach { (gesture, ref) ->
        val target = actionsByCommandId[ref.commandId]
        requireNotNull(target) {
          "Gesture $gesture of ${tool.id} references unknown command ${ref.commandId}"
        }
        require(target.command.accepts(CommandBinding(ref.commandId, ref.arguments))) {
          "Gesture $gesture of ${tool.id} has invalid arguments ${ref.arguments} " +
            "for ${ref.commandId}"
        }
      }
    }

    require(ToolbarItem.MENU !in defaultVisibleItems) {
      "MENU must never be part of the default visible items"
    }
    require(tools.single { it.id == ToolbarItem.MENU }.defaultVisible.not()) {
      "MENU must not default to visible"
    }
  }

  private fun primaryAction(tool: ToolbarTool): ToolbarActionSpec = ToolbarActionSpec(
    command = AppCommand(
      id = "editor.${tool.id.storageId}",
      label = tool.commandLabel,
      category = tool.category,
      scenes = tool.scenes,
      available = tool::isAvailable,
    ),
    effects = tool::primaryEffects,
  )
}
