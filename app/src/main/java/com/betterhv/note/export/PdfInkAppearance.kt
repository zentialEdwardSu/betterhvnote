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

internal data class PdfInkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  val width: Float get() = right - left
  val height: Float get() = bottom - top
}

internal data class PdfInkSample(val point: PdfInkPoint, val width: Float)
internal data class PdfInkDisc(val center: PdfInkPoint, val radius: Float)

internal data class PdfInkAppearanceContent(
  val bytes: ByteArray,
  /** Percent opacity values referenced as /GSxx in the appearance stream. */
  val opacityBuckets: Set<Int>,
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
  private val shapeDiscs: List<PdfInkDisc>,
  private val shapeBodies: List<List<PdfInkPoint>>,
) {
  val penType: PenType get() = source.stroke.style.penType

  val visualBounds: PdfInkBounds
    get() {
      val bodyPoints = shapeBodies.flatten()
      val left = minOf(
        shapeDiscs.minOf { it.center.x - it.radius },
        bodyPoints.minOfOrNull(PdfInkPoint::x) ?: Float.MAX_VALUE,
      )
      val top = minOf(
        shapeDiscs.minOf { it.center.y - it.radius },
        bodyPoints.minOfOrNull(PdfInkPoint::y) ?: Float.MAX_VALUE,
      )
      val right = maxOf(
        shapeDiscs.maxOf { it.center.x + it.radius },
        bodyPoints.maxOfOrNull(PdfInkPoint::x) ?: -Float.MAX_VALUE,
      )
      val bottom = maxOf(
        shapeDiscs.maxOf { it.center.y + it.radius },
        bodyPoints.maxOfOrNull(PdfInkPoint::y) ?: -Float.MAX_VALUE,
      )
      return PdfInkBounds(
        left - ANTIALIAS_MARGIN_POINTS,
        top - ANTIALIAS_MARGIN_POINTS,
        right + ANTIALIAS_MARGIN_POINTS,
        bottom + ANTIALIAS_MARGIN_POINTS,
      )
    }

  fun buildAppearance(bounds: PdfInkBounds): PdfInkAppearanceContent {
    require(bounds.width > 0f && bounds.height > 0f) { "Ink annotation bounds are empty" }
    val formatter = DecimalFormat("0.####", DecimalFormatSymbols(Locale.US)).apply {
      roundingMode = RoundingMode.HALF_UP
      isGroupingUsed = false
    }
    fun n(value: Float): String = formatter.format(if (abs(value) < 0.00005f) 0f else value)
    fun local(point: PdfInkPoint): PdfInkPoint = PdfInkPoint(point.x - bounds.left, point.y - bounds.top)

    val alphaBuckets = linkedSetOf<Int>()
    val content = buildString {
      append("q\n1 0 0 -1 0 ").append(n(bounds.height)).append(" cm\n")
      val bucket = opacityBucket(opacity * if (penType == PenType.Pencil) PENCIL_OPACITY else 1f)
      alphaBuckets += bucket
      append(n(red)).append(' ').append(n(green)).append(' ').append(n(blue))
        .append(" rg\n/").append(gsName(bucket)).append(" gs\n")
      shapeBodies.forEach { body ->
        val first = local(body.first())
        append(n(first.x)).append(' ').append(n(first.y)).append(" m\n")
        body.drop(1).forEach { point ->
          val p = local(point)
          append(n(p.x)).append(' ').append(n(p.y)).append(" l\n")
        }
        append("h\n")
      }
      shapeDiscs.forEach { disc ->
        if (disc.radius > 0f) appendCirclePath(this, local(disc.center), disc.radius, formatter)
      }
      append("f\n")
      append("Q\n")
    }
    return PdfInkAppearanceContent(content.toByteArray(Charsets.US_ASCII), alphaBuckets)
  }

  companion object {
    const val PDF_POINTS_PER_LOGICAL_PIXEL = 72f / 96f
    private const val MIN_BORDER_WIDTH_POINTS = 0.1f
    private const val ANTIALIAS_MARGIN_POINTS = 1f
    private const val CIRCLE_CONTROL = 0.5522848f
    private const val PENCIL_OPACITY = 0.8f

    fun from(source: StrokeObject): PdfInkAppearance? {
      val stroke = source.stroke
      if (stroke.points.isEmpty()) return null

      fun map(x: Float, y: Float): PdfInkPoint {
        val mapped = source.transform.mapPoint(x, y)
        return PdfInkPoint(
          mapped[0] * PDF_POINTS_PER_LOGICAL_PIXEL,
          mapped[1] * PDF_POINTS_PER_LOGICAL_PIXEL,
        )
      }

      val transformedWidthScale = source.transform.approximateScale() * PDF_POINTS_PER_LOGICAL_PIXEL
      val samples = stroke.points.map { point ->
        PdfInkSample(map(point.x, point.y), stroke.style.widthAt(point) * transformedWidthScale)
      }
      val outline = stroke.outline
      val shapeDiscs = outline.discs.toList().chunked(3).map { values ->
        PdfInkDisc(map(values[0], values[1]), values[2] * transformedWidthScale)
      }
      val shapeBodies = outline.bodies.toList().chunked(8).map { values ->
        listOf(
          map(values[0], values[1]),
          map(values[2], values[3]),
          map(values[4], values[5]),
          map(values[6], values[7]),
        )
      }
      val inkList = if (samples.size == 1) {
        listOf(samples[0].point, samples[0].point)
      } else {
        samples.map(PdfInkSample::point)
      }
      val color = stroke.style.color
      return PdfInkAppearance(
        source = source,
        samples = samples,
        inkList = inkList,
        borderWidth = (
          samples.maxOf(PdfInkSample::width)
          ).coerceAtLeast(MIN_BORDER_WIDTH_POINTS),
        red = ((color ushr 16) and 0xFF) / 255f,
        green = ((color ushr 8) and 0xFF) / 255f,
        blue = (color and 0xFF) / 255f,
        opacity = ((color ushr 24) and 0xFF) / 255f,
        shapeDiscs = shapeDiscs,
        shapeBodies = shapeBodies,
      )
    }

    private fun appendCirclePath(
      target: StringBuilder,
      center: PdfInkPoint,
      radius: Float,
      formatter: DecimalFormat,
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
        .append(n(center.x)).append(' ').append(n(top)).append(" c\n")
    }

    private fun opacityBucket(opacity: Float): Int = (opacity * 100f).roundToInt().coerceIn(0, 100)
    fun gsName(bucket: Int): String = "GS${bucket.coerceIn(0, 100)}"
  }
}
