package com.betterhv.note.doc

import com.betterhv.note.ink.Bounds

/**
 * 2D affine transform, spec §10:
 *
 *     [a  c  tx]
 *     [b  d  ty]
 *     [0  0  1 ]
 *
 * Deliberately our own 6-float struct rather than android.graphics.Matrix: this
 * package mirrors `ink`'s framework-free design so it stays unit-testable under
 * plain JUnit (no Robolectric). Callers that need to hand this to a Canvas
 * convert it at the app layer, not here.
 */
data class Transform2D(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val tx: Float,
    val ty: Float
) {
    /** Compose: applies [other] first, then this -- i.e. `this * other`. */
    fun times(other: Transform2D): Transform2D = Transform2D(
        a = a * other.a + c * other.b,
        b = b * other.a + d * other.b,
        c = a * other.c + c * other.d,
        d = b * other.c + d * other.d,
        tx = a * other.tx + c * other.ty + tx,
        ty = b * other.tx + d * other.ty + ty
    )

    fun mapPoint(x: Float, y: Float): FloatArray =
        floatArrayOf(a * x + c * y + tx, b * x + d * y + ty)

    fun mapRect(bounds: Bounds): Bounds {
        val p1 = mapPoint(bounds.left, bounds.top)
        val p2 = mapPoint(bounds.right, bounds.top)
        val p3 = mapPoint(bounds.left, bounds.bottom)
        val p4 = mapPoint(bounds.right, bounds.bottom)
        val minX = minOf(p1[0], p2[0], p3[0], p4[0])
        val maxX = maxOf(p1[0], p2[0], p3[0], p4[0])
        val minY = minOf(p1[1], p2[1], p3[1], p4[1])
        val maxY = maxOf(p1[1], p2[1], p3[1], p4[1])
        return Bounds(minX, minY, maxX, maxY)
    }

    /** Inverse transform, or null if this transform is singular (det == 0). */
    fun invert(): Transform2D? {
        val det = a * d - b * c
        if (kotlin.math.abs(det) < 1e-9f) return null
        val invDet = 1.0f / det
        val ia = d * invDet
        val ib = -b * invDet
        val ic = -c * invDet
        val id = a * invDet
        val itx = -(ia * tx + ic * ty)
        val ity = -(ib * tx + id * ty)
        return Transform2D(ia, ib, ic, id, itx, ity)
    }

    /**
     * Effective uniform scale factor, e.g. for converting a page-space radius
     * into the object's local space. Only exact for the pure translate+uniform
     * -scale compositions this app ever constructs (see [scaleAbout]); a
     * skewed/non-uniform transform would only have this as an approximation.
     */
    fun approximateScale(): Float = kotlin.math.sqrt(kotlin.math.abs(a * d - b * c))

    companion object {
        val IDENTITY = Transform2D(a = 1f, b = 0f, c = 0f, d = 1f, tx = 0f, ty = 0f)

        fun translate(dx: Float, dy: Float): Transform2D =
            Transform2D(a = 1f, b = 0f, c = 0f, d = 1f, tx = dx, ty = dy)

        /** Uniform scale by [scale] about [centerX], [centerY] (spec §39). */
        fun scaleAbout(centerX: Float, centerY: Float, scale: Float): Transform2D =
            Transform2D(
                a = scale, b = 0f, c = 0f, d = scale,
                tx = centerX - scale * centerX,
                ty = centerY - scale * centerY
            )

        fun rotateAbout(centerX: Float, centerY: Float, radians: Float): Transform2D {
            val cos = kotlin.math.cos(radians)
            val sin = kotlin.math.sin(radians)
            return Transform2D(
                a = cos, b = sin, c = -sin, d = cos,
                tx = centerX - cos * centerX + sin * centerY,
                ty = centerY - sin * centerX - cos * centerY
            )
        }
    }
}
