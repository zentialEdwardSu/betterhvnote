package com.betterhv.note

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiInputBlockStateTest {
    @Test fun emptyStateLeavesCanvasEnabled() {
        assertFalse(UiInputBlockState().blocked)
    }

    @Test fun fullScreenAndModalSurfacesBlockCanvas() {
        assertTrue(UiInputBlockState(exportPanelOpen = true).blocked)
        assertTrue(UiInputBlockState(notebookNameOpen = true).blocked)
        assertTrue(UiInputBlockState(textEditorOpen = true).blocked)
        assertTrue(UiInputBlockState(insertionOpen = true).blocked)
    }

    @Test fun everyToolbarPopupAndGuardedSurfaceBlocksCanvas() {
        assertTrue(UiInputBlockState(toolbarPopupOpen = true).blocked)
        assertTrue(UiInputBlockState(toolbarInteraction = true).blocked)
        assertTrue(UiInputBlockState(pageControlInteraction = true).blocked)
        assertTrue(UiInputBlockState(snackbarInteraction = true).blocked)
        assertTrue(UiInputBlockState(debugInteraction = true).blocked)
    }
}
