package android.os;

/**
 * Compile-only stub for the Hanvon ROM framework interface
 * android.os.HvPenDrawListener. Provided by the device at runtime;
 * this stub is NOT packaged into the APK (compileOnly source set).
 *
 * Verified from /system/framework/framework.jar on N10Pro (API 34):
 *   single method onPenTouchUpStatus(boolean, float[]), SDK,TEST-API flagged.
 */
public interface HvPenDrawListener {
    void onPenTouchUpStatus(boolean up, float[] points);
}
