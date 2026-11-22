package com.betterhv.note

import com.betterhv.note.toolbar.EditorCommandParameters
import com.betterhv.note.toolbar.ToolbarCatalog
import com.betterhv.note.toolbar.ToolbarWindowContent
import com.betterhv.note.settings.PenPalette
import com.betterhv.note.settings.PenProfiles

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

/** Non-toolbar commands and controls kept outside the toolbar object model. */
object NonToolbarCommands {
  private val editor = setOf(ShortcutScene.EDITOR)
  private val page = editor + ShortcutScene.PAGE_MANAGER
  private val insert = editor + ShortcutScene.INSERT

  val all: List<AppCommand> = buildList {
    fun command(
      id: String, label: String, category: String, scenes: Set<ShortcutScene> = editor,
      parameters: List<CommandParameter> = emptyList(), available: (CommandContext) -> Boolean = { true },
    ) { add(AppCommand(id, label, category, scenes, parameters, available)) }

    command(
      "pen.type",
      "笔类型",
      "画笔 / 设置",
      parameters = listOf(
        EditorCommandParameters.slots,
        CommandParameter(
          "value",
          "类型",
          listOf(
            CommandOption("normal", "钢笔"),
            CommandOption("pencil", "铅笔"),
            CommandOption("marker", "荧光笔"),
          ),
        ),
      ),
    )
    command("pen.width", "粗细", "画笔 / 设置", parameters = listOf(EditorCommandParameters.slots,
      CommandParameter("value", "粗细", PenProfiles.logicalWidths.indices.map { CommandOption("$it", "${it + 1}") })))
    command("pen.color", "颜色", "画笔 / 设置", parameters = listOf(EditorCommandParameters.slots,
      CommandParameter("value", "颜色", PenPalette.colors.map { CommandOption(it.id, it.label) })))
    command("eraser.mode", "指定橡皮模式", "橡皮", parameters = listOf(CommandParameter("value", "模式", listOf(
      CommandOption("WHOLE_STROKE", "整笔擦除"), CommandOption("POINT", "局部擦除"),
    ))))
    command("pdf.fit", "适合页面", "PDF 导航", available = { it.pdfPage })
    command("pdf.mode", "切换学习 / 阅读模式", "PDF 导航", available = { it.hasPdf })
    command("insert.image", "插入图片", "插入", insert)
    command("insert.text", "插入文字", "插入", insert)
    command("insert.local", "本地 / 手动来源", "插入 / 来源", setOf(ShortcutScene.INSERT))
    command("insert.notelink", "NoteLink 来源", "插入 / 来源", setOf(ShortcutScene.INSERT))
    command("insert.clipboard", "剪贴板来源", "插入 / 来源", setOf(ShortcutScene.INSERT))
    command("clipboard.image", "从剪贴板插入图片", "插入", insert)
    command("clipboard.text", "从剪贴板插入文字", "插入", insert)
    command("insert.cancel", "取消插入", "插入", setOf(ShortcutScene.INSERT))
    command("page.delete", "删除当前页", "页面", page)
    command("page.bookmark", "切换书签", "页面", page)
    command("page.close", "关闭页面管理", "页面", setOf(ShortcutScene.PAGE_MANAGER))
    command("settings.document", "文档设置", "菜单")
    command("settings.app", "应用设置", "菜单")
    command("editor.debug", "切换 Debug", "菜单")
    command("editor.toggle_toolbar", "显示 / 隐藏工具栏", "菜单")
  }
}

/** One deterministic catalog for toolbar actions, menus and hardware-key binding. */
object CommandRegistry {
  val commands: List<AppCommand> = ToolbarCatalog.commands + NonToolbarCommands.all
  private val byId = commands.associateBy(AppCommand::id).also { map ->
    require(map.size == commands.size) {
      "Duplicate command id: " + commands.groupBy { it.id }
        .filterValues { it.size > 1 }
        .keys
    }
  }

  val menus = mapOf(
    "sources" to listOf("insert.local", "insert.notelink", "insert.clipboard"),
  )

  init {
    ToolbarCatalog.windows.values
      .flatMap { window ->
        when (val content = window.content) {
          is ToolbarWindowContent.Commands -> content.entries
          is ToolbarWindowContent.Menu -> content.entries
          else -> emptyList()
        }
      }
      .forEach { binding ->
        require(find(binding.commandId) != null) {
          "Toolbar window ${binding.commandId} does not resolve in CommandRegistry"
        }
      }
  }

  fun find(id: String): AppCommand? = byId[id]
  fun title(binding: CommandBinding): String = find(binding.commandId)?.title(binding) ?: "失效绑定：${binding.commandId}"
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
