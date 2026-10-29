package com.betterhv.note

/**
 * Which tip-driven editing tool the toolbar currently has selected. Erasing is
 * NOT a tip tool: it is done only by the hardware tail eraser (see
 * [EraserOverlayView]) -- the ROM drives that reliably, whereas a tip-driven
 * eraser's moving cursor overlay lagged badly on the e-ink panel. The tail
 * eraser's behavior is chosen by [EraserMode], toggled independently of the tip
 * tool.
 */
enum class ToolKind { PEN, LASSO, NAVIGATION }

/**
 * How the hardware tail eraser removes ink: [WHOLE_STROKE] deletes any stroke
 * the disc touches; [POINT] splits strokes at the erased points (spec §33-35).
 * Toggled from the toolbar; applies to the tail eraser regardless of which tip
 * [ToolKind] is active.
 */
enum class EraserMode { WHOLE_STROKE, POINT }
