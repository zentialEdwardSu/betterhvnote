package com.betterhv.note

import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.PenType
import org.junit.Assert.assertEquals
import org.junit.Test

class PenSettingsTest {
  @Test
  fun toolbarPenSlotsKeepIndependentConfigurations() {
    val first = PenSettings().updateActivePreset { it.copy(colorIndex = 4, widthLevel = 4) }
    val second = PenSettings().selectType(PenType.Pencil)
      .updateActivePreset { it.copy(colorIndex = 5, widthLevel = 2) }
    val third = PenSettings().selectType(PenType.Marker)
      .updateActivePreset { it.copy(colorIndex = 7, widthLevel = 3) }

    val toolbar = PenToolbarSettings()
      .updateSlot(0, first)
      .updateSlot(1, second)
      .updateSlot(2, third)
      .selectSlot(1)

    assertEquals(PenType.NormalPen, toolbar.settingsFor(0).activeType)
    assertEquals(PenPreset(4, 4), toolbar.settingsFor(0).activePreset())
    assertEquals(PenType.Pencil, toolbar.activeSettings().activeType)
    assertEquals(PenPreset(5, 2), toolbar.activeSettings().activePreset())
    assertEquals(PenType.Marker, toolbar.settingsFor(2).activeType)
    assertEquals(PenPreset(7, 3), toolbar.settingsFor(2).activePreset())
  }

  @Test
  fun invalidToolbarSlotFallsBackWithoutChangingOtherSlots() {
    val original = PenToolbarSettings().updateSlot(
      2,
      PenSettings().selectType(PenType.Marker),
    )

    assertEquals(0, original.selectSlot(99).activeSlot)
    assertEquals(PenType.Marker, original.updateSlot(99, PenSettings()).settingsFor(2).activeType)
  }

  @Test
  fun brushesRememberIndependentPresets() {
    var settings = PenSettings()
    settings = settings.updateActivePreset { it.copy(colorIndex = 4, widthLevel = 3) }
    settings = settings.selectType(PenType.Marker)
    settings = settings.updateActivePreset { it.copy(colorIndex = 7, widthLevel = 4) }

    assertEquals(PenPreset(4, 3), settings.presetFor(PenType.NormalPen))
    assertEquals(PenPreset(7, 4), settings.presetFor(PenType.Marker))
    assertEquals(PenPreset(), settings.presetFor(PenType.Pencil))
  }

  @Test
  fun codecRoundTripsEveryBrush() {
    val original = PenSettings(
      activeType = PenType.Pencil,
      presets = mapOf(
        PenType.NormalPen to PenPreset(4, 0),
        PenType.Pencil to PenPreset(5, 2),
        PenType.Marker to PenPreset(6, 4),
      ),
    )
    val encoded = PenSettingsCodec.encode(original)
    val decoded = PenSettingsCodec.decode(
      encoded.activeTypeId,
      encoded.colors,
      encoded.widths,
    )

    assertEquals(original, decoded)
  }

  @Test
  fun codecFallsBackForUnknownAndCorruptValues() {
    val decoded = PenSettingsCodec.decode(
      activeTypeId = "future-brush",
      colors = mapOf("normal" to -1, "marker" to 99),
      widths = mapOf("normal" to 99, "pencil" to -5),
    )

    assertEquals(PenType.NormalPen, decoded.activeType)
    for (type in PenType.entries) {
      assertEquals(PenPreset(), decoded.presetFor(type))
    }
  }

  @Test
  fun previouslyCreatedStyleDoesNotChangeWithSettings() {
    val firstSettings = PenSettings()
    val firstStyle = PenProfiles.style(firstSettings, "N10Pro")
    val changed = firstSettings.updateActivePreset { it.copy(colorIndex = 4, widthLevel = 4) }
    val secondStyle = PenProfiles.style(changed, "N10Pro")

    assertEquals(0xFF000000.toInt(), firstStyle.color)
    assertEquals(0xFFFF0000.toInt(), secondStyle.color)
    assertEquals(4f + 32f * (4f / 19f), firstStyle.baseWidth, 0.001f)
    assertEquals(36f, secondStyle.baseWidth, 0.001f)
  }

  @Test
  fun romOverlayReceivesSelectedScreenWidthDirectly() {
    assertEquals(
      4,
      PenProfiles.serviceWidth(PenStyle(baseWidth = 4f, penType = PenType.NormalPen)),
    )
    assertEquals(
      30,
      PenProfiles.serviceWidth(PenStyle(baseWidth = 30f, penType = PenType.Marker)),
    )
    assertEquals(
      3,
      PenProfiles.serviceWidth(PenStyle(baseWidth = 3f, penType = PenType.Pencil)),
    )
  }

  @Test
  fun romOverlayWidthNeverRoundsDownToZero() {
    assertEquals(1, PenProfiles.serviceWidth(PenStyle(baseWidth = 0.1f)))
  }
}
