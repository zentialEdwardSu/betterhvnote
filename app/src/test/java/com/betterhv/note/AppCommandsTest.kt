package com.betterhv.note

import com.betterhv.note.toolbar.ToolbarCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AppCommandsTest {
  @Test fun everyToolbarAndMenuActionResolves() {
    val bindings = ToolbarCatalog.tools.flatMap { tool ->
      listOf(CommandBinding("editor.${tool.id.storageId}")) +
        tool.gestures.values.map { CommandBinding(it.commandId, it.arguments) }
    } +
      CommandRegistry.menus.values.flatten().map(::CommandBinding)
    bindings.forEach { binding ->
      assertNotNull(binding.commandId, CommandRegistry.find(binding.commandId))
      assertTrue(CommandRegistry.find(binding.commandId)!!.accepts(binding))
    }
    assertEquals(CommandRegistry.commands.size, CommandRegistry.commands.map { it.id }.toSet().size)
  }

  @Test fun dispatcherUsesSameGatesForToolbarAndHardware() {
    var context = CommandContext()
    val executed = mutableListOf<CommandBinding>()
    val dispatcher = CommandDispatcher({ context }, { executed += it })
    val undo = CommandBinding("editor.undo")
    assertFalse(dispatcher.dispatch(undo))
    context = context.copy(canUndo = true)
    assertTrue(dispatcher.dispatch(undo))
    context = context.copy(blocked = true)
    assertFalse(dispatcher.dispatch(undo))
    context = context.copy(blocked = false, scene = ShortcutScene.INSERT)
    assertFalse(dispatcher.dispatch(undo))
    assertEquals(listOf(undo), executed)
  }

  @Test fun parametersDefaultAndValidateBeforeDispatch() {
    val executed = mutableListOf<CommandBinding>()
    val dispatcher = CommandDispatcher({ CommandContext() }, { executed += it })
    assertTrue(dispatcher.dispatch(CommandBinding("pen.width", mapOf("value" to "3"))))
    assertEquals(mapOf("slot" to "current", "value" to "3"), executed.single().arguments)
    assertFalse(dispatcher.dispatch(CommandBinding("pen.width", mapOf("slot" to "7"))))
    assertFalse(dispatcher.dispatch(CommandBinding("pen.color", mapOf("unknown" to "1"))))
    assertFalse(dispatcher.dispatch(CommandBinding("removed.command")))
  }

  @Test fun commandBindingsPreserveLegacyAndUnknownIdsPerScene() {
    var bindings = HardwareShortcutBindings()
      .bind(ShortcutScene.EDITOR, HardwareKeyId.K1, ShortcutAction.EDITOR_TAIL_ERASER)
    assertEquals("editor.tail_eraser", bindings.command(ShortcutScene.EDITOR, HardwareKeyId.K1)!!.commandId)
    val width = CommandBinding("pen.width", mapOf("slot" to "2", "value" to "4"))
    bindings = bindings.bindCommand(ShortcutScene.EDITOR, HardwareKeyId.K1, width)
      .bindCommand(ShortcutScene.INSERT, HardwareKeyId.K1, CommandBinding("future.command"))
    assertEquals(width, bindings.command(ShortcutScene.EDITOR, HardwareKeyId.K1))
    assertEquals("future.command", bindings.command(ShortcutScene.INSERT, HardwareKeyId.K1)!!.commandId)
    val cleared = bindings.bindCommand(ShortcutScene.EDITOR, HardwareKeyId.K1, null)
    assertNull(cleared.command(ShortcutScene.EDITOR, HardwareKeyId.K1))
    assertNull(bindings.clear(ShortcutScene.INSERT).command(ShortcutScene.INSERT, HardwareKeyId.K1))
  }
}
