package com.betterhv.note

/** Persisted invocation, separate from the undoable document Command. */
data class CommandBinding(val commandId: String, val arguments: Map<String, String> = emptyMap())

data class CommandOption(val value: String, val label: String)
data class CommandParameter(val name: String, val label: String, val options: List<CommandOption>)

data class CommandContext(
  val scene: ShortcutScene = ShortcutScene.EDITOR,
  val blocked: Boolean = false,
  val hasPdf: Boolean = false,
  val pdfPage: Boolean = false,
  val selection: Boolean = false,
  val canUndo: Boolean = false,
  val canRedo: Boolean = false,
  val selected: Set<String> = emptySet(),
  val labels: Map<String, String> = emptyMap(),
)

data class AppCommand(
  val id: String,
  val label: String,
  val category: String,
  val scenes: Set<ShortcutScene>,
  val parameters: List<CommandParameter> = emptyList(),
  val available: (CommandContext) -> Boolean = { true },
) {
  fun accepts(binding: CommandBinding): Boolean =
    binding.arguments.keys.all { key -> parameters.any { it.name == key } } &&
      parameters.all { p -> binding.arguments[p.name]?.let { value -> p.options.any { it.value == value } } ?: true }

  fun defaults(): Map<String, String> = parameters.associate { it.name to it.options.first().value }
  fun title(binding: CommandBinding): String = label + parameters.joinToString("") { p ->
    val value = binding.arguments[p.name] ?: p.options.first().value
    " · " + p.options.firstOrNull { it.value == value }?.label.orEmpty()
  }
}

data class ToolbarCommandItem(
  val item: ToolbarItem,
  val click: CommandBinding,
  val doubleClick: CommandBinding? = null,
  val side1: CommandBinding? = null,
  val side2: CommandBinding? = null,
  val side3: CommandBinding? = null,
)

