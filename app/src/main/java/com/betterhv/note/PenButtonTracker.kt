package com.betterhv.note

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Window-wide stylus-button observer. Buttons may arrive either as MotionEvent.buttonState
 * while touching/hovering or as dedicated stylus KeyEvents, depending on the Hanvon ROM.
 */
object PenButtonTracker {
    const val SIDE_KEY_1_MASK = 0x20
    const val SIDE_KEY_2_MASK = 0x40
    const val SIDE_KEY_3_MASK = 0x80
    const val TAIL_KEY_MASK = 0x100

    @Volatile
    private var rawMotionButtons: Int = 0

    /**
     * Some Hanvon input stacks report a stylus button only as actionButton on
     * ACTION_BUTTON_PRESS, then send the tip ACTION_DOWN with buttonState == 0.
     * Preserve that press until its matching release instead of losing it when
     * the ordinary toolType=2 DOWN arrives.
     */
    @Volatile
    private var actionButtons: Int = 0

    @Volatile
    private var keyButtons: Int = 0

    @Volatile
    private var sideKey1TouchGestureArmed: Boolean = false

    private var lastLoggedMotionButtons: Int = Int.MIN_VALUE
    private var lastLoggedToolType: Int = Int.MIN_VALUE

    val currentButtonState: Int get() = rawMotionButtons or actionButtons or keyButtons

    fun isSideKey1Pressed(): Boolean =
        currentButtonState and SIDE_KEY_1_MASK != 0

    /** Survives ROMs that clear buttonState on ACTION_UP before Compose invokes onClick. */
    fun consumeSideKey1Click(): Boolean {
        val pressed = sideKey1TouchGestureArmed || isSideKey1Pressed()
        sideKey1TouchGestureArmed = false
        // Do not let a ROM that omits ACTION_BUTTON_RELEASE turn one Side1 tap
        // into a permanent modifier. A real subsequent press will arm it again.
        actionButtons = actionButtons and SIDE_KEY_1_MASK.inv()
        return pressed
    }

    fun observeMotion(event: MotionEvent, channel: String) {
        // hvNote always asks MotionEvent for pointer index 0, including when the
        // action index belongs to another pointer. Mirror that vendor behavior.
        val toolType = if (event.pointerCount > 0) {
            event.getToolType(0)
        } else {
            MotionEvent.TOOL_TYPE_UNKNOWN
        }
        val action = event.actionMasked
        val actionButton = event.actionButton
        actionButtons = updateActionButtons(actionButtons, action, actionButton)

        val previousButtons = currentButtonState
        rawMotionButtons = event.buttonState
        val effectiveButtons = currentButtonState
        val hvNoteButton = PenFunctionKey.classify(toolType, effectiveButtons, event.source)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            sideKey1TouchGestureArmed = hvNoteButton == PenSideButton.SIDE_1
        }

        val isButtonAction = action == MotionEvent.ACTION_BUTTON_PRESS ||
            action == MotionEvent.ACTION_BUTTON_RELEASE
        val isGestureBoundary = action == MotionEvent.ACTION_DOWN ||
            action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val stylusLike = event.source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER ||
            toolType == PenFunctionKey.HANVON_FUNCTION_TOOL_TYPE
        val relevant = effectiveButtons != 0 || previousButtons != 0 ||
            hvNoteButton != PenSideButton.NONE || isButtonAction ||
            (stylusLike && isGestureBoundary)
        val changed = effectiveButtons != lastLoggedMotionButtons ||
            toolType != lastLoggedToolType || isButtonAction || isGestureBoundary
        if (relevant && changed) {
            lastLoggedMotionButtons = effectiveButtons
            lastLoggedToolType = toolType
            EventLog.log(
                TAG,
                "$channel action=${motionActionName(action)} tool=$toolType " +
                    "rawButtons=${describeButtons(event.buttonState)} " +
                    "actionButton=${describeButtons(actionButton)} " +
                    "effectiveButtons=${describeButtons(effectiveButtons)} " +
                    "hvNoteButton=${hvNoteButton.logName} meta=0x${event.metaState.toString(16)} " +
                    "source=0x${event.source.toString(16)} device=${event.deviceId}"
            )
        }
    }

    fun observeKey(event: KeyEvent) {
        if (event.repeatCount != 0) return
        val mask = keyCodeToMask(event.keyCode)
        if (mask != 0) {
            keyButtons = if (event.action == KeyEvent.ACTION_DOWN) {
                keyButtons or mask
            } else {
                keyButtons and mask.inv()
            }
        }

        val stylusSource = event.source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS
        EventLog.log(
            TAG,
            "key action=${keyActionName(event.action)} code=${event.keyCode}" +
                "(${KeyEvent.keyCodeToString(event.keyCode)}) scan=${event.scanCode} " +
                "mapped=${describeButtons(mask)} held=${describeButtons(currentButtonState)} " +
                "stylusSource=$stylusSource source=0x${event.source.toString(16)} device=${event.deviceId}"
        )
    }

    fun keyCodeToMask(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY -> SIDE_KEY_1_MASK
        KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY -> SIDE_KEY_2_MASK
        KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY -> SIDE_KEY_3_MASK
        KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL -> TAIL_KEY_MASK
        else -> 0
    }

    internal fun updateActionButtons(current: Int, action: Int, actionButton: Int): Int = when (action) {
        MotionEvent.ACTION_BUTTON_PRESS -> current or actionButton
        MotionEvent.ACTION_BUTTON_RELEASE -> current and actionButton.inv()
        MotionEvent.ACTION_CANCEL -> 0
        else -> current
    }

    fun describeButtons(buttonState: Int): String {
        if (buttonState == 0) return "none(0x0)"
        val names = buildList {
            if (buttonState and SIDE_KEY_1_MASK != 0) add("side1")
            if (buttonState and SIDE_KEY_2_MASK != 0) add("side2")
            if (buttonState and SIDE_KEY_3_MASK != 0) add("side3")
            if (buttonState and TAIL_KEY_MASK != 0) add("tail")
        }
        return "${if (names.isEmpty()) "unknown" else names.joinToString("+")}" +
            "(0x${buttonState.toString(16)})"
    }

    private fun motionActionName(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "down"
        MotionEvent.ACTION_UP -> "up"
        MotionEvent.ACTION_MOVE -> "move"
        MotionEvent.ACTION_HOVER_ENTER -> "hover-enter"
        MotionEvent.ACTION_HOVER_MOVE -> "hover-move"
        MotionEvent.ACTION_HOVER_EXIT -> "hover-exit"
        MotionEvent.ACTION_BUTTON_PRESS -> "button-press"
        MotionEvent.ACTION_BUTTON_RELEASE -> "button-release"
        MotionEvent.ACTION_CANCEL -> "cancel"
        else -> action.toString()
    }

    private fun keyActionName(action: Int): String = when (action) {
        KeyEvent.ACTION_DOWN -> "down"
        KeyEvent.ACTION_UP -> "up"
        else -> action.toString()
    }

    private const val TAG = "StylusInput"
}
