package com.betterhv.note.toolbar

import com.betterhv.note.CommandBinding
import com.betterhv.note.CommandContext
import com.betterhv.note.EraserMode
import com.betterhv.note.PenToolbarSettings
import com.betterhv.note.ToolKind
import com.betterhv.note.ToolbarItem
import com.betterhv.note.ink.PenType

/**
 * Bilingual user-facing text owned by a toolbar tool. The toolbar model is
 * framework-free, so it does not read NoteI18n's Compose state directly; the
 * UI layer resolves the text with the current language.
 */
data class ToolbarText(val zh: String, val en: String) {
  fun localized(isChinese: Boolean): String = if (isChinese) zh else en
}

/**
 * Semantic icon description. Toolbar.kt maps these values to the actual
 * Compose painters; this type keeps icon selection out of the tool model.
 */
sealed interface ToolbarIcon {
  data class Static(val id: StaticId) : ToolbarIcon
  data class Pen(val type: PenType) : ToolbarIcon
  data class Eraser(val mode: EraserMode) : ToolbarIcon

  enum class StaticId {
    TAIL_ERASER,
    LASSO,
    NAVIGATION,
    INSERT,
    UNDO,
    REDO,
    DELETE,
    PAGES,
    NOTEBOOKS,
    EXPORT,
    MENU,
  }
}

/** Non-primary gestures that can map to a different command on a toolbar button. */
enum class ToolbarGesture { DOUBLE_CLICK, SIDE_1, SIDE_2, SIDE_3 }

/** A reference to a command plus its fixed arguments, before default filling. */
data class ToolbarActionRef(
  val commandId: String,
  val arguments: Map<String, String> = emptyMap(),
)

/**
 * The runtime state a tool needs to decide visibility, selection and
 * presentation. It is data only; no mutable state belongs to a tool object.
 */
data class ToolbarUiState(
  val visibleItems: Set<ToolbarItem>,
  val toolKind: ToolKind,
  val eraserMode: EraserMode,
  val penSettings: PenToolbarSettings,
  val openFlyout: ToolbarFlyoutRequest?,
  val pageManagerOpen: Boolean,
  val notebookManagerOpen: Boolean,
  val exportPanelOpen: Boolean,
  val commandContext: CommandContext,
)

/**
 * Fully resolved presentation of one toolbar button for one composition.
 * Toolbar.kt consumes these values and must not branch on the tool id.
 */
data class ToolbarToolUiState(
  val id: ToolbarItem,
  val primary: CommandBinding,
  val gestures: Map<ToolbarGesture, CommandBinding>,
  val visible: Boolean,
  val menuEligible: Boolean,
  val enabled: Boolean,
  val selected: Boolean,
  val icon: ToolbarIcon,
  val contentDescription: ToolbarText?,
  val label: ToolbarText,
)
