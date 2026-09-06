package com.betterhv.note

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View

/**
 * Transparent overlay stacked directly over [PenDrawView], sized identically, to
 * drive the eraser.
 *
 * Why a second view: on this ROM the hvpen service intercepts ACTION_MOVE/UP for
 * the view it is registered against (PenDrawView), delivering pen samples only
 * through its own callback -- so PenDrawView never sees an eraser drag. A view
 * that is NOT registered with hvpen, however, receives the full standard Android
 * touch stream (verified on-device: DOWN + MOVE + UP for both stylus tip and
 * tail eraser). This is exactly how the vendor app splits pen (HandView) from
 * eraser (MemoView). See the eraser-input memory note for the experiments.
 *
 * Only the eraser tool is handled here. For the pen tip this view returns false
 * so the event falls through to PenDrawView and the ROM's low-latency ink path.
 * Because the overlay shares PenDrawView's bounds and origin, event coordinates
 * are already in PenDrawView's client space -- no transform.
 *
 * beginErase/eraseMove/endErase always erase using whichever eraser sub-mode
 * (whole-stroke vs point) the toolbar currently has selected -- PenDrawView
 * forwards them to its active [com.betterhv.note.tool.EraserTool] internally,
 * so this view does not need to know about tool selection at all.
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class EraserOverlayView(context: Context, private val pen: PenDrawView) : View(context) {

  private var erasing = false

  override fun onTouchEvent(event: MotionEvent): Boolean {
    val isEraser = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        if (!isEraser) return false // pen: let it reach PenDrawView/hvpen
        erasing = true
        pen.beginErase()
        pen.eraseMove(event.x, event.y)
        return true
      }

      MotionEvent.ACTION_MOVE -> {
        if (!erasing) return false
        // Replay batched historical samples so a fast erase drag doesn't
        // skip over strokes between coarse MOVE deliveries.
        for (h in 0 until event.historySize) {
          pen.eraseMove(event.getHistoricalX(h), event.getHistoricalY(h))
        }
        pen.eraseMove(event.x, event.y)
        return true
      }

      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        if (!erasing) return false
        pen.eraseMove(event.x, event.y)
        pen.endErase()
        erasing = false
        return true
      }
    }
    return false
  }
}
