package com.betterhv.note.toolbar

import com.betterhv.note.CommandBinding
import com.betterhv.note.ToolbarItem

/** Stable internal ids for surfaces opened by toolbar tools. */
enum class ToolbarWindowId {
  PEN_SETTINGS,
  INSERT,
  PDF_NAVIGATION,
  MENU,
  PAGE_MANAGER,
}

/**
 * How a floating window is presented. A flyout is anchored to a toolbar button;
 * a panel may float over the canvas without a Popup anchor.
 */
enum class FloatingPresentation { FLYOUT, PANEL }

enum class FloatingOpenPolicy { OPEN, TOGGLE }

/** Content rendered inside a floating window by the toolbar UI. */
sealed interface ToolbarWindowContent {
  data object PenSettings : ToolbarWindowContent
  data class Commands(val entries: List<CommandBinding>) : ToolbarWindowContent
  data class Menu(val entries: List<CommandBinding>) : ToolbarWindowContent
  data object PageManager : ToolbarWindowContent
}

/** Declarative metadata for one floating toolbar surface. */
data class ToolbarWindowSpec(
  val id: ToolbarWindowId,
  val presentation: FloatingPresentation,
  val anchor: ToolbarItem?,
  val openPolicy: FloatingOpenPolicy,
  val title: ToolbarText,
  val content: ToolbarWindowContent,
)

/** A specific request to open (or show) one toolbar flyout. */
data class ToolbarFlyoutRequest(
  val window: ToolbarWindowId,
  val anchor: ToolbarItem,
  val arguments: Map<String, String> = emptyMap(),
)

/** Full-screen surfaces are not floating windows. */
enum class ToolbarScreenId { NOTEBOOK_MANAGER, EXPORT_MANAGER }
