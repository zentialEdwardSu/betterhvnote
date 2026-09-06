package com.betterhv.note

import android.content.Context
import android.os.Build
import android.os.EinkManager

/**
 * Wraps the ROM's "eink" system service to bracket the pixel eraser gesture
 * in autowrite (fast, no-dither) mode and revert to a quality/full refresh
 * once erasing finishes.
 *
 * Ported from the vendor app's MyhvMemoryUtils.enterAutowrite (hvNote 7.16
 * smali): MemoView.onTouchEvent calls enterAutowrite(ctx, true) around its
 * eraser path (idxListErase / notifyEraserDataByEvent) and enterAutowrite(
 * ctx, false) once the erase is applied, reverting to setMode("9") on SDK 34
 * or setMode("7") on older SDKs.
 *
 * Scope note: the same smali also has an enterA2Mode(Context, Boolean) used
 * around paste and multi-touch (rotate/scale/long-press) gestures -- but
 * NEVER around plain single-stylus handwriting. There is no vendor evidence
 * that wrapping ordinary pen strokes in fast-mode enter/exit is correct or
 * even safe, so this controller is scoped to the eraser gesture only, where
 * the vendor precedent is solid. If refresh-per-stroke on plain writing
 * turns out to be fixable at all, it needs its own investigation -- it may
 * simply be inherent ROM/panel behavior the vendor app doesn't fight either.
 *
 * This is the believed fix for erased ink reappearing after refresh: an
 * eraser's partial update while never in the ROM's own erase-aware mode
 * pairing can leave A2-style ghosting that a later nearby update makes
 * visible again, because nothing was forcing a quality flush right after
 * the erase.
 */
class EinkRefreshController(context: Context) {
  private val appContext = context.applicationContext
  private var eink: EinkManager? = null
  private var einkLookupFailed = false

  private fun manager(): EinkManager? {
    var m = eink
    if (m == null && !einkLookupFailed) {
      m = try {
        appContext.getSystemService("eink") as? EinkManager
      } catch (t: Throwable) {
        null
      }
      if (m == null) {
        einkLookupFailed = true
        EventLog.log(TAG, "ERROR getSystemService(eink) returned null/unavailable")
      } else {
        eink = m
        EventLog.log(TAG, "eink service acquired")
      }
    }
    return m
  }

  /** Enter autowrite/fast mode. Call when an erase gesture begins. */
  fun enterEraseFastMode() {
    setMode(MODE_AUTOWRITE_FAST)
  }

  /**
   * Revert to quality/full-refresh mode. Call once an erase gesture has
   * been applied, so any fast-mode residue from it is flushed before the
   * next pen-down lands nearby.
   */
  fun exitEraseFastMode() {
    setMode(qualityModeForSdk())
  }

  private fun setMode(mode: String) {
    val m = manager() ?: return
    try {
      m.setMode(mode)
      EventLog.log(TAG, "setMode($mode)")
    } catch (t: Throwable) {
      EventLog.log(TAG, "ERROR setMode($mode): ${t.javaClass.simpleName}: ${t.message}")
    }
  }

  private fun qualityModeForSdk(): String = if (Build.VERSION.SDK_INT == 34) MODE_QUALITY_SDK34 else MODE_QUALITY_LEGACY

  companion object {
    private const val TAG = "EinkRefresh"

    private const val MODE_AUTOWRITE_FAST = "0"
    private const val MODE_QUALITY_SDK34 = "9"
    private const val MODE_QUALITY_LEGACY = "7"
  }
}
