package com.betterhv.note

import android.graphics.Bitmap
import android.graphics.Color
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.jni.InkStrokeModelerJNI
import com.betterhv.note.jni.ModelerStrokeSmoother
import hanvon.aebr.dither.HVDitherJNI
import hanvon.aebr.hvnote.jni.GraphJniUtil
import hanvon.aebr.penengine.HWPenEngine

/**
 * Phase-1 verification: confirm each migrated native library loads and a
 * cheap native call returns without UnsatisfiedLinkError. Results go to
 * the on-screen EventLog overlay.
 */
object NativeSelfTest {
    fun run() {
        // libHwGraphUtil.so — getVersionFromJni is NOT compiled into this .so
        // (verified: absent from the binary), so probe the real static symbol
        // nativeDrawGraph (Java_hanvon_aebr_hvnote_jni_GraphJniUtil_nativeDrawGraph).
        try {
            val xs = intArrayOf(10, 20, 30, 40)
            val ys = intArrayOf(10, 25, 30, 45)
            val r = GraphJniUtil.nativeDrawGraph(xs, ys)
            EventLog.log("SelfTest", "GraphJniUtil loaded, nativeDrawGraph -> '${r?.take(40)}'")
        } catch (t: Throwable) {
            EventLog.log("SelfTest", "GraphJniUtil FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        // libhw_PenEngine.so — pen rendering no longer goes through this library
        // (stroke geometry and rasterization are ours now), so only confirm the
        // library still loads. Kept because the eraser rework in the editing
        // phase may still want the native engine; drop the wrapper if it does not.
        try {
            val d3566 = HWPenEngine.DENSITY_3566
            EventLog.log("SelfTest", "HWPenEngine loaded (DENSITY_3566=$d3566, unused for rendering)")
        } catch (t: Throwable) {
            EventLog.log("SelfTest", "HWPenEngine FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        // libhvdither.so
        try {
            val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.GRAY)
            HVDitherJNI.ditherBitmapARGB32(bmp, 0)
            EventLog.log("SelfTest", "HVDitherJNI loaded, ditherBitmapARGB32 returned")
        } catch (t: Throwable) {
            EventLog.log("SelfTest", "HVDitherJNI FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        // libink_stroke_modeler_jni.so (Google ink-stroke-modeler). Drive a full
        // DOWN->MOVE->pen-up round-trip through the smoother so this confirms not
        // just that the .so loaded, but that the native modeler actually produces
        // output rather than silently degrading to the OneEuro fallback -- which
        // is otherwise invisible on-device short of writing by hand.
        try {
            val smoother = ModelerStrokeSmoother()
            var t = 1_000L
            val batch = (0..8).map { InkPoint(it * 12f, it * 6f, 0.5f, t++) }
            val modeled = smoother.smoothBatch(batch)
            val tail = smoother.finishStroke()
            EventLog.log(
                "SelfTest",
                "InkStrokeModeler available=${InkStrokeModelerJNI.available} " +
                    "in=${batch.size} modeled=${modeled.size} tail=${tail.size}"
            )
        } catch (t: Throwable) {
            EventLog.log("SelfTest", "InkStrokeModeler FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
