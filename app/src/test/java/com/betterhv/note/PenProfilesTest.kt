package com.betterhv.note

import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.PenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PenProfilesTest {
  @Test
  fun servicePenIdsMatchHvNote() {
    assertEquals(6, PenProfiles.servicePen(PenType.NormalPen))
    assertEquals(15, PenProfiles.servicePen(PenType.Pencil))
    assertEquals(15, PenProfiles.servicePen(PenType.Marker))
  }

  @Test
  fun widthLevelsMapToDeviceEndpointsAndIncreaseMonotonically() {
    val types = listOf(PenType.NormalPen, PenType.Pencil, PenType.Marker)
    for (type in types) {
      val widths = PenProfiles.logicalWidths.indices.map {
        PenProfiles.actualWidth(type, it, "N10Pro")
      }
      assertTrue(widths.zipWithNext().all { (left, right) -> right > left })
    }

    assertEquals(4f, PenProfiles.actualWidth(PenType.NormalPen, 0, "N10Pro"), 0.001f)
    assertEquals(36f, PenProfiles.actualWidth(PenType.NormalPen, 4, "N10Pro"), 0.001f)
    assertEquals(3f, PenProfiles.actualWidth(PenType.Pencil, 0, "M10"), 0.001f)
    assertEquals(30f, PenProfiles.actualWidth(PenType.Pencil, 4, "M10"), 0.001f)
    assertEquals(15f, PenProfiles.actualWidth(PenType.Marker, 0, "C10"), 0.001f)
    assertEquals(54f, PenProfiles.actualWidth(PenType.Marker, 4, "C10"), 0.001f)
    assertEquals(21f, PenProfiles.actualWidth(PenType.Marker, 0, "unknown"), 0.001f)
    assertEquals(60f, PenProfiles.actualWidth(PenType.Marker, 4, "unknown"), 0.001f)
  }

  @Test
  fun markerColorsMatchHvNoteAlphaPalette() {
    assertEquals(0x66000000, PenProfiles.markerColor(0))
    assertEquals(0x80CCCCCC.toInt(), PenProfiles.markerColor(2))
    assertEquals(0x4DFF0000, PenProfiles.markerColor(4))
    assertEquals(0x4D005FFF, PenProfiles.markerColor(5))
    assertEquals(0x4D008000, PenProfiles.markerColor(6))
    assertEquals(0x66EDED05, PenProfiles.markerColor(7))
  }

  @Test
  fun serviceColorsUseArgbOnColorHardwareAndHvNoteGrayMappingOnMono() {
    val normalRed = PenStyle(color = 0xFFFF0000.toInt(), penType = PenType.NormalPen)
    val normalBlack = PenStyle(color = 0xFF000000.toInt(), penType = PenType.NormalPen)
    val white = PenStyle(color = 0xFFFFFFFF.toInt(), penType = PenType.Pencil)
    val markerBlack = PenStyle(color = 0x66000000, penType = PenType.Marker)
    val markerBlue = PenStyle(color = 0x4D005FFF, penType = PenType.Marker)

    assertEquals(0xFFFF0000.toInt(), PenProfiles.serviceColor(normalRed, true))
    assertEquals(PenProfiles.SERVICE_COLOR_BLACK, PenProfiles.serviceColor(normalBlack, false))
    assertEquals(PenProfiles.SERVICE_COLOR_WHITE, PenProfiles.serviceColor(white, false))
    assertEquals(PenProfiles.SERVICE_COLOR_DARK_GRAY, PenProfiles.serviceColor(normalRed, false))
    assertEquals(PenProfiles.SERVICE_COLOR_DARK_GRAY, PenProfiles.serviceColor(markerBlack, false))
    assertEquals(PenProfiles.SERVICE_COLOR_LIGHT_GRAY, PenProfiles.serviceColor(markerBlue, false))
  }

  @Test
  fun generatedStylesHaveDistinctPressureBehavior() {
    val normal = PenProfiles.style(PenSettings(), "N10Pro")
    val pencil = PenProfiles.style(PenSettings().selectType(PenType.Pencil), "N10Pro")
    val marker = PenProfiles.style(PenSettings().selectType(PenType.Marker), "N10Pro")

    assertTrue(normal.widthAt(0f) < normal.widthAt(1f))
    assertTrue(pencil.widthAt(0f) < pencil.widthAt(1f))
    assertEquals(marker.widthAt(0f), marker.widthAt(1f), 0.001f)
    assertEquals(PenType.Marker, marker.penType)
  }

  @Test
  fun normalPenAppliesItsMeasuredVisualWidthScale() {
    val normal = PenProfiles.style(
      PenSettings().updateActivePreset { it.copy(widthLevel = 4) },
      "N10Pro",
    )
    val marker = PenProfiles.style(
      PenSettings().selectType(PenType.Marker)
        .updateActivePreset { it.copy(widthLevel = 4) },
      "N10Pro",
    )

    assertEquals(36f * 0.34f, normal.widthAt(1f), 0.001f)
    assertEquals(80f, marker.widthAt(1f), 0.001f)
  }
}
