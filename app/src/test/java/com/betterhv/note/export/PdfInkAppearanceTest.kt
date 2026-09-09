package com.betterhv.note.export

import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.PenType
import com.betterhv.note.ink.PressureCurve
import com.betterhv.note.ink.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PdfInkAppearanceTest {
  @Test fun `centerline applies object transform before 72 over 96 conversion`() {
    val source = stroke(
      PenType.Marker,
      listOf(point(4f, 8f), point(12f, 16f)),
      transform = Transform2D(a = 2f, b = 0f, c = 0f, d = 2f, tx = 10f, ty = 20f),
    )

    val ink = requireNotNull(PdfInkAppearance.from(source))

    assertEquals(13.5f, ink.inkList[0].x, EPS)
    assertEquals(27f, ink.inkList[0].y, EPS)
    assertEquals(25.5f, ink.inkList[1].x, EPS)
    assertEquals(39f, ink.inkList[1].y, EPS)
  }

  @Test fun `single point ink duplicates its endpoint and keeps nonempty bounds`() {
    val ink = requireNotNull(PdfInkAppearance.from(stroke(PenType.Marker, listOf(point(20f, 30f)))))

    assertEquals(2, ink.inkList.size)
    assertEquals(ink.inkList[0], ink.inkList[1])
    assertTrue(ink.visualBounds.width > 0f)
    assertTrue(ink.visualBounds.height > 0f)
    val appearance = String(ink.buildAppearance(ink.visualBounds).bytes)
    assertTrue(appearance.contains(" c\n"))
    assertTrue(appearance.contains("f\n"))
  }

  @Test fun `argb is split into annotation rgb and source opacity`() {
    val ink = requireNotNull(
      PdfInkAppearance.from(
        stroke(PenType.Marker, listOf(point(1f, 1f), point(2f, 2f)), color = 0x80402010.toInt()),
      )
    )

    assertEquals(0x40 / 255f, ink.red, EPS)
    assertEquals(0x20 / 255f, ink.green, EPS)
    assertEquals(0x10 / 255f, ink.blue, EPS)
    assertEquals(0x80 / 255f, ink.opacity, EPS)
  }

  @Test fun `normal pen appearance uses shared swept disc geometry`() {
    val ink = requireNotNull(
      PdfInkAppearance.from(
        stroke(
          PenType.NormalPen,
          listOf(
            point(0f, 0f, 4f / 12f),
            point(10f, 5f, 8f / 12f),
            point(20f, 0f, 1f),
          ),
        )
      )
    )
    val appearance = String(ink.buildAppearance(ink.visualBounds).bytes)

    assertTrue(appearance.contains(" rg"))
    assertEquals(12, appearance.windowed(3).count { it == " c\n" })
    assertTrue(appearance.contains("h\n"))
    assertEquals(9f, ink.borderWidth, EPS)
    assertTrue(appearance.contains("/GS100 gs"))
  }

  @Test fun `marker appearance uses shared fixed-width swept discs`() {
    val ink = requireNotNull(
      PdfInkAppearance.from(
        stroke(
          PenType.Marker,
          listOf(point(0f, 0f, 0.1f), point(20f, 10f, 1f)),
        )
      )
    )
    val appearance = String(ink.buildAppearance(ink.visualBounds).bytes)

    assertTrue(appearance.contains("0.0667 0.1333 0.2 rg"))
    assertTrue(appearance.contains(" m\n"))
    assertTrue(appearance.contains("h\n"))
  }

  @Test fun `pencil appearance uses one fixed width and opacity state`() {
    val ink = requireNotNull(
      PdfInkAppearance.from(
        stroke(
          PenType.Pencil,
          listOf(point(0f, 0f, 0f), point(10f, 0f, 0.5f), point(20f, 0f, 1f)),
          color = 0x80ABCDEF.toInt(),
        )
      )
    )
    val appearance = ink.buildAppearance(ink.visualBounds)
    val content = String(appearance.bytes)

    assertEquals(setOf(40), appearance.opacityBuckets)
    assertEquals(0, content.windowed(2).count { it == " w" })
    appearance.opacityBuckets.forEach { assertTrue(content.contains("/${PdfInkAppearance.gsName(it)} gs")) }
  }

  private fun stroke(
    type: PenType,
    points: List<InkPoint>,
    color: Int = 0xFF112233.toInt(),
    transform: Transform2D = Transform2D.IDENTITY,
  ) = StrokeObject(
    id = UUID.randomUUID(),
    transform = transform,
    stroke = Stroke(
      points,
      PenStyle(
        baseWidth = 12f,
        color = color,
        pressureCurve = PressureCurve(a = 0.25f, gamma = 0.7f),
        penType = type,
      ),
    ),
  )

  private fun point(x: Float, y: Float, pressure: Float = 0.5f) =
    InkPoint(x, y, pressure, 1L)

  private companion object {
    const val EPS = 0.0001f
  }
}
