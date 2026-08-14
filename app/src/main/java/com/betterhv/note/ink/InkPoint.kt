package com.betterhv.note.ink

/**
 * One stylus sample. Spec §12.
 *
 * Coordinates are page coordinates (§60) -- never screen pixels. Pressure is
 * normalized to [0,1] by the input adapter before it gets here; `timestamp` is
 * milliseconds and is recorded from the first version even though nothing
 * consumes it yet, because audio sync and AI features later key off it (§2.5).
 *
 * Deliberately a plain value type with no validation in the constructor: a page
 * holds millions of these (§72) and they are allocated on the input hot path,
 * so clamping happens once at the parse boundary instead of per-instance here.
 * Tilt fields are reserved by the spec but this device does not report them.
 */
data class InkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val timestamp: Long,
    val tiltX: Float = 0.0f,
    val tiltY: Float = 0.0f,
    val azimuth: Float = 0.0f
)
