package com.betterhv.note.ink

/**
 * One stylus sample. Spec §12.
 *
 * Coordinates are page units (§60), never screen pixels. [pressure] is the
 * existing per-point scalar slot: for NormalPen strokes produced by the ROM it
 * carries vendor geometric width / the exact width configured on hvpen; fixed
 * width brushes write 1. The name is retained for the version-1 document
 * format and for other inputs that really do provide normalized pressure.
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
  val azimuth: Float = 0.0f,
)
