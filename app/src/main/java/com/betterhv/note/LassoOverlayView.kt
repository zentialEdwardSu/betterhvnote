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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pen.currentToolKind() != ToolKind.LASSO) return false
                // The tail eraser remains owned by EraserOverlayView even while
                // lasso is selected.
                if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) return false
                lassoing = true
                pen.beginLasso(event.x, event.y, event.eventTime)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
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
                if (!lassoing) return false
                pen.updateLasso(listOf(sample(event.x, event.y, event.eventTime)))
                pen.endLasso(cancelled = false)
                lassoing = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!lassoing) return false
                pen.endLasso(cancelled = true)
                lassoing = false
                return true
            }
        }
        return lassoing
    }

    private fun sample(x: Float, y: Float, eventTime: Long) =
        InkPoint(x = x, y = y, pressure = 1.0f, timestamp = eventTime)
}
