package com.betterhv.note.toolbar

import com.betterhv.note.CommandBinding
import com.betterhv.note.CommandDispatcher
import com.betterhv.note.CommandContext
import com.betterhv.note.CommandRegistry
import com.betterhv.note.NonToolbarCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarCommandRegistryTest {
  @Test fun toolbarAndSharedCommandsAreRegisteredExactlyOnce() {
    val ids = CommandRegistry.commands.map { it.id }
    assertEquals(ids.size, ids.toSet().size)
    assertTrue(ids.containsAll(ToolbarCatalog.commands.map { it.id }))
    assertNotNull(CommandRegistry.find("editor.pages"))
    assertNotNull(CommandRegistry.find("editor.pen_1"))
    assertNotNull(CommandRegistry.find("editor.menu"))
    assertNotNull(CommandRegistry.find("pen.settings"))
    assertNotNull(CommandRegistry.find("page.add"))
    assertNotNull(CommandRegistry.find("notebook.current"))
  }

  @Test fun penSettingsKeepsLegacyDefaultArgumentsAndBindingListIsUsable() {
    val command = requireNotNull(CommandRegistry.find("pen.settings"))
    assertEquals(mapOf("slot" to "current"), command.defaults())
    assertTrue(command.accepts(CommandBinding("pen.settings")))
    assertTrue(command.accepts(CommandBinding("pen.settings", mapOf("slot" to "2"))))
    assertEquals(
      "打开笔设置 · 当前笔槽",
      CommandRegistry.title(CommandBinding("pen.settings")),
    )
  }

  @Test fun everyWindowAndLegacyMenuEntryResolves() {
    ToolbarCatalog.windows.values.forEach { window ->
      when (val content = window.content) {
        is ToolbarWindowContent.Commands -> content.entries
        is ToolbarWindowContent.Menu -> content.entries
        else -> emptyList()
      }.forEach { binding ->
        assertNotNull("${window.id}: ${binding.commandId}", CommandRegistry.find(binding.commandId))
      }
    }
    CommandRegistry.menus.values.flatten().forEach { id ->
      assertNotNull("legacy menu $id", CommandRegistry.find(id))
    }
  }

  @Test fun dispatcherCanUseLegacyToolBindingAndNewSharedCommand() {
    val executed = mutableListOf<CommandBinding>()
    val dispatcher = CommandDispatcher(
      context = {
        CommandContext(
          canUndo = true,
          scene = com.betterhv.note.ShortcutScene.EDITOR,
        )
      },
      execute = { executed += it },
    )

    assertTrue(dispatcher.dispatch(CommandBinding("editor.undo")))
    assertTrue(
      dispatcher.dispatch(
        CommandBinding("pen.settings", mapOf("slot" to "1")),
      ),
    )
    assertEquals("editor.undo", executed[0].commandId)
    assertEquals("pen.settings", executed[1].commandId)
    assertEquals("1", executed[1].arguments["slot"])
    assertTrue(NonToolbarCommands.all.any { it.id == "pen.type" })
  }
}
