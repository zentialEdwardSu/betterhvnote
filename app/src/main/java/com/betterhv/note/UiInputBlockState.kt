package com.betterhv.note

internal data class UiInputBlockState(
  val pageManagerOpen: Boolean = false,
  val toolbarPopupOpen: Boolean = false,
  val settingsOpen: Boolean = false,
  val notebookManagerOpen: Boolean = false,
  val notebookNameOpen: Boolean = false,
  val insertionOpen: Boolean = false,
  val textEditorOpen: Boolean = false,
  val notebookBusy: Boolean = false,
  val exportPanelOpen: Boolean = false,
  val richObjectSelected: Boolean = false,
  val toolbarInteraction: Boolean = false,
  val pageControlInteraction: Boolean = false,
  val snackbarInteraction: Boolean = false,
  val debugInteraction: Boolean = false,
  val pdfRegionDialogOpen: Boolean = false,
  val linkedNotePlacementOpen: Boolean = false,
) {
  val blocked: Boolean
    get() = pageManagerOpen || toolbarPopupOpen || settingsOpen || notebookManagerOpen ||
      notebookNameOpen || insertionOpen || textEditorOpen || notebookBusy || exportPanelOpen ||
      richObjectSelected || toolbarInteraction || pageControlInteraction || snackbarInteraction ||
      debugInteraction || pdfRegionDialogOpen || linkedNotePlacementOpen
}
