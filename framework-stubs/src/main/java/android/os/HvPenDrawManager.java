package android.os;

/**
 * Compile-only stub for the Hanvon ROM framework class
 * android.os.HvPenDrawManager. Obtained at runtime via
 * context.getSystemService("hvpen"). NOT packaged into the APK.
 *
 * Signatures + constants verified from /system/framework/framework.jar
 * on N10Pro (Android 14 / API 34). All methods flagged SDK,TEST-API and
 * the backing service com.android.server.hvpendraw.HvPenDrawService has
 * no permission checks, so an ordinary app may call them.
 */
public class HvPenDrawManager {
    // Draw modes
    public static final int PEN_MODE = 0;
    public static final int REGION_ERASER_MODE = 1;
    public static final int ADD_BLANK_MODE = 2;
    public static final int THIRD_APP_MODE = 3;
    public static final int BUTTON_PEN_MODE = 4;
    // Screen origin positions
    public static final int RIGHT_TOP = 0;
    public static final int LEFT_BOTTOM = 1;
    public static final int LEFT_TOP = 2;
    // Orientation
    public static final int ORIENTATION_VERTICAL = 1;
    public static final int ORIENTATION_HORIZONTAL = 2;
    // Pen colors
    public static final int PEN_ALPHA_COLOR = 0;
    public static final int PEN_BLACK_COLOR = 1;
    public static final int PEN_WHITE_COLOR = 2;
    public static final int PEN_DKGRAY_COLOR = 3;
    public static final int PEN_LTGRAY_COLOR = 4;

    public static int getScreenOrgPos() { throw new RuntimeException("stub"); }
    public long initService(int l, int t, int r, int b, HvPenDrawListener listener) { throw new RuntimeException("stub"); }
    public void endService(long h, HvPenDrawListener listener) { throw new RuntimeException("stub"); }
    public void clearService() { throw new RuntimeException("stub"); }
    public void enablePen(long h, boolean enable) { throw new RuntimeException("stub"); }
    public void resetData() { throw new RuntimeException("stub"); }
    public void setAreaActive(long h, boolean active) { throw new RuntimeException("stub"); }
    public void setDrawArea(long h, int l, int t, int r, int b) { throw new RuntimeException("stub"); }
    public void setDrawStatus(long h, int status) { throw new RuntimeException("stub"); }
    public void setExceptionArea(long h, int l, int t, int r, int b) { throw new RuntimeException("stub"); }
    public void setOrientation(int o) { throw new RuntimeException("stub"); }
    public void setOverlay(boolean overlay) { throw new RuntimeException("stub"); }
    public void setPen(long h, int pen) { throw new RuntimeException("stub"); }
    public void setPenColor(long h, int color) { throw new RuntimeException("stub"); }
    public void setPenWidth(long h, int width) { throw new RuntimeException("stub"); }
}
