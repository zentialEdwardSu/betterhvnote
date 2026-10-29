package com.betterhv.note.toolbar

import com.betterhv.note.CommandBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolbarCommandRouterTest {
  private val router = ToolbarCommandRouter()

  @Test fun routesPrimaryAndSharedToolbarCommands() {
    assertNotNull(router.action(CommandBinding("editor.pages")))
    assertNotNull(router.action(CommandBinding("editor.pen_2")))
    assertNotNull(router.action(CommandBinding("pen.settings", mapOf("slot" to "1"))))
    assertNotNull(router.action(CommandBinding("page.add")))
    assertNotNull(router.action(CommandBinding("notebook.current")))
  }

  @Test fun leavesNonToolbarCommandsForTheLegacyExecutor() {
    assertNull(router.action(CommandBinding("pen.type")))
    assertNull(router.action(CommandBinding("insert.image")))
    assertNull(router.action(CommandBinding("settings.app")))
    assertNull(router.action(CommandBinding("editor.toggle_toolbar")))
  }

  @Test fun routedPageCommandStillCarriesItsEffectPlan() {
    val action = requireNotNull(router.action(CommandBinding("editor.pages")))
    assertEquals(
      listOf(
        ToolbarEffect.ShowFloatingWindow(
          ToolbarWindowId.PAGE_MANAGER,
          com.betterhv.note.ToolbarItem.PAGES,
        ),
      ),
      action.effects(emptyMap()),
    )
  }
}
