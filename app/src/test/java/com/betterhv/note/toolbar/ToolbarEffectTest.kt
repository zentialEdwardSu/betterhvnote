package com.betterhv.note.toolbar

import com.betterhv.note.CommandBinding
import com.betterhv.note.CommandContext
import com.betterhv.note.CommandDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarEffectTest {
  @Test fun everyToolbarActionProducesAnEffectPlan() {
    ToolbarCatalog.actions.forEach { action ->
      val effects = action.effects(action.command.defaults())
      assertTrue("${action.command.id} should request an effect", effects.isNotEmpty())
    }
  }

  @Test fun disabledCommandsNeverExecuteEffects() {
    var executed = false
    val dispatcher = CommandDispatcher(
      context = { CommandContext(blocked = true) },
      execute = { executed = true },
    )
    assertFalse(dispatcher.dispatch(CommandBinding("editor.undo")))
    assertFalse(executed)
  }

  @Test fun penSettingsSlotCurrentAndExplicitArgumentsArePreserved() {
    val action = requireNotNull(ToolbarCatalog.actionFor("pen.settings"))
    val current = action.effects(mapOf("slot" to "current"))
    assertEquals(ToolbarEffect.SelectPenSlot(null), current[0])
    assertEquals(ToolbarEffect.SetCanvasTool(com.betterhv.note.ToolKind.PEN), current[1])

    val explicit = action.effects(mapOf("slot" to "2"))
    assertEquals(ToolbarEffect.SelectPenSlot(2), explicit[0])
    assertEquals(
      ToolbarEffect.ShowFloatingWindow(
        ToolbarWindowId.PEN_SETTINGS,
        arguments = mapOf("slot" to "2"),
      ),
      explicit[2],
    )
  }

  @Test fun pageAndNotebookSideEffectsAreExplicit() {
    assertNotNull(ToolbarCatalog.actionFor("page.add"))
    assertEquals(
      listOf(ToolbarEffect.AddPage),
      ToolbarCatalog.actionFor("page.add")!!.effects(emptyMap()),
    )
    assertEquals(
      listOf(ToolbarEffect.CreateNotebook(NotebookScope.CURRENT_TO_END)),
      ToolbarCatalog.actionFor("notebook.remaining")!!.effects(emptyMap()),
    )
  }
}
