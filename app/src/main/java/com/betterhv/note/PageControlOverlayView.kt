package com.betterhv.note

import android.content.Context
import android.view.MotionEvent
import android.view.View

/**
 * Transparent Side2/Side3 page-turn layer. Ordinary pen input returns false
 * and continues to the ink/lasso/eraser layers. The page counter owns Side1
 * bookmark clicks directly so its visual and hit target cannot drift apart.
 */
class PageControlOverlayView(
    context: Context,
    private val pen: PenDrawView,
    private val onNotice: (String) -> Unit
) : View(context) {
    private var armed = PenSideButton.NONE

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = if (event.pointerCount > 0) event.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val effectiveButtons = event.buttonState or PenButtonTracker.currentButtonState
                val eventModifier = PenFunctionKey.classifyClickModifier(toolType, effectiveButtons, event.source)
                val modifier = eventModifier.takeUnless { it == PenSideButton.NONE }
                    ?: PenButtonTracker.currentClickModifier()
                val inPageZone = event.x >= width * PAGE_ZONE_START
                armed = when {
                    // Side2 belongs exclusively to PDF zoom while Navigation is
                    // selected, including inside the right-hand page-turn zone.
                    modifier == PenSideButton.SIDE_2 && pen.currentToolKind() == ToolKind.NAVIGATION ->
                        PenSideButton.NONE
                    (modifier == PenSideButton.SIDE_2 || modifier == PenSideButton.SIDE_3) && inPageZone -> modifier
                    else -> PenSideButton.NONE
                }
                return armed != PenSideButton.NONE
            }
            MotionEvent.ACTION_UP -> {
                if (armed == PenSideButton.NONE) return false
                val action = armed
                armed = PenSideButton.NONE
                PenButtonTracker.consumeClickModifier()
                perform(action)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumed = armed != PenSideButton.NONE
                armed = PenSideButton.NONE
                PenButtonTracker.consumeClickModifier()
                return consumed
            }
        }
        return armed != PenSideButton.NONE
    }

    private fun perform(modifier: PenSideButton) {
        when (modifier) {
            PenSideButton.SIDE_1 -> Unit
            PenSideButton.SIDE_2 -> {
                if (!pen.navigatePage(-1)) onNotice("已经是第一页")
            }
            PenSideButton.SIDE_3 -> {
                if (!pen.navigatePage(1)) onNotice("已经是最后一页")
            }
            PenSideButton.NONE -> Unit
        }
    }

    companion object {
        private const val PAGE_ZONE_START = 0.75f
    }
}
