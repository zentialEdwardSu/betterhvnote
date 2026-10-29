package com.betterhv.note.toolbar

import com.betterhv.note.AppCommand
import com.betterhv.note.CommandOption
import com.betterhv.note.CommandParameter
import com.betterhv.note.PenToolbarSettings
import com.betterhv.note.ShortcutScene
import com.betterhv.note.ToolKind

/** Command parameters shared by toolbar actions and the existing pen commands. */
object EditorCommandParameters {
  val slots: CommandParameter = CommandParameter(
    name = "slot",
    label = "笔槽",
    options = listOf(
      CommandOption("current", "当前笔槽"),
      CommandOption("0", "笔 1"),
      CommandOption("1", "笔 2"),
      CommandOption("2", "笔 3"),
    ),
  )
}

/**
 * Commands referenced by toolbar gestures that are not the primary command of
 * one tool. They stay ordinary [AppCommand]s so the K1-K8 binding panel can
 * list and execute them exactly like tool commands.
 */
object SharedToolbarActions {
  private val editorScenes = setOf(ShortcutScene.EDITOR)
  private val pageScenes = editorScenes + ShortcutScene.PAGE_MANAGER

  private val penSettings = ToolbarActionSpec(
    command = AppCommand(
      id = "pen.settings",
      label = "打开笔设置",
      category = "画笔",
      scenes = editorScenes,
      parameters = listOf(EditorCommandParameters.slots),
    ),
    effects = { arguments ->
      val slot = arguments["slot"]
        ?.takeIf { it != "current" }
        ?.toIntOrNull()
        ?.takeIf { it in 0 until PenToolbarSettings.SLOT_COUNT }
      listOf(
        ToolbarEffect.SelectPenSlot(slot),
        ToolbarEffect.SetCanvasTool(ToolKind.PEN),
        ToolbarEffect.ShowFloatingWindow(
          ToolbarWindowId.PEN_SETTINGS,
          arguments = arguments,
        ),
      )
    },
  )

  private val pageAdd = ToolbarActionSpec(
    command = AppCommand(
      id = "page.add",
      label = "在当前页后新增",
      category = "页面",
      scenes = pageScenes,
    ),
    effects = { listOf(ToolbarEffect.AddPage) },
  )

  private val pagePrevious = ToolbarActionSpec(
    command = AppCommand(
      id = "page.previous",
      label = "上一页",
      category = "页面",
      scenes = pageScenes,
    ),
    effects = { listOf(ToolbarEffect.NavigatePage(-1)) },
  )

  private val pageNext = ToolbarActionSpec(
    command = AppCommand(
      id = "page.next",
      label = "下一页",
      category = "页面",
      scenes = pageScenes,
    ),
    effects = { listOf(ToolbarEffect.NavigatePage(1)) },
  )

  private val notebookCurrent = ToolbarActionSpec(
    command = AppCommand(
      id = "notebook.current",
      label = "当前页建立笔记本",
      category = "笔记本",
      scenes = editorScenes,
    ),
    effects = { listOf(ToolbarEffect.CreateNotebook(NotebookScope.CURRENT_PAGE)) },
  )

  private val notebookRemaining = ToolbarActionSpec(
    command = AppCommand(
      id = "notebook.remaining",
      label = "当前页至末页建立笔记本",
      category = "笔记本",
      scenes = editorScenes,
    ),
    effects = { listOf(ToolbarEffect.CreateNotebook(NotebookScope.CURRENT_TO_END)) },
  )

  val all: List<ToolbarActionSpec> = listOf(
    penSettings,
    pageAdd,
    pagePrevious,
    pageNext,
    notebookCurrent,
    notebookRemaining,
  )
}
