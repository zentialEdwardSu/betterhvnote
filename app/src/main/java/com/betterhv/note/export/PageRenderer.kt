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
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.PageObject
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.TextObject
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.ink.InkRenderer
import com.betterhv.note.storage.PageSnapshot
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** One Canvas rendering path is shared by screen-independent PNG and PDF output. */
class PageRenderer(private val documentsDir: File) {
    private val inkRenderer = InkRenderer()
    private val imageRoot = File(documentsDir, "assets").canonicalFile.toPath()

    fun renderToBitmap(
        snapshot: PageSnapshot,
        widthPx: Int,
        heightPx: Int,
        backgroundColor: Int = Color.WHITE
    ): Bitmap {
        require(widthPx > 0 && heightPx > 0) { "导出尺寸无效" }
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(backgroundColor)
            save()
            scale(widthPx / snapshot.metadata.width, heightPx / snapshot.metadata.height)
            renderSnapshot(this, snapshot)
            restore()
        }
        return bitmap
    }

    fun renderSinglePagePdf(snapshot: PageSnapshot, output: File) {
        output.parentFile?.mkdirs()
        val widthPoints = (snapshot.metadata.width * PDF_POINTS_PER_LOGICAL_PIXEL).roundToInt().coerceAtLeast(1)
        val heightPoints = (snapshot.metadata.height * PDF_POINTS_PER_LOGICAL_PIXEL).roundToInt().coerceAtLeast(1)
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(widthPoints, heightPoints, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.save()
            page.canvas.scale(
                widthPoints / snapshot.metadata.width,
                heightPoints / snapshot.metadata.height
            )
            renderSnapshot(page.canvas, snapshot)
            page.canvas.restore()
            document.finishPage(page)
            FileOutputStream(output).use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun renderSnapshot(canvas: Canvas, snapshot: PageSnapshot) {
        snapshot.objects.sortedBy(PageObject::zIndex).forEach { obj ->
            val save = canvas.save()
            canvas.concat(obj.transform.toMatrix())
            when (obj) {
                is StrokeObject -> inkRenderer.drawStroke(canvas, obj.stroke)
                is TextObject -> drawText(canvas, obj)
                is ImageObject -> drawImage(canvas, obj)
            }
            canvas.restoreToCount(save)
        }
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

    private fun drawImage(canvas: Canvas, obj: ImageObject) {
        val file = runCatching { File(documentsDir, obj.assetPath).canonicalFile }.getOrNull()
            ?.takeIf { it.toPath().startsWith(imageRoot) && it.isFile } ?: return
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        try {
            val save = canvas.save()
            canvas.concat(exifMatrix(obj.exifOrientation, bitmap.width.toFloat(), bitmap.height.toFloat()))
            canvas.drawBitmap(
                bitmap, null, RectF(0f, 0f, obj.pixelWidth.toFloat(), obj.pixelHeight.toFloat()),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            canvas.restoreToCount(save)
        } finally {
            bitmap.recycle()
        }
    }

    private fun exifMatrix(orientation: Int, width: Float, height: Float): Matrix = Matrix().apply {
        setValues(when (orientation) {
            2 -> floatArrayOf(-1f, 0f, width, 0f, 1f, 0f, 0f, 0f, 1f)
            3 -> floatArrayOf(-1f, 0f, width, 0f, -1f, height, 0f, 0f, 1f)
            4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, height, 0f, 0f, 1f)
            5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            6 -> floatArrayOf(0f, -1f, height, 1f, 0f, 0f, 0f, 0f, 1f)
            7 -> floatArrayOf(0f, -1f, height, -1f, 0f, width, 0f, 0f, 1f)
            8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, width, 0f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        })
    }

    private fun Transform2D.toMatrix() = Matrix().apply {
        setValues(floatArrayOf(a, c, tx, b, d, ty, 0f, 0f, 1f))
    }

    companion object { private const val PDF_POINTS_PER_LOGICAL_PIXEL = 72f / 96f }
}
