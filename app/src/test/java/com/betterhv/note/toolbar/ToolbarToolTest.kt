package com.betterhv.note.toolbar

import com.betterhv.note.CommandContext
import com.betterhv.note.EraserMode
import com.betterhv.note.settings.PenToolbarSettings
import com.betterhv.note.ShortcutScene
import com.betterhv.note.ToolKind
import com.betterhv.note.ToolbarItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarToolTest {
  @Test fun allStableToolbarItemsHaveExactlyOneToolObject() {
    val tools = allTools()
    assertEquals(ToolbarItem.entries.size, tools.size)
    assertEquals(ToolbarItem.entries.toSet(), tools.map { it.id }.toSet())
    assertFalse(MenuTool.defaultVisible)
    assertTrue(MenuTool.isVisibleInToolbar(state(visibleItems = emptySet())))
  }

  @Test fun penSlotToolSelectsItsExplicitSlotAndExposesSettingsGestures() {
    val tool = PenSlotTool(1)
    assertEquals(ToolbarItem.PEN_2, tool.id)
    assertEquals(ToolbarText("笔 2", "Pen 2"), tool.label)
    assertEquals(
      listOf(
        ToolbarEffect.SelectPenSlot(1),
        ToolbarEffect.SetCanvasTool(ToolKind.PEN),
      ),
      tool.primaryEffects(emptyMap()),
    )
    assertEquals("pen.settings", tool.gestures.getValue(ToolbarGesture.DOUBLE_CLICK).commandId)
    assertEquals(mapOf("slot" to "1"), tool.gestures.getValue(ToolbarGesture.SIDE_1).arguments)

    val selected = state(
      toolKind = ToolKind.PEN,
      penSettings = PenToolbarSettings(activeSlot = 1),
    )
    assertTrue(tool.isSelected(selected))
    assertEquals(
      ToolbarIcon.Pen(selected.penSettings.settingsFor(1).activeType),
      tool.icon(selected),
    )
  }

  @Test fun tailEraserToolHasDynamicIconAndText() {
    val whole = state(eraserMode = EraserMode.WHOLE_STROKE)
    val point = state(eraserMode = EraserMode.POINT)

    assertEquals(listOf(ToolbarEffect.ToggleEraserMode), TailEraserTool.primaryEffects(emptyMap()))
    assertEquals(ToolbarIcon.Eraser(EraserMode.WHOLE_STROKE), TailEraserTool.icon(whole))
    assertEquals(ToolbarIcon.Eraser(EraserMode.POINT), TailEraserTool.icon(point))
    assertEquals(
      ToolbarText("局部擦除 · 点击切换", "局部擦除 · 点击切换"),
      TailEraserTool.title(point),
    )
    assertEquals(TailEraserTool.title(point), TailEraserTool.contentDescription(point))
  }

  @Test fun pdfNavigationRequiresPdfAndCombinesCanvasAndFlyoutEffects() {
    val withoutPdf = state(hasPdf = false)
    val withPdf = state(hasPdf = true)

    assertFalse(PdfNavigationTool.isAvailable(CommandContext(hasPdf = false)))
    assertTrue(PdfNavigationTool.isAvailable(CommandContext(hasPdf = true)))
    assertFalse(PdfNavigationTool.isEligible(withoutPdf))
    assertFalse(PdfNavigationTool.isVisibleInToolbar(withoutPdf))
    assertTrue(PdfNavigationTool.isEligible(withPdf))
    assertTrue(PdfNavigationTool.isVisibleInToolbar(withPdf))
    assertEquals(
      listOf(
        ToolbarEffect.SetCanvasTool(ToolKind.NAVIGATION),
        ToolbarEffect.ShowFloatingWindow(
          ToolbarWindowId.PDF_NAVIGATION,
          ToolbarItem.NAVIGATION,
        ),
      ),
      PdfNavigationTool.primaryEffects(emptyMap()),
    )
    assertTrue(PdfNavigationTool.isSelected(state(toolKind = ToolKind.NAVIGATION, hasPdf = true)))
  }

  @Test fun floatingWindowToolsExposeSelectionAndAnchors() {
    val insertOpen = state(
      openFlyout = ToolbarFlyoutRequest(ToolbarWindowId.INSERT, ToolbarItem.INSERT),
    )
    val menuOpen = state(
      openFlyout = ToolbarFlyoutRequest(ToolbarWindowId.MENU, ToolbarItem.MENU),
    )

    assertTrue(InsertTool.isSelected(insertOpen))
    assertFalse(InsertTool.isSelected(menuOpen))
    assertTrue(MenuTool.isSelected(menuOpen))
    assertEquals(
      listOf(ToolbarEffect.ShowFloatingWindow(ToolbarWindowId.INSERT, ToolbarItem.INSERT)),
      InsertTool.primaryEffects(emptyMap()),
    )
    assertEquals(
      listOf(ToolbarEffect.ShowFloatingWindow(ToolbarWindowId.MENU, ToolbarItem.MENU)),
      MenuTool.primaryEffects(emptyMap()),
    )
    assertEquals(
      ToolbarText(
        "Settings: tap to open; Side1 opens quick panel",
        "Settings: tap to open; Side1 opens quick panel",
      ),
      MenuTool.contentDescription(menuOpen),
    )
    assertTrue(ShortcutScene.INSERT in InsertTool.scenes)
    assertTrue(ShortcutScene.PAGE_MANAGER in PageManagerTool.scenes)
  }

  @Test fun pageAndNotebookGesturesAreDeclaredByTheTool() {
    assertEquals(
      listOf(
        ToolbarEffect.ShowFloatingWindow(ToolbarWindowId.PAGE_MANAGER, ToolbarItem.PAGES),
      ),
      PageManagerTool.primaryEffects(emptyMap()),
    )
    assertEquals("page.add", PageManagerTool.gestures.getValue(ToolbarGesture.SIDE_1).commandId)
    assertEquals("page.previous", PageManagerTool.gestures.getValue(ToolbarGesture.SIDE_2).commandId)
    assertEquals("page.next", PageManagerTool.gestures.getValue(ToolbarGesture.SIDE_3).commandId)
    assertTrue(PageManagerTool.isSelected(state(pageManagerOpen = true)))

    assertEquals(
      listOf(ToolbarEffect.OpenFullScreen(ToolbarScreenId.NOTEBOOK_MANAGER)),
      NotebooksTool.primaryEffects(emptyMap()),
    )
    assertEquals(
      "notebook.current",
      NotebooksTool.gestures.getValue(ToolbarGesture.SIDE_1).commandId,
    )
    assertEquals(
      "notebook.remaining",
      NotebooksTool.gestures.getValue(ToolbarGesture.SIDE_2).commandId,
    )
    assertEquals(
      listOf(ToolbarEffect.OpenFullScreen(ToolbarScreenId.EXPORT_MANAGER)),
      ExportTool.primaryEffects(emptyMap()),
    )
  }

  @Test fun immediateActionsUseTheCommandContextAvailability() {
    assertFalse(UndoTool.isAvailable(CommandContext(canUndo = false)))
    assertTrue(UndoTool.isAvailable(CommandContext(canUndo = true)))
    assertFalse(RedoTool.isAvailable(CommandContext(canRedo = false)))
    assertTrue(RedoTool.isAvailable(CommandContext(canRedo = true)))
    assertFalse(DeleteTool.isAvailable(CommandContext(selection = false)))
    assertTrue(DeleteTool.isAvailable(CommandContext(selection = true)))

    assertEquals(listOf(ToolbarEffect.Undo), UndoTool.primaryEffects(emptyMap()))
    assertEquals(listOf(ToolbarEffect.Redo), RedoTool.primaryEffects(emptyMap()))
    assertEquals(listOf(ToolbarEffect.DeleteSelection), DeleteTool.primaryEffects(emptyMap()))
  }

  private fun allTools(): List<ToolbarTool> = listOf(
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

  private fun state(
    visibleItems: Set<ToolbarItem> = ToolbarItem.entries
      .filterNot { it == ToolbarItem.MENU }
      .toSet(),
    toolKind: ToolKind = ToolKind.PEN,
    eraserMode: EraserMode = EraserMode.WHOLE_STROKE,
    penSettings: PenToolbarSettings = PenToolbarSettings(),
    openFlyout: ToolbarFlyoutRequest? = null,
    pageManagerOpen: Boolean = false,
    hasPdf: Boolean = false,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    selection: Boolean = false,
  ): ToolbarUiState = ToolbarUiState(
    visibleItems = visibleItems,
    toolKind = toolKind,
    eraserMode = eraserMode,
    penSettings = penSettings,
    openFlyout = openFlyout,
    pageManagerOpen = pageManagerOpen,
    notebookManagerOpen = false,
    exportPanelOpen = false,
    commandContext = CommandContext(
      hasPdf = hasPdf,
      canUndo = canUndo,
      canRedo = canRedo,
      selection = selection,
    ),
  )
}
