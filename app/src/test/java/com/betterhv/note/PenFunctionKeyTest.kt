package com.betterhv.note

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PenFunctionKeyTest {
  @Test
  fun sideKeyOneStateDoesNotRequireStylusToolType() {
    assertTrue(PenFunctionKey.isPressed(toolType = 2, buttonState = 0x20))
    assertTrue(PenFunctionKey.isPressed(toolType = 1, buttonState = 0x20))
  }

  @Test
  fun ordinaryStylusAndSecondSideKeyAreNotFunctionClick() {
    assertFalse(PenFunctionKey.isPressed(toolType = 2, buttonState = 0))
    assertFalse(PenFunctionKey.isPressed(toolType = 2, buttonState = 0x40))
  }

  @Test
  fun hanvonFunctionToolTypeMatchesHvNote() {
    assertTrue(PenFunctionKey.isPressed(toolType = 6, buttonState = 0))
    assertTrue(PenFunctionKey.isPressed(toolType = 6, buttonState = 0x40))
  }

  @Test
  fun buttonStatesUseHvNoteExactEqualityAndPriority() {
    assertFalse(PenFunctionKey.isPressed(toolType = 2, buttonState = 0x21))
    assertTrue(PenFunctionKey.classify(2, 0x20) == PenSideButton.SIDE_1)
    assertTrue(PenFunctionKey.classify(2, 0x40) == PenSideButton.SIDE_2)
    assertTrue(PenFunctionKey.classify(2, 0x80) == PenSideButton.SIDE_3)
  }

  @Test
  fun targetDeviceSideOneUsesFingerToolTypeWithStylusSource() {
    assertTrue(PenFunctionKey.isPressed(toolType = 1, buttonState = 0, source = 0x5002))
    assertFalse(PenFunctionKey.isPressed(toolType = 1, buttonState = 0, source = 0x1002))
    assertFalse(PenFunctionKey.isPressed(toolType = 2, buttonState = 0, source = 0x5002))
  }

  @Test
  fun uiClickClassifierGivesExplicitSideTwoAndThreePriorityOverVendorToolFallback() {
    assertTrue(PenFunctionKey.classifyClickModifier(6, 0x40) == PenSideButton.SIDE_2)
    assertTrue(PenFunctionKey.classifyClickModifier(6, 0x80) == PenSideButton.SIDE_3)
    assertTrue(PenFunctionKey.classifyClickModifier(6, 0) == PenSideButton.SIDE_1)
    assertTrue(PenFunctionKey.classifyClickModifier(2, 0xA0) == PenSideButton.SIDE_3)
  }
}
