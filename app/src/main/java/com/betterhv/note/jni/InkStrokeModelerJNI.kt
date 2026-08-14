package com.betterhv.note.jni

/**
 * Thin JNI wrapper over libink_stroke_modeler_jni.so (Google's
 * ink-stroke-modeler). This class does nothing but declare the native methods
 * and load the library; all policy (batching, fallback, InkPoint marshaling)
 * lives in [ModelerStrokeSmoother].
 *
 * Package + class name MUST stay `com.betterhv.note.jni.InkStrokeModelerJNI`:
 * the native symbols are statically named `Java_com_betterhv_note_jni_
 * InkStrokeModelerJNI_native*` (verify with `python tools/elfsyms.py <lib>
 * InkStrokeModeler`), the same descriptor-coupling constraint the three vendor
 * JNI wrappers document. The native methods are instance (not static) methods,
 * so the bridge is exported with a `jobject` receiver -- keep them non-static.
 *
 * Native state is a single process-wide StrokeModeler (see the .cc). One
 * gesture at a time: call [nativeReset] at pen-down, [nativeUpdate] per batch,
 * [nativeClear] at pen-up. Not safe to drive from multiple threads at once;
 * PenDrawView funnels everything through one Handler thread.
 *
 * [available] is false when the .so failed to load (wrong ABI, missing lib,
 * build without native). Callers must check it and degrade gracefully rather
 * than invoking the native methods, which would throw UnsatisfiedLinkError.
 */
class InkStrokeModelerJNI {

    /** Result count (>=0) or a negative error code; see the .cc for the codes. */
    external fun nativeReset(): Int

    /**
     * @param inputXYP flat [x,y,pressure, ...], length >= 3 * eventTypes.size
     * @param eventTypes one byte per sample: 0=DOWN, 2=UP, else MOVE
     * @param outXYP caller-allocated output, filled with [x,y,pressure] triples
     * @return number of modeled Results written (>=0), or a negative error code
     */
    external fun nativeUpdate(inputXYP: FloatArray, eventTypes: ByteArray, outXYP: FloatArray): Int

    /** Speculative tail; fills [outXYP], returns count or negative error. */
    external fun nativePredict(outXYP: FloatArray): Int

    external fun nativeSave()
    external fun nativeRestore()
    external fun nativeClear()

    companion object {
        /** True if libink_stroke_modeler_jni.so loaded; false means degrade. */
        val available: Boolean = try {
            System.loadLibrary("ink_stroke_modeler_jni")
            true
        } catch (t: Throwable) {
            false
        }
    }
}
