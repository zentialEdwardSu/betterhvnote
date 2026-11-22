package com.betterhv.note.toolbar

import com.betterhv.note.CommandBinding
import com.betterhv.note.CommandContext
import com.betterhv.note.CommandDispatcher
import com.betterhv.note.EraserMode
import com.betterhv.note.settings.PenToolbarSettings
import com.betterhv.note.ToolKind
import com.betterhv.note.ToolbarItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarCatalogTest {
  private val primaryCommandIds = setOf(
    "editor.pen_1",
    "editor.pen_2",
    "editor.pen_3",
    "editor.tail_eraser",
    "editor.lasso",
    "editor.navigation",
    "editor.insert",
    "editor.undo",
    "editor.redo",
    "editor.delete",
    "editor.pages",
    "editor.notebooks",
    "editor.export",
    "editor.menu",
  )

  private val sharedCommandIds = setOf(
    "pen.settings",
    "page.add",
    "page.previous",
    "page.next",
    "notebook.current",
    "notebook.remaining",
  )

  @Test fun toolsMatchStableOrderAndExposeExpectedCommands() {
    assertEquals(ToolbarItem.entries.toList(), ToolbarCatalog.tools.map { it.id })
    assertEquals(14, ToolbarCatalog.tools.size)
    assertEquals(primaryCommandIds + sharedCommandIds, ToolbarCatalog.commands.map { it.id }.toSet())
    assertEquals(20, ToolbarCatalog.commands.size)
    assertEquals(ToolbarCatalog.actions.size, ToolbarCatalog.actionsByCommandId.size)
    assertEquals(ToolbarItem.PEN_2, ToolbarCatalog.penToolForSlot(1).id)
  }

  @Test fun defaultVisibilityExcludesMenuButKeepsAllOtherTools() {
    assertEquals(
      ToolbarItem.entries.filterNot { it == ToolbarItem.MENU }.toSet(),
      ToolbarCatalog.defaultVisibleItems,
    )
    assertTrue(ToolbarItem.MENU !in ToolbarCatalog.defaultVisibleItems)
  }

  @Test fun windowMetadataCarriesFlyoutOrPanelContent() {
    val penSettings = ToolbarCatalog.windows.getValue(ToolbarWindowId.PEN_SETTINGS)
    assertEquals(FloatingPresentation.FLYOUT, penSettings.presentation)
    assertNull(penSettings.anchor)
    assertTrue(penSettings.content is ToolbarWindowContent.PenSettings)

    val insert = ToolbarCatalog.windows.getValue(ToolbarWindowId.INSERT)
    assertEquals(ToolbarItem.INSERT, insert.anchor)
    assertEquals(
      listOf(CommandBinding("insert.image"), CommandBinding("insert.text")),
      (insert.content as ToolbarWindowContent.Commands).entries,
    )

    val menu = ToolbarCatalog.windows.getValue(ToolbarWindowId.MENU)
    assertEquals(
      listOf(
        CommandBinding("settings.document"),
        CommandBinding("settings.app"),
        CommandBinding("editor.debug"),
      ),
      (menu.content as ToolbarWindowContent.Menu).entries,
    )

    val pageManager = ToolbarCatalog.windows.getValue(ToolbarWindowId.PAGE_MANAGER)
    assertEquals(FloatingPresentation.PANEL, pageManager.presentation)
    assertEquals(FloatingOpenPolicy.TOGGLE, pageManager.openPolicy)
  }

  @Test fun sharedActionsProduceTheirExpectedEffects() {
    val penSettings = ToolbarCatalog.actionFor("pen.settings")!!
    assertEquals(
      listOf(
        ToolbarEffect.SelectPenSlot(1),
        ToolbarEffect.SetCanvasTool(ToolKind.PEN),
        ToolbarEffect.ShowFloatingWindow(
          ToolbarWindowId.PEN_SETTINGS,
          arguments = mapOf("slot" to "1"),
        ),
      ),
      penSettings.effects(mapOf("slot" to "1")),
    )
    assertEquals(
      listOf(ToolbarEffect.SelectPenSlot(null)),
      ToolbarCatalog.actionFor("pen.settings")!!.effects(mapOf("slot" to "current")).take(1),
    )
    assertEquals(listOf(ToolbarEffect.AddPage), ToolbarCatalog.actionFor("page.add")!!.effects(emptyMap()))
    assertEquals(
      listOf(ToolbarEffect.NavigatePage(-1)),
      ToolbarCatalog.actionFor("page.previous")!!.effects(emptyMap()),
    )
    assertEquals(
      listOf(ToolbarEffect.NavigatePage(1)),
      ToolbarCatalog.actionFor("page.next")!!.effects(emptyMap()),
    )
    assertEquals(
      listOf(ToolbarEffect.CreateNotebook(NotebookScope.CURRENT_PAGE)),
      ToolbarCatalog.actionFor("notebook.current")!!.effects(emptyMap()),
    )
    assertEquals(
      listOf(ToolbarEffect.CreateNotebook(NotebookScope.CURRENT_TO_END)),
      ToolbarCatalog.actionFor("notebook.remaining")!!.effects(emptyMap()),
    )
  }

  @Test fun commandTitlesAndSelectionComeFromTools() {
    val point = state(eraserMode = EraserMode.POINT)
    assertEquals(
      ToolbarText("局部擦除 · 点击切换", "局部擦除 · 点击切换"),
      ToolbarCatalog.commandTitles(point)["editor.tail_eraser"],
    )

    val pen = state(
      toolKind = ToolKind.PEN,
      penSettings = PenToolbarSettings(activeSlot = 1),
    )
    assertEquals(setOf("editor.pen_2"), ToolbarCatalog.selectedCommandIds(pen))

    val pages = state(pageManagerOpen = true)
    assertTrue("editor.pages" in ToolbarCatalog.selectedCommandIds(pages))

    val insert = state(
      openFlyout = ToolbarFlyoutRequest(ToolbarWindowId.INSERT, ToolbarItem.INSERT),
    )
    assertTrue("editor.insert" in ToolbarCatalog.selectedCommandIds(insert))
  }

  @Test fun presentResolvesVisibilityMenuEligibilityAndGestures() {
    val hiddenInsert = ToolbarCatalog.defaultVisibleItems - ToolbarItem.INSERT
    val current = state(visibleItems = hiddenInsert, pageManagerOpen = true)
    val dispatcher = CommandDispatcher(
      context = { current.commandContext },
      execute = {},
    )
    val presented = ToolbarCatalog.present(current, dispatcher)

    assertEquals(14, presented.size)
    assertEquals(ToolbarItem.entries.toList(), presented.map { it.id })
    assertTrue(presented.single { it.id == ToolbarItem.MENU }.visible)
    assertFalse(presented.single { it.id == ToolbarItem.NAVIGATION }.visible)
    assertFalse(presented.single { it.id == ToolbarItem.NAVIGATION }.menuEligible)
    assertFalse(presented.single { it.id == ToolbarItem.INSERT }.visible)
    assertTrue(presented.single { it.id == ToolbarItem.INSERT }.menuEligible)
    assertTrue(presented.single { it.id == ToolbarItem.PAGES }.selected)
    assertEquals(
      CommandBinding("pen.settings", mapOf("slot" to "0")),
      presented.single { it.id == ToolbarItem.PEN_1 }
        .gestures.getValue(ToolbarGesture.SIDE_1),
    )
  }

  @Test fun noPdfNavigationIsIneligibleAndNotRendered() {
    val noPdf = state(hasPdf = false)
    assertEquals(
      false,
      ToolbarCatalog.tools.first { it.id == ToolbarItem.NAVIGATION }.isVisibleInToolbar(noPdf),
    )
    val withPdf = state(hasPdf = true)
    assertTrue(
      ToolbarCatalog.tools.first { it.id == ToolbarItem.NAVIGATION }.isVisibleInToolbar(withPdf),
    )
  }

  private fun state(
    visibleItems: Set<ToolbarItem> = ToolbarCatalog.defaultVisibleItems,
    toolKind: ToolKind = ToolKind.PEN,
    eraserMode: EraserMode = EraserMode.WHOLE_STROKE,
    penSettings: PenToolbarSettings = PenToolbarSettings(),
    openFlyout: ToolbarFlyoutRequest? = null,
    pageManagerOpen: Boolean = false,
    hasPdf: Boolean = false,
  ): ToolbarUiState = ToolbarUiState(
    visibleItems = visibleItems,
    toolKind = toolKind,
    eraserMode = eraserMode,
    penSettings = penSettings,
    openFlyout = openFlyout,
    pageManagerOpen = pageManagerOpen,
    notebookManagerOpen = false,
    exportPanelOpen = false,
    commandContext = CommandContext(hasPdf = hasPdf),
  )
}
