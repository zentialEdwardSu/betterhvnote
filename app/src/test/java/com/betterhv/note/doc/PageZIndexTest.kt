package com.betterhv.note.doc

import com.betterhv.note.doc.commands.DeleteObjectsCommand
import com.betterhv.note.doc.commands.SplitStrokeCommand
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.Stroke
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class PageZIndexTest {
  @Test fun nextZIndexStartsAtZeroOnEmptyPageAndAdvancesAboveCurrentMax() {
    val page = Page()
    assertEquals(0, page.nextZIndex())

    val first = strokeObject(zIndex = 0)
    page.addObject(first)
    assertEquals(1, page.nextZIndex())

    val fifth = strokeObject(zIndex = 5)
    page.addObject(fifth)
    assertEquals(6, page.nextZIndex())

    page.removeObject(fifth.id)
    assertEquals(1, page.nextZIndex())
  }

  @Test fun deleteObjectsCommandUndoRestoresObjectsInOriginalZIndexOrder() {
    val page = Page()
    val commands = CommandStack()
    val strokeZero = strokeObject(zIndex = 0)
    val imageOne = ImageObject(
      id = UUID.randomUUID(),
      zIndex = 1,
      assetPath = "assets/photo.png",
      mimeType = "image/png",
      pixelWidth = 40,
      pixelHeight = 40,
    )
    val textTwo = TextObject(
      id = UUID.randomUUID(),
      zIndex = 2,
      text = "Top note",
      localBounds = Bounds(0f, 0f, 80f, 24f),
    )
    page.addObject(strokeZero)
    page.addObject(imageOne)
    page.addObject(textTwo)

    commands.execute(DeleteObjectsCommand(page, listOf(strokeZero.id, imageOne.id)))
    assertEquals(listOf(textTwo.id), page.scene.all().map(PageObject::id))

    commands.undo()
    assertEquals(listOf(0, 1, 2), page.scene.all().map(PageObject::zIndex))
    assertEquals(listOf(strokeZero.id, imageOne.id, textTwo.id), page.scene.all().map(PageObject::id))
    assertEquals(textTwo, page.getObject(textTwo.id))
  }

  @Test fun splitStrokeCommandExecuteAndUndoPreserveZIndexOrder() {
    val page = Page()
    val commands = CommandStack()
    val baseStroke = strokeObject(zIndex = 0)
    val middleStroke = strokeObject(zIndex = 1)
    val topText = TextObject(
      id = UUID.randomUUID(),
      zIndex = 2,
      text = "Pinned above",
      localBounds = Bounds(0f, 0f, 100f, 30f),
    )
    page.addObject(baseStroke)
    page.addObject(middleStroke)
    page.addObject(topText)

    val pieceA = strokeObject(zIndex = middleStroke.zIndex)
    val pieceB = strokeObject(zIndex = middleStroke.zIndex)
    commands.execute(SplitStrokeCommand(page, middleStroke.id, listOf(pieceA, pieceB)))

    assertEquals(listOf(0, 1, 1, 2), page.scene.all().map(PageObject::zIndex))
    assertEquals(
      listOf(baseStroke.id, pieceA.id, pieceB.id, topText.id),
      page.scene.all().map(PageObject::id),
    )
    assertEquals(topText, page.getObject(topText.id))

    commands.undo()
    assertEquals(listOf(0, 1, 2), page.scene.all().map(PageObject::zIndex))
    assertEquals(
      listOf(baseStroke.id, middleStroke.id, topText.id),
      page.scene.all().map(PageObject::id),
    )
    assertEquals(topText, page.getObject(topText.id))
  }

  @Test fun equalZIndexObjectsPreserveCreatedAtOrderAcrossDeleteUndoAndSplitStroke() {
    val page = Page()
    val commands = CommandStack()
    val first = strokeObject(zIndex = 0, createdAt = 100L)
    val second = strokeObject(zIndex = 0, createdAt = 200L)
    val third = strokeObject(zIndex = 0, createdAt = 300L)
    page.addObject(first)
    page.addObject(second)
    page.addObject(third)

    commands.execute(DeleteObjectsCommand(page, listOf(second.id, first.id)))
    assertEquals(listOf(third.id), page.scene.all().map(PageObject::id))

    commands.undo()
    assertEquals(listOf(first.id, second.id, third.id), page.scene.all().map(PageObject::id))

    val pieceA = strokeObject(zIndex = 0, createdAt = 200L)
    val pieceB = strokeObject(zIndex = 0, createdAt = 200L)
    commands.execute(SplitStrokeCommand(page, second.id, listOf(pieceA, pieceB)))
    assertEquals(
      listOf(first.id, pieceA.id, pieceB.id, third.id),
      page.scene.all().map(PageObject::id),
    )

    commands.undo()
    assertEquals(listOf(first.id, second.id, third.id), page.scene.all().map(PageObject::id))
  }

  private fun strokeObject(zIndex: Int, createdAt: Long = 0L): StrokeObject = StrokeObject(
    id = UUID.randomUUID(),
    transform = Transform2D.IDENTITY,
    zIndex = zIndex,
    createdAt = createdAt,
    stroke = Stroke(
      points = listOf(InkPoint(10f, 10f, 0.5f, 1L), InkPoint(40f, 25f, 0.7f, 2L)),
      style = PenStyle(),
    ),
  )
}
