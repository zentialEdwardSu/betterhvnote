package com.betterhv.note

import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Transparent page-object input layer above the ink/lasso views. */
class ObjectEditOverlayView(context: Context, private val pen: PenDrawView) : View(context) {
    var onPlacementTap: ((Float, Float) -> Unit)? = null

    private var handling = false
    private var side1Selection = false
    private var linkNavigation = false
    private var placement = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) return false
                if (pen.beginLinkedNavigationIconTap(event.x, event.y)) {
                    handling = true
                    linkNavigation = true
                    return true
                }
                val eventModifier = PenFunctionKey.classifyClickModifier(
                    event.getToolType(0),
                    event.buttonState or PenButtonTracker.currentButtonState,
                    event.source
                )
                val modifier = eventModifier.takeUnless { it == PenSideButton.NONE }
                    ?: PenButtonTracker.currentClickModifier()
                if (onPlacementTap == null && pen.currentToolKind() == ToolKind.NAVIGATION) {
                    return false
                }
                if (onPlacementTap == null && pen.currentToolKind() == ToolKind.LASSO &&
                    pen.selectedRichObject() == null
                ) {
                    return false
                }
                if (modifier == PenSideButton.SIDE_2 || modifier == PenSideButton.SIDE_3) return false
                downX = event.x
                downY = event.y
                moved = false
                side1Selection = modifier == PenSideButton.SIDE_1
                placement = !side1Selection && onPlacementTap != null
                handling = when {
                    side1Selection -> true
                    placement -> true
                    pen.selectedRichObject() != null -> {
                        if (pen.beginRichObjectGesture(event.x, event.y)) true
                        else {
                            pen.clearRichSelection()
                            true
                        }
                    }
                    else -> false
                }
                return handling
            }

            MotionEvent.ACTION_MOVE -> {
                if (!handling) return false
                if (linkNavigation) return true
                if (!side1Selection && !placement) {
                    if (hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > TAP_SLOP) {
                        moved = true
                    }
                    pen.updateRichObjectGesture(event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!handling) return false
                when {
                    linkNavigation -> pen.endLinkedNavigationIconTap(event.x, event.y, cancelled = false)
                    side1Selection -> {
                        PenButtonTracker.consumeClickModifier()
                        pen.selectRichObjectAt(event.x, event.y)
                    }
                    placement -> onPlacementTap?.invoke(event.x, event.y)
                    else -> {
                        pen.endRichObjectGesture(cancelled = false)
                        val close = event.eventTime - lastTapUp <= DOUBLE_TAP_TIMEOUT &&
                            hypot((event.x - lastTapX).toDouble(), (event.y - lastTapY).toDouble()) <= TAP_SLOP
                        if (!moved && close) {
                            pen.requestSelectedTextEdit()
                            lastTapUp = 0L
                        } else if (!moved) {
                            lastTapUp = event.eventTime
                            lastTapX = event.x
                            lastTapY = event.y
                        }
                    }
                }
                reset()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!handling) return false
                when {
                    linkNavigation -> pen.endLinkedNavigationIconTap(event.x, event.y, cancelled = true)
                    !side1Selection && !placement -> pen.endRichObjectGesture(cancelled = true)
                }
                reset()
                return true
            }
        }
        return handling
    }

    private fun reset() {
        handling = false
        side1Selection = false
        linkNavigation = false
        placement = false
        moved = false
    }

    companion object {
        private const val TAP_SLOP = 24f
        private const val DOUBLE_TAP_TIMEOUT = 350L
    }
}
