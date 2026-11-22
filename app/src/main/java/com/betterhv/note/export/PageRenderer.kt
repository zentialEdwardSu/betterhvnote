package com.betterhv.note.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withMatrix
import androidx.core.graphics.withSave
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.PageKind
import com.betterhv.note.doc.PageObject
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.TextObject
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.ink.InkRenderer
import com.betterhv.note.storage.PageSnapshot
import com.betterhv.note.template.TemplateStore
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Computes power-of-two BitmapFactory sampling bounded by target and max decoded dimension. */
internal object ExportImageSampling {
  fun inSampleSize(
    srcWidth: Int,
    srcHeight: Int,
    reqWidth: Int,
    reqHeight: Int,
    maxDimension: Int = 4096,
  ): Int {
    if (srcWidth <= 0 || srcHeight <= 0) return 1
    var sample = 1
    if (reqWidth > 0 && reqHeight > 0) {
      val halfWidth = srcWidth / 2
      val halfHeight = srcHeight / 2
      while (
        sample <= Int.MAX_VALUE / 2 &&
        halfWidth / sample >= reqWidth &&
        halfHeight / sample >= reqHeight
      ) {
        sample *= 2
      }
    }
    if (maxDimension > 0) {
      while (
        sample <= Int.MAX_VALUE / 2 &&
        (srcWidth / sample > maxDimension || srcHeight / sample > maxDimension)
      ) {
        sample *= 2
      }
    }
    return sample
  }
}

/** One Canvas rendering path is shared by screen-independent PNG and PDF output. */
class PageRenderer(private val documentsDir: File, private val templates: TemplateStore? = null) {
  private val inkRenderer = InkRenderer()
  private val imageRoot = File(documentsDir, "assets").canonicalFile.toPath()

