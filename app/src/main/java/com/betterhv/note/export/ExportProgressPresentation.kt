package com.betterhv.note.export

import java.util.Locale

/** True when the stage knows how many units it must process. */
fun ExportProgress.Stage.isDeterminate(): Boolean = when (this) {
  ExportProgress.Stage.RENDERING,
  ExportProgress.Stage.ASSEMBLING,
  ExportProgress.Stage.SENDING,
  -> true

  else -> false
}

fun ExportProgress.determinateFraction(): Float? =
  if (stage.isDeterminate() && total > 0) {
    (current.toFloat() / total).coerceIn(0f, 1f)
  } else {
    null
  }

/**
 * Remaining time for a determinate stage, assuming the remaining pages take as
 * long as the average page processed so far. Returns null when there is not
 * enough information yet or the stage is already complete.
 */
fun ExportProgress.remainingMs(): Long? {
  if (determinateFraction() == null || current <= 0 || total <= current) return null
  return ((stageElapsedMs.toDouble() * (total - current)) / current).toLong()
}

/** Compact duration for progress captions: "12.3s", "1m 05s". */
fun formatDuration(milliseconds: Long): String {
  val seconds = milliseconds.coerceAtLeast(0L) / 1000.0
  return if (seconds < 60.0) {
    String.format(Locale.US, "%.1fs", seconds)
  } else {
    val minutes = (seconds / 60.0).toInt()
    String.format(Locale.US, "%dm %02ds", minutes, (seconds - minutes * 60).toInt())
  }
}
