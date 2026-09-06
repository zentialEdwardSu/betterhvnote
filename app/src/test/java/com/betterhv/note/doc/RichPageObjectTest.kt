package com.betterhv.note.doc

import com.betterhv.note.doc.commands.UpdateObjectCommand
import com.betterhv.note.ink.Bounds
import com.betterhv.note.storage.PageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.math.PI

class RichPageObjectTest {
  @Test
  fun exifQuarterTurnSwapsImageLocalBounds() {
    val image = ImageObject(
      UUID.randomUUID(),
      assetPath = "assets/portrait.jpg",
      mimeType = "image/jpeg",
      pixelWidth = 4000,
      pixelHeight = 3000,
      exifOrientation = 6,
    )
    assertEquals(3000f, image.localBounds.right, 0f)
    assertEquals(4000f, image.localBounds.bottom, 0f)
  }

  @Test
  fun rotateAboutKeepsCenterFixedAndRotatesCorner() {
    val transform = Transform2D.rotateAbout(10f, 20f, (PI / 2).toFloat())
    val center = transform.mapPoint(10f, 20f)
    val corner = transform.mapPoint(20f, 20f)
    assertEquals(10f, center[0], 0.001f)
    assertEquals(20f, center[1], 0.001f)
    assertEquals(10f, corner[0], 0.001f)
    assertEquals(30f, corner[1], 0.001f)
  }

  @Test
  fun textStyleUpdateIsUndoableAndSnapshotKeepsRichObjects() {
    val page = Page(width = 500f, height = 700f)
    val before = TextObject(
      id = UUID.randomUUID(),
      text = "hello",
      fontSize = 24f,
      localBounds = Bounds(0f, 0f, 60f, 30f),
    )
    page.addObject(before)
    val after = before.copy(
      fontFamily = TextFontFamily.SERIF,
      fontSize = 32f,
      localBounds = Bounds(0f, 0f, 80f, 40f),
    )
    val stack = CommandStack()
    stack.execute(UpdateObjectCommand(page, before, after))
    assertEquals(after, page.getObject(before.id))
    stack.undo()
    assertEquals(before, page.getObject(before.id))

    val image = ImageObject(
      UUID.randomUUID(),
      assetPath = "assets/example.png",
      mimeType = "image/png",
      pixelWidth = 100,
      pixelHeight = 50,
    )
    page.addObject(image)
    val snapshot = PageSnapshot.capture(page)
    assertTrue(snapshot.objects.any { it is TextObject })
    assertTrue(snapshot.objects.any { it is ImageObject })
  }
}
