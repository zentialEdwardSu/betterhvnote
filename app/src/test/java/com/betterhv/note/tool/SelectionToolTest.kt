package com.betterhv.note.tool

import com.betterhv.note.doc.CommandStack
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.SelectionSet
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.TextObject
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SelectionToolTest {

  @Test
  fun `lasso selects stroke segment crossing region with endpoints outside`() {
    val fixture = fixtureWithStroke(-10f, 50f, 110f, 50f)

    drawLasso(fixture.tool, 0f, 0f, 100f, 100f)

    assertEquals(listOf(fixture.stroke.id), fixture.host.selection.objectIds)
  }

  @Test
  fun `tap sized lasso is rejected like hvNote`() {
    val fixture = fixtureWithStroke(5f, 5f, 15f, 15f)

    drawLasso(fixture.tool, 0f, 0f, 20f, 20f)

    assertTrue(fixture.host.selection.isEmpty)
  }

  @Test
  fun `cancelled move restores object transform`() {
    val fixture = fixtureWithStroke(10f, 50f, 90f, 50f)
    drawLasso(fixture.tool, 0f, 0f, 100f, 100f)
    assertFalse(fixture.host.selection.isEmpty)

    fixture.tool.onDown(50f, 50f)
    fixture.tool.onBatch(listOf(point(65f, 70f)))
    assertEquals(Transform2D.translate(15f, 20f), fixture.page.getObject(fixture.stroke.id)?.transform)

    fixture.tool.onCancel()
    assertEquals(Transform2D.IDENTITY, fixture.page.getObject(fixture.stroke.id)?.transform)
  }

  @Test
  fun `rectangle selects ink text and image as one group`() {
    val page = Page()
    val stroke = StrokeObject(
      UUID.randomUUID(),
      stroke = Stroke(listOf(point(20f, 20f), point(80f, 80f)), PenStyle()),
    )
    val text = TextObject(
      UUID.randomUUID(),
      transform = Transform2D.translate(30f, 30f),
      text = "note",
      localBounds = Bounds(0f, 0f, 40f, 20f),
    )
    val image = ImageObject(
      UUID.randomUUID(),
      transform = Transform2D.translate(60f, 50f),
      assetPath = "assets/test.png",
      mimeType = "image/png",
      pixelWidth = 20,
      pixelHeight = 20,
    )
    page.addObject(stroke);
    page.addObject(text);
    page.addObject(image)
    val host = RecordingHost()
    val tool = SelectionTool(page, CommandStack(), host) { 24f }

    val selection = tool.selectRectangle(Bounds(10f, 10f, 100f, 100f))

    assertEquals(setOf(stroke.id, text.id, image.id), selection.objectIds.toSet())
  }

  @Test
  fun `free lasso selects transformed rich objects`() {
    val page = Page()
    val text = TextObject(
      UUID.randomUUID(),
      transform = Transform2D.translate(30f, 30f),
      text = "note",
      localBounds = Bounds(0f, 0f, 40f, 20f),
    )
    page.addObject(text)
    val host = RecordingHost()
    val tool = SelectionTool(page, CommandStack(), host) { 24f }

    drawLasso(tool, 10f, 10f, 100f, 100f)

    assertEquals(listOf(text.id), host.selection.objectIds)
  }

  @Test
  fun `empty rectangle remains available as a completed PDF region`() {
    val page = Page()
    val tool = SelectionTool(page, CommandStack(), RecordingHost()) { 24f }
    val bounds = Bounds(10f, 20f, 110f, 120f)

    assertTrue(tool.selectRectangle(bounds).isEmpty)
    assertEquals(bounds, tool.consumeCompletedRegion())
  }

  private fun drawLasso(tool: SelectionTool, left: Float, top: Float, right: Float, bottom: Float) {
    tool.onDown(left, top)
    tool.onBatch(
      listOf(
        point(right, top),
        point(right, bottom),
        point(left, bottom),
        point(left, top),
      ),
    )
    tool.onUp()
  }

  private fun fixtureWithStroke(ax: Float, ay: Float, bx: Float, by: Float): Fixture {
    val page = Page()
    val stroke = StrokeObject(
      id = UUID.randomUUID(),
      stroke = Stroke(listOf(point(ax, ay), point(bx, by)), PenStyle()),
    )
    page.addObject(stroke)
    val host = RecordingHost()
    return Fixture(page, stroke, host, SelectionTool(page, CommandStack(), host) { 24f })
  }

  private fun point(x: Float, y: Float) = InkPoint(x, y, 1f, 0L)

  private data class Fixture(val page: Page, val stroke: StrokeObject, val host: RecordingHost, val tool: SelectionTool)

  private class RecordingHost : ToolHost {
    var selection = SelectionSet.EMPTY
    override fun requestRepaint(bounds: Bounds) = Unit
    override fun onSelectionChanged(selection: SelectionSet) {
      this.selection = selection
    }
  }
}
