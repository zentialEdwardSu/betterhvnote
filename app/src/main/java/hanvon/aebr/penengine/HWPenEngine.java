package hanvon.aebr.penengine;

/**
 * JNI wrapper for libhw_PenEngine.so. Package + class name MUST stay
 * exactly "hanvon.aebr.penengine.HWPenEngine" — the native library
 * stores this class descriptor for dynamic JNI registration in
 * JNI_OnLoad. Ported verbatim from the decompiled hvNote 7.16 smali.
 */
public class HWPenEngine {
    public static int DENSITY_3566;
    public static int DENSITY_3576;

    static {
        try {
            System.loadLibrary("hw_PenEngine");
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
        DENSITY_3566 = 1;
        DENSITY_3576 = 2;
    }

    public static native void beginStroke(long j);
    public static native boolean destroyEngine(long j);
    public static native void drawPencil32(int width, int height, int density, int color,
                                           int[] pixels, int count, float[] points, int[] mode);
    public static native int endStroke(long j, int[] a, float[] b);
    public static native void fillSurface(long j, int i);
    public static native float getSize(long j);
    public static native long initialize(int i, int j, int[] a);
    public static native void resetScan(long j, int[] a, int i);
    public static native void setColor(long j, byte a, byte b, byte c, byte d);
    public static native void setKernelOriginPos(long j, int i);
    public static native void setPenSize(long j, float f);
    public static native void setPenStyle(long j, int i);
    public static native int strokePoint(long j, float x, float y, float p, int[] a, float[] b);
}
