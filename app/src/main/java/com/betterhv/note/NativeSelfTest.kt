package com.betterhv.note

import com.betterhv.note.ink.InkPoint
import com.betterhv.note.jni.InkStrokeModelerJNI
import com.betterhv.note.jni.ModelerStrokeSmoother

/**
 * Verifies the one app-owned JNI library that remains in the package. The
 * legacy Hanvon ROM binaries are intentionally not packaged because their
 * ELF segments are only 4 KiB aligned and production rendering no longer
 * depends on them.
 */
object NativeSelfTest {
    fun run() {
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
