package com.betterhv.note.ink

/**
 * A finished stroke: the authoritative vector record of one pen gesture.
 * Spec §2.1 -- this is the real data; any bitmap is a cache that can be thrown
 * away and regenerated from these points.
 *
 * This remains deliberately narrow after Document Core landed: identity,
 * z-order, transform, and object timestamps live in StrokeObject/PageObject.
 * Per-point timestamps stay here for the §2.5 timeline requirement.
 */
data class Stroke(
    val points: List<InkPoint>,
    val style: PenStyle
) {
    val bounds: Bounds by lazy {
        // Marker is rendered as a round-capped centerline. Its cap extends by
        // half a width beyond both endpoints, which the ribbon-only bounds do
        // not include. Keep spatial queries and selection aware of those pixels.
        if (style.penType == PenType.Marker) {
            Bounds.of(points).inflate(style.maxWidth * 0.5f)
        } else {
            outline.bounds
        }
    }

    /**
     * Cached because the renderer asks for this on every repaint of a committed
     * stroke, and rebuilding the outline for thousands of strokes per frame is
     * the difference between a smooth page and a visible stall.
     */
    val outline: StrokeOutline by lazy { StrokeGeometry.build(points, style) }

    val startTime: Long get() = points.firstOrNull()?.timestamp ?: 0L
    val endTime: Long get() = points.lastOrNull()?.timestamp ?: 0L
}
