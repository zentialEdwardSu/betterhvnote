package com.betterhv.note.tool

import com.betterhv.note.doc.SelectionSet
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint

/**
 * Common tip-driven tool interface (spec §17). Every tool is driven from the
 * same three input events PenDrawView actually has available for the pen tip
 * on this hardware: onTouchEvent(ACTION_DOWN) for [onDown], the batches
 * decoded from onPenTouchUpStatus for [onBatch], and ACTION_UP / the
 * quiet-period timeout for [onUp] -- NOT onTouchEvent(MOVE/UP), which the ROM
 * never delivers for the hvpen-registered view (see the eraser-input-channel
 * project memory for the on-device experiments establishing this).
 */
interface Tool {
  fun onDown(x: Float, y: Float)
  fun onBatch(points: List<InkPoint>)
  fun onUp()
  fun onCancel()
}

/**
 * Shared surface of [com.betterhv.note.tool.StrokeEraserTool] and
 * [com.betterhv.note.tool.PointEraserTool] used by the hardware tail eraser
 * (see [com.betterhv.note.EraserOverlayView]), which drives an eraser
 * directly from its own touch stream rather than through [Tool]'s
 * onDown/onBatch/onUp (its samples are raw x/y taps, not decoded InkPoint
 * batches).
 */
interface EraserTool : Tool {
  fun eraseAt(x: Float, y: Float)
  fun commit()
}

/** Callbacks a [Tool] uses to ask the view to repaint or update selection UI. */
interface ToolHost {
  fun requestRepaint(bounds: Bounds)
  fun onSelectionChanged(selection: SelectionSet)
}
