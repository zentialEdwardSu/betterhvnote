package com.betterhv.note

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.betterhv.note.ink.InkPoint

/**
 * Transparent input layer for the lasso tool.
 *
 * hvNote drives lasso editing from its non-hvpen MemoMarkView, not from the
 * hardware-ink callback used for normal writing.  The distinction matters on
 * this ROM: disabling ROM ink also stops the point batches that callback would
 * otherwise provide.  Like [EraserOverlayView], this unregistered view receives
 * the complete Android DOWN/MOVE/UP stream and forwards it in PenDrawView's
 * client coordinate space.
 */
class LassoOverlayView(context: Context, private val pen: PenDrawView) : View(context) {

    private var lassoing = false
    private var selectingPdfRegion = false
    private var navigatingPdf = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pen.currentToolKind() !in setOf(ToolKind.LASSO, ToolKind.NAVIGATION)) return false
                // The tail eraser remains owned by EraserOverlayView even while
                // lasso is selected.
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) return false
                // The dedicated Link icon belongs to ObjectEditOverlayView and
                // must remain clickable even while lasso/navigation is active.
                if (pen.isLinkedNavigationIconHit(event.x, event.y)) return false
                val eventModifier = PenFunctionKey.classifyClickModifier(
                    event.getToolType(0),
                    event.buttonState or PenButtonTracker.currentButtonState,
                    event.source
                )
                // Some Hanvon firmware reports the side key only before the tip
                // ACTION_DOWN. The window tracker keeps that gesture modifier.
                val modifier = eventModifier.takeUnless { it == PenSideButton.NONE }
                    ?: PenButtonTracker.currentClickModifier()
                // A single text/image selected through Edit keeps the lasso
                // tool active but delegates ordinary transform gestures to the
                // object layer below. Side1 remains the rectangular PDF lasso.
                if (pen.currentToolKind() == ToolKind.LASSO &&
                    pen.selectedRichObject() != null && modifier != PenSideButton.SIDE_1
                ) return false
                if (pen.currentToolKind() == ToolKind.LASSO && modifier == PenSideButton.SIDE_1) {
                    selectingPdfRegion = pen.beginPdfRegion(event.x, event.y)
                    return selectingPdfRegion
                }
                if (pen.currentToolKind() == ToolKind.NAVIGATION) {
                    navigatingPdf = pen.beginPdfNavigation(event.x, event.y, modifier)
                    return navigatingPdf
                }
                lassoing = true
                pen.beginLasso(event.x, event.y, event.eventTime)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (navigatingPdf) {
                    pen.updatePdfNavigation(event.x, event.y)
                    return true
                }
                if (selectingPdfRegion) {
                    pen.updatePdfRegion(event.x, event.y)
                    return true
                }
                if (!lassoing) return false
                // Preserve historical samples so fast loops remain closed and
                // smooth instead of becoming a sparse polygon.
                val samples = ArrayList<InkPoint>(event.historySize + 1)
                for (h in 0 until event.historySize) {
                    samples.add(
                        sample(
                            event.getHistoricalX(h),
                            event.getHistoricalY(h),
                            event.getHistoricalEventTime(h)
                        )
                    )
                }
                samples.add(sample(event.x, event.y, event.eventTime))
                pen.updateLasso(samples)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (navigatingPdf) {
                    pen.updatePdfNavigation(event.x, event.y)
                    pen.endPdfNavigation(cancelled = false)
                    PenButtonTracker.consumeClickModifier()
                    navigatingPdf = false
                    return true
                }
                if (selectingPdfRegion) {
                    pen.updatePdfRegion(event.x, event.y)
                    pen.endPdfRegion(cancelled = false)
                    PenButtonTracker.consumeClickModifier()
                    selectingPdfRegion = false
                    return true
                }
                if (!lassoing) return false
                pen.updateLasso(listOf(sample(event.x, event.y, event.eventTime)))
                pen.endLasso(cancelled = false)
                lassoing = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (navigatingPdf) {
                    pen.endPdfNavigation(cancelled = true)
                    PenButtonTracker.consumeClickModifier()
                    navigatingPdf = false
                    return true
                }
                if (selectingPdfRegion) {
                    pen.endPdfRegion(cancelled = true)
                    PenButtonTracker.consumeClickModifier()
                    selectingPdfRegion = false
                    return true
                }
                if (!lassoing) return false
                pen.endLasso(cancelled = true)
                lassoing = false
                return true
            }
        }
        return lassoing || selectingPdfRegion || navigatingPdf
    }

    private fun sample(x: Float, y: Float, eventTime: Long) =
        InkPoint(x = x, y = y, pressure = 1.0f, timestamp = eventTime)
}