  fun renderToBitmap(
    snapshot: PageSnapshot,
    widthPx: Int,
    heightPx: Int,
    backgroundColor: Int = Color.WHITE,
    checkActive: () -> Unit = {},
  ): Bitmap {
    require(widthPx > 0 && heightPx > 0) { "Invalid export dimensions" }
    requireValidPageDimensions(snapshot)
    checkActive()
    val bitmap = createBitmap(widthPx, heightPx)
    val scaleX = widthPx / snapshot.metadata.width
    val scaleY = heightPx / snapshot.metadata.height
    Canvas(bitmap).apply {
      drawColor(backgroundColor)
      renderTemplate(this, snapshot, RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()))
      withSave {
        scale(scaleX, scaleY)
        renderSnapshot(this, snapshot, canvasScale = maxOf(scaleX, scaleY), checkActive = checkActive)
      }
    }
    return bitmap
  }

  /**
   * Draws the page's objects (text, images, strokes) over an existing bitmap.
   * Fast PDF export uses this to overlay ink on a rasterized PDF source page
   * without re-rendering the background.
   */
  fun renderObjectsOnto(bitmap: Bitmap, snapshot: PageSnapshot, checkActive: () -> Unit = {}) {
    require(!bitmap.isRecycled) { "Cannot render onto a recycled bitmap" }
    require(bitmap.isMutable) { "renderObjectsOnto requires a mutable bitmap" }
    requireValidPageDimensions(snapshot)
    val scaleX = bitmap.width / snapshot.metadata.width
    val scaleY = bitmap.height / snapshot.metadata.height
    Canvas(bitmap).withSave {
      scale(scaleX, scaleY)
      renderSnapshot(this, snapshot, canvasScale = maxOf(scaleX, scaleY), checkActive = checkActive)
    }
  }

  /**
   * Render the non-ink PDF page contents. Strokes are added afterwards as
   * editable PDF Ink annotations by [PdfInkAnnotationWriter].
   */
  fun renderSinglePagePdfBase(snapshot: PageSnapshot, output: File, checkActive: () -> Unit = {}) {
    renderSinglePagePdf(snapshot, output, drawBackground = true, checkActive = checkActive)
  }

  /**
   * Render only non-ink objects (text and images) onto a transparent PDF page
   * for grafting over an existing [PageKind.PDF_SOURCE] page.
   */
  fun renderSinglePagePdfOverlay(snapshot: PageSnapshot, output: File, checkActive: () -> Unit = {}) {
    renderSinglePagePdf(snapshot, output, drawBackground = false, checkActive = checkActive)
  }

  private fun renderSinglePagePdf(
    snapshot: PageSnapshot,
    output: File,
    drawBackground: Boolean,
    checkActive: () -> Unit,
  ) {
    requireValidPageDimensions(snapshot)
    output.parentFile?.mkdirs()
    val widthPoints = (snapshot.metadata.width * PDF_POINTS_PER_LOGICAL_PIXEL).roundToInt().coerceAtLeast(1)
    val heightPoints = (snapshot.metadata.height * PDF_POINTS_PER_LOGICAL_PIXEL).roundToInt().coerceAtLeast(1)
    val scaleX = widthPoints / snapshot.metadata.width
    val scaleY = heightPoints / snapshot.metadata.height
    val document = PdfDocument()
    try {
      checkActive()
      val page = document.startPage(PdfDocument.PageInfo.Builder(widthPoints, heightPoints, 1).create())
      if (drawBackground) {
        page.canvas.drawColor(Color.WHITE)
        renderTemplate(
          page.canvas,
          snapshot,
          RectF(0f, 0f, widthPoints.toFloat(), heightPoints.toFloat()),
        )
      }
      page.canvas.save()
      page.canvas.scale(scaleX, scaleY)
      renderSnapshot(
        page.canvas,
        snapshot,
        canvasScale = maxOf(scaleX, scaleY),
        includeStrokes = false,
        checkActive = checkActive,
      )
      page.canvas.restore()
      document.finishPage(page)
      FileOutputStream(output).use(document::writeTo)
    } finally {
      document.close()
    }
  }

  private fun requireValidPageDimensions(snapshot: PageSnapshot) {
    require(
      snapshot.metadata.width.isFinite() &&
        snapshot.metadata.height.isFinite() &&
        snapshot.metadata.width > 0f &&
        snapshot.metadata.height > 0f,
    ) { "Invalid page dimensions" }
  }

  private fun renderSnapshot(
    canvas: Canvas,
    snapshot: PageSnapshot,
    canvasScale: Float = 1f,
    includeStrokes: Boolean = true,
    checkActive: () -> Unit = {},
  ) {
    snapshot.objects.sortedBy(PageObject::zIndex).forEach { obj ->
      checkActive()
      if (!includeStrokes && obj is StrokeObject) return@forEach
      canvas.withMatrix(obj.transform.toMatrix()) {
        when (obj) {
          is StrokeObject -> inkRenderer.drawStroke(canvas, obj.stroke)
          is TextObject -> drawText(canvas, obj)
          is ImageObject -> drawImage(canvas, obj, canvasScale)
        }
      }
    }
  }

  private fun renderTemplate(canvas: Canvas, snapshot: PageSnapshot, target: RectF) {
    if (snapshot.metadata.kind == PageKind.PDF_SOURCE) return
    val store = templates ?: return
    val definition = store.resolve(snapshot.metadata.templateId) ?: return
    if (!definition.isCompatible(snapshot.metadata.width, snapshot.metadata.height)) return
    store.renderer.draw(canvas, definition, target)
  }

  private fun drawText(canvas: Canvas, obj: TextObject) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      textSize = obj.fontSize
      typeface = Typeface.create(obj.fontFamily.androidName, Typeface.NORMAL)
    }
    val metrics = paint.fontMetrics
    var baseline = -metrics.ascent
    val lineHeight = metrics.descent - metrics.ascent
    obj.text.split('\n').forEach { line ->
      canvas.drawText(line, 0f, baseline, paint)
      baseline += lineHeight
    }
  }

  private fun drawImage(canvas: Canvas, obj: ImageObject, canvasScale: Float) {
    val file = runCatching { File(documentsDir, obj.assetPath).canonicalFile }.getOrNull()
      ?.takeIf { it.toPath().startsWith(imageRoot) && it.isFile } ?: return
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
    val effectiveScale = (canvasScale * obj.transform.approximateScale()).let {
      if (it.isFinite() && it > 0f) it else 1f
    }
    val reqWidth = (obj.pixelWidth * effectiveScale).roundToInt().coerceAtLeast(1)
    val reqHeight = (obj.pixelHeight * effectiveScale).roundToInt().coerceAtLeast(1)
    val sampleSize = ExportImageSampling.inSampleSize(
      bounds.outWidth,
      bounds.outHeight,
      reqWidth,
      reqHeight,
    )
    val bitmap = BitmapFactory.decodeFile(
      file.absolutePath,
      BitmapFactory.Options().apply { inSampleSize = sampleSize },
    ) ?: return
    try {
      canvas.withMatrix(exifMatrix(obj.exifOrientation, obj.pixelWidth.toFloat(), obj.pixelHeight.toFloat())) {
        canvas.drawBitmap(
          bitmap,
          null,
          RectF(0f, 0f, obj.pixelWidth.toFloat(), obj.pixelHeight.toFloat()),
          Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
      }
    } finally {
      bitmap.recycle()
    }
  }

  private fun exifMatrix(orientation: Int, width: Float, height: Float): Matrix = Matrix().apply {
    setValues(
      when (orientation) {
        2 -> floatArrayOf(-1f, 0f, width, 0f, 1f, 0f, 0f, 0f, 1f)
        3 -> floatArrayOf(-1f, 0f, width, 0f, -1f, height, 0f, 0f, 1f)
        4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, height, 0f, 0f, 1f)
        5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        6 -> floatArrayOf(0f, -1f, height, 1f, 0f, 0f, 0f, 0f, 1f)
        7 -> floatArrayOf(0f, -1f, height, -1f, 0f, width, 0f, 0f, 1f)
        8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, width, 0f, 0f, 1f)
        else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
      },
    )
  }

  private fun Transform2D.toMatrix() = Matrix().apply {
    setValues(floatArrayOf(a, c, tx, b, d, ty, 0f, 0f, 1f))
  }

  companion object {
    private const val PDF_POINTS_PER_LOGICAL_PIXEL = 72f / 96f
  }
}
