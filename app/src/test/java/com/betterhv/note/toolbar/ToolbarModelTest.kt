package com.betterhv.note.toolbar

import com.betterhv.note.AppCommand
import com.betterhv.note.CommandBinding
import com.betterhv.note.CommandContext
import com.betterhv.note.EraserMode
import com.betterhv.note.PenToolbarSettings
import com.betterhv.note.ShortcutScene
import com.betterhv.note.ToolKind
import com.betterhv.note.ToolbarItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolbarModelTest {
  @Test fun toolbarTextResolvesBothLanguages() {
    val text = ToolbarText("页面", "Pages")
    assertEquals("页面", text.localized(isChinese = true))
    assertEquals("Pages", text.localized(isChinese = false))
  }

  @Test fun toolbarUiStateKeepsPureRuntimeValues() {
    val state = ToolbarUiState(
      visibleItems = setOf(ToolbarItem.PEN_1),
      toolKind = ToolKind.PEN,
      eraserMode = EraserMode.POINT,
      penSettings = PenToolbarSettings(activeSlot = 1),
      openFlyout = null,
      pageManagerOpen = false,
      notebookManagerOpen = false,
      exportPanelOpen = false,
      commandContext = CommandContext(),
    )

    assertEquals(ToolbarItem.PEN_1, state.visibleItems.single())
    assertEquals(1, state.penSettings.activeSlot)
    assertNull(state.openFlyout)
  }

  @Test fun actionsAndEffectsArePlainValues() {
    val command = AppCommand(
      id = "editor.test",
      label = "测试",
      category = "测试",
      scenes = setOf(ShortcutScene.EDITOR),
    )
    val action = ToolbarActionSpec(command) { arguments ->
      listOf(
        ToolbarEffect.SelectPenSlot(arguments["slot"]?.toIntOrNull()),
        ToolbarEffect.ShowFloatingWindow(ToolbarWindowId.PEN_SETTINGS, ToolbarItem.PEN_1),
      )
    }

    val effects = action.effects(mapOf("slot" to "1"))
    assertEquals(command, action.command)
    assertEquals(ToolbarEffect.SelectPenSlot(1), effects[0])
    assertEquals(
      ToolbarEffect.ShowFloatingWindow(ToolbarWindowId.PEN_SETTINGS, ToolbarItem.PEN_1),
      effects[1],
    )
  }

  @Test fun floatingWindowSpecsDescribePresentationWithoutUiTypes() {
    val spec = ToolbarWindowSpec(
      id = ToolbarWindowId.INSERT,
      presentation = FloatingPresentation.FLYOUT,
      anchor = ToolbarItem.INSERT,
      openPolicy = FloatingOpenPolicy.OPEN,
      title = ToolbarText("菜单", "菜单"),
      content = ToolbarWindowContent.Commands(listOf(CommandBinding("insert.image"))),
    )

    assertEquals(ToolbarWindowId.INSERT, spec.id)
    assertEquals(ToolbarItem.INSERT, spec.anchor)
    assertTrue(spec.content is ToolbarWindowContent.Commands)
    assertEquals(
      ToolbarWindowId.INSERT,
      ToolbarFlyoutRequest(ToolbarWindowId.INSERT, ToolbarItem.INSERT).window,
    )
  }

  @Test fun toolbarModelHasNoAndroidOrComposeImports() {
    val sourceDir = findToolbarSourceDir()
    val forbidden = listOf(
      "import android.",
      "import androidx.compose",
      "androidx.compose",
      "PenDrawView",
      "MainActivity",
    )

    sourceDir.walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .forEach { file ->
        val source = file.readText()
        forbidden.forEach { token ->
          assertFalse("$token must not appear in ${file.name}", source.contains(token))
        }
      }
  }

  private fun findToolbarSourceDir(): File {
    var current: File? = File(".").absoluteFile
    while (current != null) {
      File(current, "src/main/java/com/betterhv/note/toolbar").takeIf { it.isDirectory }?.let {
        return it
      }
      File(current, "app/src/main/java/com/betterhv/note/toolbar").takeIf { it.isDirectory }?.let {
        return it
      }
      current = current.parentFile
    }
    error("Could not locate toolbar source directory from ${File(".").absolutePath}")
  }
}