/** One deterministic catalog for toolbar actions, menus and hardware-key binding. */
object CommandRegistry {
  private val editor = setOf(ShortcutScene.EDITOR)
  private val page = editor + ShortcutScene.PAGE_MANAGER
  private val insert = editor + ShortcutScene.INSERT
  private val slots = CommandParameter("slot", "笔槽", listOf(
    CommandOption("current", "当前笔槽"), CommandOption("0", "笔 1"),
    CommandOption("1", "笔 2"), CommandOption("2", "笔 3"),
  ))
  val commands: List<AppCommand> = buildList {
    fun command(
      id: String, label: String, category: String, scenes: Set<ShortcutScene> = editor,
      parameters: List<CommandParameter> = emptyList(), available: (CommandContext) -> Boolean = { true },
    ) { add(AppCommand(id, label, category, scenes, parameters, available)) }
    repeat(3) { command("editor.pen_${it + 1}", "笔 ${it + 1}", "画笔") }
    command("pen.settings", "打开笔设置", "画笔", parameters = listOf(slots))
    command("pen.type", "笔类型", "画笔 / 设置", parameters = listOf(slots, CommandParameter("value", "类型", listOf(
      CommandOption("normal", "钢笔"), CommandOption("pencil", "铅笔"), CommandOption("marker", "荧光笔"),
    ))))
    command("pen.width", "粗细", "画笔 / 设置", parameters = listOf(slots,
      CommandParameter("value", "粗细", PenProfiles.logicalWidths.indices.map { CommandOption("$it", "${it + 1}") })))
    command("pen.color", "颜色", "画笔 / 设置", parameters = listOf(slots,
      CommandParameter("value", "颜色", PenPalette.colors.map { CommandOption(it.id, it.label) })))
    command("editor.tail_eraser", "切换橡皮模式", "橡皮")
    command("eraser.mode", "指定橡皮模式", "橡皮", parameters = listOf(CommandParameter("value", "模式", listOf(
      CommandOption("WHOLE_STROKE", "整笔擦除"), CommandOption("POINT", "局部擦除"),
    ))))
    command("editor.lasso", "套索", "编辑")
    command("editor.undo", "撤销", "编辑", available = { it.canUndo })
    command("editor.redo", "重做", "编辑", available = { it.canRedo })
    command("editor.delete", "删除所选", "编辑", available = { it.selection })
    command("editor.navigation", "PDF 导航", "PDF 导航", available = { it.hasPdf })
    command("pdf.fit", "适合页面", "PDF 导航", available = { it.pdfPage })
    command("pdf.mode", "切换学习 / 阅读模式", "PDF 导航", available = { it.hasPdf })
    command("editor.insert", "插入图片或文字", "插入", insert)
    command("insert.image", "插入图片", "插入", insert)
    command("insert.text", "插入文字", "插入", insert)
    command("insert.local", "本地 / 手动来源", "插入 / 来源", setOf(ShortcutScene.INSERT))
    command("insert.notelink", "NoteLink 来源", "插入 / 来源", setOf(ShortcutScene.INSERT))
    command("insert.clipboard", "剪贴板来源", "插入 / 来源", setOf(ShortcutScene.INSERT))
    command("clipboard.image", "从剪贴板插入图片", "插入", insert)
    command("clipboard.text", "从剪贴板插入文字", "插入", insert)
    command("insert.cancel", "取消插入", "插入", setOf(ShortcutScene.INSERT))
    command("editor.pages", "页面管理", "页面", page)
    command("page.previous", "上一页", "页面", page)
    command("page.next", "下一页", "页面", page)
    command("page.add", "在当前页后新增", "页面", page)
    command("page.delete", "删除当前页", "页面", page)
    command("page.bookmark", "切换书签", "页面", page)
    command("page.close", "关闭页面管理", "页面", setOf(ShortcutScene.PAGE_MANAGER))
    command("editor.notebooks", "笔记本管理", "笔记本")
    command("notebook.current", "当前页建立笔记本", "笔记本")
    command("notebook.remaining", "当前页至末页建立笔记本", "笔记本")
    command("editor.export", "导出管理", "导出")
    command("editor.menu", "打开菜单", "菜单")
    command("editor.toggle_toolbar", "显示 / 隐藏工具栏", "菜单")
    command("settings.document", "文档设置", "菜单")
    command("settings.app", "应用设置", "菜单")
    command("editor.debug", "切换 Debug", "菜单")
  }
  private val byId = commands.associateBy(AppCommand::id).also { require(it.size == commands.size) }
  fun find(id: String): AppCommand? = byId[id]
  fun title(binding: CommandBinding): String = find(binding.commandId)?.title(binding) ?: "失效绑定：${binding.commandId}"
  val menus = mapOf(
    "insert" to listOf("insert.image", "insert.text"),
    "sources" to listOf("insert.local", "insert.notelink", "insert.clipboard"),
    "navigation" to listOf("pdf.fit", "pdf.mode"),
    "menu" to listOf("settings.document", "settings.app", "editor.debug"),
  )
  val toolbar: List<ToolbarCommandItem> = ToolbarItem.entries.map { item ->
    val binding = CommandBinding("editor.${item.storageId}")
    when (item) {
      ToolbarItem.PEN_1, ToolbarItem.PEN_2, ToolbarItem.PEN_3 -> {
        val settings = CommandBinding("pen.settings", mapOf("slot" to "${item.ordinal}"))
        ToolbarCommandItem(item, binding, doubleClick = settings, side1 = settings)
      }
      ToolbarItem.PAGES -> ToolbarCommandItem(item, binding, side1 = CommandBinding("page.add"),
        side2 = CommandBinding("page.previous"), side3 = CommandBinding("page.next"))
      ToolbarItem.NOTEBOOKS -> ToolbarCommandItem(item, binding, side1 = CommandBinding("notebook.current"),
        side2 = CommandBinding("notebook.remaining"))
      ToolbarItem.MENU -> ToolbarCommandItem(item, binding, side1 = binding)
      else -> ToolbarCommandItem(item, binding)
    }
  }
}

class CommandDispatcher(private val context: () -> CommandContext, private val execute: (CommandBinding) -> Unit) {
  fun enabled(binding: CommandBinding): Boolean {
    val command = CommandRegistry.find(binding.commandId) ?: return false
    val state = context()
    return !state.blocked && state.scene in command.scenes && command.accepts(binding) && command.available(state)
  }
  fun title(binding: CommandBinding): String = context().labels[binding.commandId] ?: CommandRegistry.title(binding)
  fun selected(binding: CommandBinding): Boolean = binding.commandId in context().selected
  fun dispatch(binding: CommandBinding): Boolean {
    if (!enabled(binding)) return false
    val command = requireNotNull(CommandRegistry.find(binding.commandId))
    execute(binding.copy(arguments = command.defaults() + binding.arguments))
    return true
  }
}

data class ToolbarMenuRequest(val id: String, val slot: Int = 0)
