package com.betterhv.note

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PenButtonTrackerTest {
    @Test
    fun actionButtonPressSurvivesOrdinaryStylusDownUntilRelease() {
        var held = PenButtonTracker.updateActionButtons(
            current = 0,
            action = android.view.MotionEvent.ACTION_BUTTON_PRESS,
            actionButton = PenButtonTracker.SIDE_KEY_1_MASK
        )
        assertEquals(PenButtonTracker.SIDE_KEY_1_MASK, held)

        held = PenButtonTracker.updateActionButtons(
            current = held,
            action = android.view.MotionEvent.ACTION_DOWN,
            actionButton = 0
        )
        assertEquals(PenButtonTracker.SIDE_KEY_1_MASK, held)

        held = PenButtonTracker.updateActionButtons(
            current = held,
            action = android.view.MotionEvent.ACTION_BUTTON_RELEASE,
            actionButton = PenButtonTracker.SIDE_KEY_1_MASK
        )
        assertEquals(0, held)
    }

    @Test
    fun dedicatedStylusKeyCodesMapToAllButtons() {
        assertEquals(
            PenButtonTracker.SIDE_KEY_1_MASK,
            PenButtonTracker.keyCodeToMask(KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY)
        )
        assertEquals(
            PenButtonTracker.SIDE_KEY_2_MASK,
            PenButtonTracker.keyCodeToMask(KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY)
        )
        assertEquals(
            PenButtonTracker.SIDE_KEY_3_MASK,
            PenButtonTracker.keyCodeToMask(KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY)
        )
        assertEquals(
            PenButtonTracker.TAIL_KEY_MASK,
            PenButtonTracker.keyCodeToMask(KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL)
        )
    }

    @Test
    fun buttonDescriptionsExposeRawAndNamedState() {
        assertEquals("none(0x0)", PenButtonTracker.describeButtons(0))
        assertEquals("side1+side3(0xa0)", PenButtonTracker.describeButtons(0xA0))
        assertEquals("unknown(0x400)", PenButtonTracker.describeButtons(0x400))
    }
}
