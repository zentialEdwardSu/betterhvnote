package com.betterhv.note.export

import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.ink.PenType
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PdfInkPoint(val x: Float, val y: Float)

internal data class PdfInkBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal data class PdfInkSample(val point: PdfInkPoint, val pressure: Float)

internal data class PdfInkAppearanceContent(
    val bytes: ByteArray,
    /** Percent opacity values referenced as /GSxx in the appearance stream. */
    val opacityBuckets: Set<Int>
)

/**
 * Framework-free description of one exported PDF Ink annotation.
 *
 * MuPDF's annotation API consumes page coordinates at 72 dpi. BetterHvNote's
 * document model uses logical 96 dpi pixels, so all object transforms are
 * applied first and the result is then scaled by [PDF_POINTS_PER_LOGICAL_PIXEL].
 */
internal class PdfInkAppearance private constructor(
    val source: StrokeObject,
    val samples: List<PdfInkSample>,
    val inkList: List<PdfInkPoint>,
    val borderWidth: Float,
    val red: Float,
    val green: Float,
    val blue: Float,
    val opacity: Float,
    private val normalLeft: List<PdfInkPoint>,
    private val normalRight: List<PdfInkPoint>
) {
    val penType: PenType get() = source.stroke.style.penType

    val visualBounds: PdfInkBounds
        get() {
            val points = if (penType == PenType.NormalPen && normalLeft.isNotEmpty()) {
                normalLeft + normalRight
            } else {
                samples.map(PdfInkSample::point)
            }
            val strokePadding = if (penType == PenType.NormalPen) 0f else borderWidth * 0.5f
            val padding = strokePadding + ANTIALIAS_MARGIN_POINTS
            return PdfInkBounds(
                left = points.minOf(PdfInkPoint::x) - padding,
                top = points.minOf(PdfInkPoint::y) - padding,
                right = points.maxOf(PdfInkPoint::x) + padding,
                bottom = points.maxOf(PdfInkPoint::y) + padding
            )
        }

    fun buildAppearance(bounds: PdfInkBounds): PdfInkAppearanceContent {
        require(bounds.width > 0f && bounds.height > 0f) { "Ink annotation bounds are empty" }
        val formatter = DecimalFormat("0.####", DecimalFormatSymbols(Locale.US)).apply {
            roundingMode = RoundingMode.HALF_UP
            isGroupingUsed = false
        }
        fun n(value: Float): String = formatter.format(if (abs(value) < 0.00005f) 0f else value)
        fun local(point: PdfInkPoint): PdfInkPoint =
            PdfInkPoint(point.x - bounds.left, point.y - bounds.top)

        val alphaBuckets = linkedSetOf<Int>()
        val content = buildString {
            append("q\n1 0 0 -1 0 ").append(n(bounds.height)).append(" cm\n")
            append(n(red)).append(' ').append(n(green)).append(' ').append(n(blue))
            when (penType) {
                PenType.NormalPen -> {
                    val bucket = opacityBucket(opacity)
                    alphaBuckets += bucket
                    append(" rg\n/").append(gsName(bucket)).append(" gs\n")
                    if (normalLeft.isNotEmpty()) {
                        val first = local(normalLeft.first())
                        append(n(first.x)).append(' ').append(n(first.y)).append(" m\n")
                        normalLeft.drop(1).forEach { point ->
                            val p = local(point)
                            append(n(p.x)).append(' ').append(n(p.y)).append(" l\n")
                        }
                        normalRight.asReversed().forEach { point ->
                            val p = local(point)
                            append(n(p.x)).append(' ').append(n(p.y)).append(" l\n")
                        }
                        append("h f\n")
                    }
                }

                PenType.Marker -> {
                    val bucket = opacityBucket(opacity)
                    alphaBuckets += bucket
                    append(" RG\n")
                    append(n(red)).append(' ').append(n(green)).append(' ').append(n(blue))
                        .append(" rg\n/").append(gsName(bucket)).append(" gs\n1 J 1 j\n")
                    append(n(borderWidth)).append(" w\n")
                    if (samples.size == 1) {
                        appendCircle(this, local(samples.first().point), borderWidth * 0.5f, formatter)
                    } else {
                        appendCenterline(this, samples.map(PdfInkSample::point), bounds, formatter)
                    }
                }

                PenType.Pencil -> {
                    append(" RG\n")
                    append(n(red)).append(' ').append(n(green)).append(' ').append(n(blue))
                        .append(" rg\n1 J 1 j\n")
                    if (samples.size == 1) {
                        val sample = samples.first()
                        val bucket = opacityBucket(opacity * pencilOpacity(sample.pressure))
                        alphaBuckets += bucket
                        append('/').append(gsName(bucket)).append(" gs\n")
                        val p = local(sample.point)
                        appendCircle(this, p, segmentWidth(sample.pressure) * 0.5f, formatter)
                    } else {
                        for (index in 1 until samples.size) {
                            val from = samples[index - 1]
                            val to = samples[index]
                            val pressure = ((from.pressure + to.pressure) * 0.5f).coerceIn(0f, 1f)
                            val bucket = opacityBucket(opacity * pencilOpacity(pressure))
                            alphaBuckets += bucket
                            append('/').append(gsName(bucket)).append(" gs\n")
                            append(n(segmentWidth(pressure))).append(" w\n")
                            val a = local(from.point)
                            val b = local(to.point)
                            append(n(a.x)).append(' ').append(n(a.y)).append(" m ")
                                .append(n(b.x)).append(' ').append(n(b.y)).append(" l S\n")
                        }
                    }
                }
            }
            append("Q\n")
        }
        return PdfInkAppearanceContent(content.toByteArray(Charsets.US_ASCII), alphaBuckets)
    }

    private fun segmentWidth(pressure: Float): Float =
        source.stroke.style.widthAt(pressure.coerceIn(0f, 1f)) *
            source.transform.approximateScale() * PDF_POINTS_PER_LOGICAL_PIXEL

    companion object {
        const val PDF_POINTS_PER_LOGICAL_PIXEL = 72f / 96f
        private const val MIN_BORDER_WIDTH_POINTS = 0.1f
        private const val ANTIALIAS_MARGIN_POINTS = 1f
        private const val CIRCLE_CONTROL = 0.5522848f

        fun from(source: StrokeObject): PdfInkAppearance? {
            val stroke = source.stroke
            if (stroke.points.isEmpty()) return null

            fun map(x: Float, y: Float): PdfInkPoint {
                val mapped = source.transform.mapPoint(x, y)
                return PdfInkPoint(
                    mapped[0] * PDF_POINTS_PER_LOGICAL_PIXEL,
                    mapped[1] * PDF_POINTS_PER_LOGICAL_PIXEL
                )
            }

            val samples = stroke.points.map { point ->
                PdfInkSample(map(point.x, point.y), point.pressure.coerceIn(0f, 1f))
            }
            val inkList = if (samples.size == 1) {
                listOf(samples[0].point, samples[0].point)
            } else {
                samples.map(PdfInkSample::point)
            }
            val outline = if (stroke.style.penType == PenType.NormalPen) stroke.outline else null
            val left = outline?.left?.toPdfPoints(::map).orEmpty()
            val right = outline?.right?.toPdfPoints(::map).orEmpty()
            val color = stroke.style.color
            return PdfInkAppearance(
                source = source,
                samples = samples,
                inkList = inkList,
                borderWidth = (stroke.style.maxWidth * source.transform.approximateScale() *
                    PDF_POINTS_PER_LOGICAL_PIXEL).coerceAtLeast(MIN_BORDER_WIDTH_POINTS),
                red = ((color ushr 16) and 0xFF) / 255f,
                green = ((color ushr 8) and 0xFF) / 255f,
                blue = (color and 0xFF) / 255f,
                opacity = ((color ushr 24) and 0xFF) / 255f,
                normalLeft = left,
                normalRight = right
            )
        }

        private fun FloatArray.toPdfPoints(map: (Float, Float) -> PdfInkPoint): List<PdfInkPoint> =
            List(size / 2) { index -> map(this[index * 2], this[index * 2 + 1]) }

        private fun appendCenterline(
            target: StringBuilder,
            points: List<PdfInkPoint>,
            bounds: PdfInkBounds,
            formatter: DecimalFormat
        ) {
            if (points.isEmpty()) return
            fun n(value: Float): String = formatter.format(if (abs(value) < 0.00005f) 0f else value)
            val first = points.first()
            target.append(n(first.x - bounds.left)).append(' ')
                .append(n(first.y - bounds.top)).append(" m\n")
            if (points.size == 1) {
                target.append(n(first.x - bounds.left)).append(' ')
                    .append(n(first.y - bounds.top)).append(" l\n")
            } else {
                points.drop(1).forEach { point ->
                    target.append(n(point.x - bounds.left)).append(' ')
                        .append(n(point.y - bounds.top)).append(" l\n")
                }
            }
            target.append("S\n")
        }

        private fun appendCircle(
            target: StringBuilder,
            center: PdfInkPoint,
            radius: Float,
            formatter: DecimalFormat
        ) {
            fun n(value: Float): String = formatter.format(if (abs(value) < 0.00005f) 0f else value)
            val control = radius * CIRCLE_CONTROL
            val left = center.x - radius
            val right = center.x + radius
            val top = center.y - radius
            val bottom = center.y + radius
            target.append(n(center.x)).append(' ').append(n(top)).append(" m\n")
            target.append(n(center.x + control)).append(' ').append(n(top)).append(' ')
                .append(n(right)).append(' ').append(n(center.y - control)).append(' ')
                .append(n(right)).append(' ').append(n(center.y)).append(" c\n")
            target.append(n(right)).append(' ').append(n(center.y + control)).append(' ')
                .append(n(center.x + control)).append(' ').append(n(bottom)).append(' ')
                .append(n(center.x)).append(' ').append(n(bottom)).append(" c\n")
            target.append(n(center.x - control)).append(' ').append(n(bottom)).append(' ')
                .append(n(left)).append(' ').append(n(center.y + control)).append(' ')
                .append(n(left)).append(' ').append(n(center.y)).append(" c\n")
            target.append(n(left)).append(' ').append(n(center.y - control)).append(' ')
                .append(n(center.x - control)).append(' ').append(n(top)).append(' ')
                .append(n(center.x)).append(' ').append(n(top)).append(" c f\n")
        }

        private fun pencilOpacity(pressure: Float): Float = 0.35f + 0.45f * pressure.coerceIn(0f, 1f)
        private fun opacityBucket(opacity: Float): Int = (opacity * 100f).roundToInt().coerceIn(0, 100)
        fun gsName(bucket: Int): String = "GS${bucket.coerceIn(0, 100)}"
    }
}
