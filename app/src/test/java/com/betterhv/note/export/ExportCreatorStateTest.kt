package com.betterhv.note.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ExportCreatorStateTest {
  @Test fun formatForScopeChangeResetsPngToPdfForMultiPageScopes() {
    assertEquals(
      ExportFormat.PDF,
      ExportCreatorState.formatForScopeChange(ExportFormat.PNG, ExportScope.SELECTED_PAGES),
    )
    assertEquals(
      ExportFormat.PDF,
      ExportCreatorState.formatForScopeChange(ExportFormat.PNG, ExportScope.ALL_PAGES),
    )
    assertEquals(
      ExportFormat.PDF,
      ExportCreatorState.formatForScopeChange(ExportFormat.PDF, ExportScope.SELECTED_PAGES),
    )
    assertEquals(
      ExportFormat.PNG,
      ExportCreatorState.formatForScopeChange(ExportFormat.PNG, ExportScope.SINGLE_PAGE),
    )
    assertEquals(
      ExportFormat.PDF,
      ExportCreatorState.formatForScopeChange(ExportFormat.PDF, ExportScope.SINGLE_PAGE),
    )
  }

  @Test fun resolveNotebookIdPreservesUserSelectionAcrossReload() {
    val nbA = UUID.randomUUID()
    val nbB = UUID.randomUUID()
    val nbC = UUID.randomUUID()

    val initial = ExportCreatorState.resolveNotebookId(null, nbA, listOf(nbA, nbB))
    assertEquals(nbA, initial)

    val afterReload = ExportCreatorState.resolveNotebookId(nbB, nbA, listOf(nbA, nbB, nbC))
    assertEquals(nbB, afterReload)
  }

  @Test fun resolveNotebookIdFallsBackWhenSelectedOrInitialNotebookIsDeletedOrEmpty() {
    val nbA = UUID.randomUUID()
    val nbB = UUID.randomUUID()
    val nbC = UUID.randomUUID()
    val nbD = UUID.randomUUID()

    assertEquals(
      nbA,
      ExportCreatorState.resolveNotebookId(nbB, nbA, listOf(nbA, nbC)),
    )
    assertEquals(
      nbC,
      ExportCreatorState.resolveNotebookId(nbB, nbA, listOf(nbC, nbD)),
    )
    assertNull(
      ExportCreatorState.resolveNotebookId(nbB, nbA, emptyList()),
    )
  }
}
