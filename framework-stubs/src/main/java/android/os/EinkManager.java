package android.os;

/**
 * Compile-only stub for the ROM framework class android.os.EinkManager.
 * Obtained at runtime via context.getSystemService("eink"). NOT packaged
 * into the APK.
 *
 * Unlike HvPenDrawManager, this signature has NOT been verified against a
 * real /system/framework/framework.jar dump -- it is reconstructed purely
 * from decompiled vendor app calls (hanvon.aebr.hvnote.commonData.
 * MyhvMemoryUtils.enterA2Mode / enterAutowrite in hvNote 7.16 smali), which
 * only ever call setMode(String) with the numeric-string mode codes below.
 * Confirm getSystemService("eink") actually returns a non-null EinkManager
 * on-device before relying on this.
 *
 * Observed mode codes (as string, not int):
 *   "13" - A2 / fast partial-refresh mode (entry)
 *   "0"  - autowrite / fastest mode, no dithering
 *   "7"  - quality/full-refresh revert, SDK_INT < 34
 *   "9"  - quality/full-refresh revert, SDK_INT == 34
 */
public class EinkManager {
    public void setMode(String mode) { throw new RuntimeException("stub"); }
}
