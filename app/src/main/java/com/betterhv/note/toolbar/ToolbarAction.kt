package com.betterhv.note.toolbar

import com.betterhv.note.AppCommand
import com.betterhv.note.ToolKind
import com.betterhv.note.ToolbarItem

/**
 * A command owned by the toolbar catalog together with the pure effects it
 * requests. Effects are data; the app layer decides how to apply them.
 */
data class ToolbarActionSpec(
  val command: AppCommand,
  val effects: (Map<String, String>) -> List<ToolbarEffect>,
)

/** Pure requests produced by a toolbar action. */
sealed interface ToolbarEffect {
  /** `null` means the currently active pen slot. */
  data class SelectPenSlot(val slot: Int?) : ToolbarEffect

  data class SetCanvasTool(val tool: ToolKind) : ToolbarEffect

  data object ToggleEraserMode : ToolbarEffect

  data class ShowFloatingWindow(
    val window: ToolbarWindowId,
    val anchor: ToolbarItem? = null,
    val arguments: Map<String, String> = emptyMap(),
  ) : ToolbarEffect

  data class OpenFullScreen(val screen: ToolbarScreenId) : ToolbarEffect

  data object Undo : ToolbarEffect
  data object Redo : ToolbarEffect
  data object DeleteSelection : ToolbarEffect

  data object AddPage : ToolbarEffect
  data class NavigatePage(val delta: Int) : ToolbarEffect
  data class CreateNotebook(val scope: NotebookScope) : ToolbarEffect
}

enum class NotebookScope { CURRENT_PAGE, CURRENT_TO_END }

/**
 * App-layer output port. Tools only return effects; the AppRoot implementation
 * maps them onto existing editor state and canvas calls.
 */
fun interface ToolEffectSink {
  fun execute(effect: ToolbarEffect)
}
