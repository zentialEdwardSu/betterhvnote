package com.betterhv.note

import com.betterhv.note.doc.SelectionSet
import com.betterhv.note.ink.Bounds
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.UUID

class TailEraserSelectionTest {
  @Test fun onlyNonemptySelectionAtDownIsDeleted() {
    val selection = SelectionSet(listOf(UUID.randomUUID()), Bounds(10f, 20f, 40f, 60f))
    assertTrue(tailErasesSelection(selection, 10f, 60f))
    assertTrue(tailErasesSelection(selection, 25f, 35f))
    assertFalse(tailErasesSelection(selection, 9f, 35f))
    assertFalse(tailErasesSelection(selection, Float.NaN, 35f))
    assertFalse(tailErasesSelection(SelectionSet.EMPTY, 0f, 0f))
  }

  @Test fun selectionAllowsTailButModalStillBlocksIt() {
    assertTrue(UiInputBlockState(richObjectSelected = true).blocked)
    assertFalse(UiInputBlockState(richObjectSelected = true).eraserBlocked)
    assertFalse(UiInputBlockState(pdfRegionDialogOpen = true).eraserBlocked)
    assertTrue(UiInputBlockState(richObjectSelected = true, settingsOpen = true).eraserBlocked)
    assertTrue(UiInputBlockState(notebookBusy = true).eraserBlocked)
    assertTrue(UiInputBlockState(linkedNotePlacementOpen = true).eraserBlocked)
  }
}
