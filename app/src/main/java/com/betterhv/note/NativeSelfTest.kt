package com.betterhv.note

import android.graphics.Bitmap
import android.graphics.Color
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
        // libhw_PenEngine.so — touch static init (loadLibrary) + a native call
        try {
            val d3566 = HWPenEngine.DENSITY_3566
            val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(16)
            val pts = floatArrayOf(1f, 1f, 1f, 2f, 2f, 1f)
            HWPenEngine.drawPencil32(4, 4, d3566, Color.BLACK, pixels, 0, pts, IntArray(4))
            EventLog.log("SelfTest", "HWPenEngine loaded, drawPencil32 returned (DENSITY_3566=$d3566)")
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
    }
}
