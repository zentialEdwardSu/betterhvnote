package com.betterhv.note

import android.graphics.Rect

/**
 * Bit-exact port of HandView.getPenDrawArea / getPenOrientation /
 * getScreenOrgPos from hvNote 7.16 smali. Translated register-by-register
 * from the dalvik bytecode to preserve the exact rotation mapping the ROM
 * expects. Do not "simplify" — the branch structure mirrors the original.
 */
object PenGeometry {

    /** HandView.getScreenOrgPos(): most models use RIGHT_TOP(0); N10Pro/M10/C10 use 1. */
    fun getScreenOrgPos(model: String): Int {
        return if (model.startsWith("N10Pro") || model.startsWith("M10") || model.startsWith("C10")) 0 else 1
    }

    /**
     * getPenDrawArea(rect, rotation, orgPos, screenWidth, screenHeight).
     * p0=rect, p1=rotation, p2=orgPos, p3=screenWidth, p4=screenHeight.
     * Final Rect is constructed as Rect(v3, p2Out, p1Out, p0Out).
     */
    fun getPenDrawArea(rect: Rect, rotation: Int, orgPos: Int, screenWidth: Int, screenHeight: Int): Rect {
        val p3 = screenWidth
        val p4 = screenHeight
        val w = rect.width()
        val h = rect.height()
        // outputs (left, top, right, bottom) = (v3, p2, p1, p0)
        val l: Int; val t: Int; val r: Int; val b: Int
        when (orgPos) {
            2 -> when (rotation) { // LEFT_TOP
                0 -> { l = rect.left; t = rect.top; r = rect.right; b = rect.bottom }
                1 -> { l = p4 - rect.bottom; t = rect.left; r = p4 - rect.top; b = rect.right }
                2 -> { l = p3 - rect.right; t = p4 - rect.bottom; r = p3 - rect.left; b = p4 - rect.top }
                3 -> { l = rect.top; t = p3 - rect.right; r = rect.bottom; b = p3 - rect.left }
                else -> { l = 0; t = 0; r = 0; b = 0 }
            }
            0 -> when (rotation) { // RIGHT_TOP
                0 -> { l = rect.top; t = p3 - rect.right; r = rect.bottom; b = p3 - rect.left }
                1 -> { l = rect.left; t = rect.top; r = rect.right; b = rect.bottom }
                2 -> { l = p4 - rect.top - h; t = rect.left; r = p4 - rect.top; b = w + rect.left }
                3 -> { l = p3 - rect.right; t = p4 - rect.bottom; r = w + (p3 - rect.right); b = h + (p4 - rect.bottom) }
                else -> { l = 0; t = 0; r = 0; b = 0 }
            }
            1 -> when (rotation) { // LEFT_BOTTOM
                0 -> { l = p4 - rect.top - h; t = rect.left; r = p4 - rect.top; b = w + rect.left }
                1 -> { l = p3 - rect.right; t = p4 - rect.bottom; r = w + (p3 - rect.right); b = h + (p4 - rect.bottom) }
                2 -> { l = rect.top; t = p3 - rect.right; r = rect.bottom; b = p3 - rect.left }
                3 -> { l = rect.left; t = rect.top; r = rect.right; b = rect.bottom }
                else -> { l = 0; t = 0; r = 0; b = 0 }
            }
            else -> { l = 0; t = 0; r = 0; b = 0 }
        }
        return Rect(l, t, r, b)
    }

    /**
     * getPenOrientation(a, b): bit-exact from smali. b selects a family
     * (2→{3..6}, 0→{11..14}, else→{7..10}); a in 0..3 indexes within it.
     * Returns 0 for out-of-range.
     */
    fun getPenOrientation(a: Int, b: Int): Int {
        return when (b) {
            2 -> when (a) { 0 -> 3; 1 -> 4; 2 -> 5; 3 -> 6; else -> 0 }
            0 -> when (a) { 0 -> 11; 1 -> 12; 2 -> 13; 3 -> 14; else -> 0 }
            else -> when (a) { 0 -> 7; 1 -> 8; 2 -> 9; 3 -> 10; else -> 0 }
        }
    }
}
