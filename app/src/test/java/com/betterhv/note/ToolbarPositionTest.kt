package com.betterhv.note

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarPositionTest {
  private val container = IntSize(1000, 800)
  private val toolbar = IntSize(100, 300)

  @Test fun positionsAtEveryDockEdgeAndAlongFraction() {
    assertEquals(10, toolbarPosition(container, toolbar, DockEdge.START, 0f, 10).x)
    assertEquals(890, toolbarPosition(container, toolbar, DockEdge.END, 1f, 10).x)
    assertEquals(10, toolbarPosition(container, toolbar, DockEdge.TOP, 0f, 10).y)
    assertEquals(490, toolbarPosition(container, toolbar, DockEdge.BOTTOM, 1f, 10).y)
    assertEquals(250, toolbarPosition(container, toolbar, DockEdge.START, 0.5f, 10).y)
    assertEquals(450, toolbarPosition(container, toolbar, DockEdge.TOP, 0.5f, 10).x)
  }

  @Test fun fractionIsClamped() {
    assertEquals(
      toolbarPosition(container, toolbar, DockEdge.TOP, 0f, 10),
      toolbarPosition(container, toolbar, DockEdge.TOP, -2f, 10),
    )
    assertEquals(
      toolbarPosition(container, toolbar, DockEdge.TOP, 1f, 10),
      toolbarPosition(container, toolbar, DockEdge.TOP, 2f, 10),
    )
  }

  @Test fun dockingUsesToolbarBoundsInsteadOfItsCenter() {
    assertEquals(
      DockEdge.TOP,
      nearestDockEdge(container, toolbar, Offset(200f, 0f)),
    )
    assertEquals(
      DockEdge.BOTTOM,
      nearestDockEdge(container, toolbar, Offset(200f, 500f)),
    )
    assertEquals(
      DockEdge.START,
      nearestDockEdge(container, toolbar, Offset(0f, 200f)),
    )
    assertEquals(
      DockEdge.END,
      nearestDockEdge(container, toolbar, Offset(900f, 200f)),
    )
  }

  @Test fun topAndBottomWinCornerTiesForHorizontalLayout() {
    assertEquals(
      DockEdge.TOP,
      nearestDockEdge(container, toolbar, Offset(0f, 0f)),
    )
    assertEquals(
      DockEdge.BOTTOM,
      nearestDockEdge(container, toolbar, Offset(900f, 500f)),
    )
  }
}
