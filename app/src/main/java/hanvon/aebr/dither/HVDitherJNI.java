package hanvon.aebr.dither;

import android.graphics.Bitmap;

/**
 * JNI wrapper for libhvdither.so (E-ink grayscale dithering). Package +
 * class name MUST stay "hanvon.aebr.dither.HVDitherJNI" — the native
 * library registers against this descriptor in JNI_OnLoad. Ported
 * verbatim from decompiled hvNote 7.16 smali.
 */
public class HVDitherJNI {
    static {
        System.loadLibrary("hvdither");
    }

    public static native void AdjustBitmap(Bitmap bmp, int a, int b, int c, int d, int e);
    public static native byte[] convertToA8(Bitmap bmp);
    public static native void ditherBitmapA8(Bitmap bmp, int i);
    public static native void ditherBitmapARGB32(Bitmap bmp, int i);
    public static native void ditherBitmapARGB32Color(Bitmap bmp);
    public static native void ditherBitmapARGB32To4Grey(Bitmap bmp);
}
