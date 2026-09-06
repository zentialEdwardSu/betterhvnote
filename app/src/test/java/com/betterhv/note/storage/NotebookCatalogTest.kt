package com.betterhv.note.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class NotebookCatalogTest {
  @Test
  fun arbitrarySelectionIsReturnedInSourceOrder() {
    val pages = List(5) { UUID.randomUUID() }
    val selected = linkedSetOf(pages[4], pages[1], pages[3])

    assertEquals(listOf(pages[1], pages[3], pages[4]), orderedSelectedPages(pages, selected))
  }

  @Test
  fun workingCopyBehaviorAlwaysChoosesWorkingNotebook() {
    val working = UUID.randomUUID()
    val active = UUID.randomUUID()

    assertEquals(
      working,
      resolveStartupNotebookId(StartupBehavior.WORKING_COPY, working, active) { true },
    )
  }

  @Test
  fun lastOpenedFallsBackToWorkingCopyWhenActiveIdIsMissingOrInvalid() {
    val working = UUID.randomUUID()
    val active = UUID.randomUUID()

    assertEquals(
      active,
      resolveStartupNotebookId(StartupBehavior.LAST_OPENED, working, active) { it == active },
    )
    assertEquals(
      working,
      resolveStartupNotebookId(StartupBehavior.LAST_OPENED, working, active) { false },
    )
    assertEquals(
      working,
      resolveStartupNotebookId(StartupBehavior.LAST_OPENED, working, null) { true },
    )
  }
}
