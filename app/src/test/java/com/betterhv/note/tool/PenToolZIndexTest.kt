package com.betterhv.note.tool

import com.betterhv.note.doc.CommandStack
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.PageObject
import com.betterhv.note.doc.SelectionSet
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.TextObject
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.OneEuroSmoother
import com.betterhv.note.ink.PenStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PenToolZIndexTest {
  @Test fun strokeDrawnAfterImageAndTextReceivesStrictlyHigherZIndex() {
    val page = Page()
    val image = ImageObject(
      id = UUID.randomUUID(),
      zIndex = 2,
      assetPath = "assets/sample.png",
      mimeType = "image/png",
      pixelWidth = 100,
      pixelHeight = 80,
    )
    val text = TextObject(
      id = UUID.randomUUID(),
      zIndex = 5,
      text = "Caption",
      localBounds = Bounds(0f, 0f, 80f, 24f),
    )
    page.addObject(image)
    page.addObject(text)

    val commands = CommandStack()
    val tool = PenTool(
      page = page,
      commandStack = commands,
      host = noopHost(),
      styleProvider = { PenStyle() },
      smootherProvider = { OneEuroSmoother() },
    )

    drawSampleStroke(tool, startX = 10f)

    val objects = page.scene.all()
    assertEquals(3, objects.size)
    val added = objects.last() as StrokeObject
    assertTrue("stroke zIndex ${added.zIndex} must exceed existing max 5", added.zIndex > 5)
    assertEquals(6, added.zIndex)
  }

  @Test fun consecutiveStrokesStartAtZeroAndIncreaseMonotonically() {
    val page = Page()
    val commands = CommandStack()
    val tool = PenTool(
      page = page,
      commandStack = commands,
      host = noopHost(),
      styleProvider = { PenStyle() },
      smootherProvider = { OneEuroSmoother() },
    )

    drawSampleStroke(tool, startX = 10f)
    drawSampleStroke(tool, startX = 100f)

    assertEquals(listOf(0, 1), page.scene.all().map(PageObject::zIndex))

    commands.undo()
    drawSampleStroke(tool, startX = 200f)
    assertEquals(listOf(0, 1), page.scene.all().map(PageObject::zIndex))
  }

  private fun drawSampleStroke(tool: PenTool, startX: Float) {
    tool.onDown(startX, 10f)
    tool.onBatch(
      listOf(
        InkPoint(startX, 10f, 0.5f, 1L),
        InkPoint(startX + 30f, 40f, 0.7f, 2L),
        InkPoint(startX + 60f, 55f, 0.8f, 3L),
      ),
    )
    tool.onUp()
  }

  private fun noopHost(): ToolHost = object : ToolHost {
    override fun requestRepaint(bounds: Bounds) = Unit
    override fun onSelectionChanged(selection: SelectionSet) = Unit
  }
}
