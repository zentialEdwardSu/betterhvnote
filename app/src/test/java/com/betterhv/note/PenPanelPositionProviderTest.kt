package com.betterhv.note

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class PenPanelPositionProviderTest {
  private val window = IntSize(1000, 800)
  private val popup = IntSize(300, 250)

  @Test
  fun panelIsPlacedOutsideEachDockEdgeWithTwelvePixelGap() {
    assertEquals(
      IntOffset(64, 275),
      position(DockEdge.START, IntRect(8, 378, 52, 422)),
    )
    assertEquals(
      IntOffset(636, 275),
      position(DockEdge.END, IntRect(948, 378, 992, 422)),
    )
    assertEquals(
      IntOffset(272, 64),
      position(DockEdge.TOP, IntRect(400, 8, 444, 52)),
    )
    assertEquals(
      IntOffset(272, 486),
      position(DockEdge.BOTTOM, IntRect(400, 748, 444, 792)),
    )
  }

  @Test
  fun orthogonalPositionIsClampedInsideWindow() {
    assertEquals(
      IntOffset(64, 0),
      position(DockEdge.START, IntRect(8, 0, 52, 44)),
    )
    assertEquals(
      IntOffset(0, 64),
      position(DockEdge.TOP, IntRect(0, 8, 44, 52)),
    )
  }

  private fun position(edge: DockEdge, anchor: IntRect): IntOffset =
    PenPanelPositionProvider(edge, gapPx = 12).calculatePosition(
      anchorBounds = anchor,
      windowSize = window,
      layoutDirection = LayoutDirection.Ltr,
      popupContentSize = popup,
    )
}
