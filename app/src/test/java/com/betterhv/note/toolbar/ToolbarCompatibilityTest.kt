package com.betterhv.note.toolbar

import com.betterhv.note.CommandBinding
import com.betterhv.note.CommandRegistry
import com.betterhv.note.HardwareKeyId
import com.betterhv.note.HardwareShortcutBindings
import com.betterhv.note.ShortcutAction
import com.betterhv.note.ShortcutScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolbarCompatibilityTest {
  @Test fun legacyShortcutActionsStillResolveToCommands() {
    val bindings = HardwareShortcutBindings()
      .bind(ShortcutScene.EDITOR, HardwareKeyId.K1, ShortcutAction.EDITOR_UNDO)
      .bind(ShortcutScene.PAGE_MANAGER, HardwareKeyId.K2, ShortcutAction.PAGE_NEXT)
    assertEquals(
      "editor.undo",
      bindings.command(ShortcutScene.EDITOR, HardwareKeyId.K1)?.commandId,
    )
    assertEquals(
      "page.next",
      bindings.command(ShortcutScene.PAGE_MANAGER, HardwareKeyId.K2)?.commandId,
    )
    assertNotNull(CommandRegistry.find("editor.undo"))
    assertNotNull(CommandRegistry.find("page.next"))
  }

  @Test fun invalidPersistedBindingStaysInvalidWithoutThrowing() {
    assertNull(CommandRegistry.find("future.command"))
    assertEquals(
      "失效绑定：future.command",
      CommandRegistry.title(CommandBinding("future.command")),
    )
  }

  @Test fun toolCommandsDoNotNeedShortcutActionEnumEntries() {
    assertFalse(ShortcutAction.entries.any { it.storageId == "notebook.current" })
    assertNotNull(CommandRegistry.find("editor.notebooks"))
    assertNotNull(CommandRegistry.find("editor.pages"))
  }
}
