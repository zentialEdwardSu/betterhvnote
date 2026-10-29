package com.betterhv.note.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Writes one rasterized page as a single-image PDF page.
 *
 * Fast mode replaces per-stroke Ink annotations with one image object per page,
 * so the PDF writer never has to inspect Ink dictionaries or appearance
 * streams; the page size still matches the vector output's 72 dpi geometry.
 */
object PdfPageImageWriter {
  fun write(bitmap: Bitmap, widthPoints: Int, heightPoints: Int, output: File) {
    require(widthPoints > 0 && heightPoints > 0) { "Invalid PDF page size" }
    require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "Invalid page bitmap" }
    output.parentFile?.mkdirs()
    val document = PdfDocument()
    try {
      val page = document.startPage(
        PdfDocument.PageInfo.Builder(widthPoints, heightPoints, 1).create(),
      )
      try {
        page.canvas.drawColor(Color.WHITE)
        page.canvas.drawBitmap(
          bitmap,
          null,
          RectF(0f, 0f, widthPoints.toFloat(), heightPoints.toFloat()),
          Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
      } finally {
        document.finishPage(page)
      }
      FileOutputStream(output).use { stream ->
        document.writeTo(stream)
        stream.fd.sync()
      }
    } catch (error: Throwable) {
      output.delete()
      throw error
    } finally {
      document.close()
    }
  }
}
