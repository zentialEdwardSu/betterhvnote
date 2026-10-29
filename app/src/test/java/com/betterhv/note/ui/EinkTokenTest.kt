package com.betterhv.note.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EinkTokenTest {
  @Test fun chromeColorsAreGrayscale() {
    val chrome = listOf(
      EinkColors.Paper,
      EinkColors.Chrome,
      EinkColors.ChromeAlt,
      EinkColors.Ink,
      EinkColors.Muted,
      EinkColors.Disabled,
      EinkColors.DisabledFill,
    )
    chrome.forEach { color ->
      assertEquals(color.toString(), color.red, color.green, 0f)
      assertEquals(color.toString(), color.green, color.blue, 0f)
    }
    assertEquals(Color.Transparent, EinkColors.Transparent)
  }

  @Test fun dimensionsRespectTheEinkTouchAndBorderScale() {
    assertTrue(EinkDimens.Touch.value >= 44f)
    assertTrue(EinkDimens.Row.value >= 44f)
    assertTrue(EinkDimens.Icon.value > 0f)
    assertTrue(EinkDimens.PanelHeader.value > 0f)
    assertTrue(EinkDimens.ScreenHeader.value > 0f)
    assertEquals(1f, EinkDimens.Hairline.value)
    assertEquals(2f, EinkDimens.BorderEmphasis.value)
  }

  @Test fun typographyRolesArePositiveAndOrdered() {
    assertTrue(EinkType.ScreenTitle.fontSize.value > EinkType.PanelTitle.fontSize.value)
    assertTrue(EinkType.SectionTitle.fontSize.value > EinkType.Label.fontSize.value)
    assertTrue(EinkType.Body.fontSize.value > EinkType.Caption.fontSize.value)
    assertTrue(EinkType.Action.fontSize.value > 0f)
  }
}
