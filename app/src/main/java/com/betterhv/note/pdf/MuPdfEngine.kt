package com.betterhv.note.pdf

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFPage
import com.betterhv.note.doc.PdfPageSource
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.ink.Bounds
import com.betterhv.note.storage.PdfImportedPage
import java.io.File

data class PdfInspection(val pages: List<PdfImportedPage>)

class EncryptedPdfUnsupportedException : IllegalArgumentException("暂不支持加密或受密码保护的 PDF")

/** Narrow MuPDF boundary. Every native handle is destroyed in the same call that creates it. */
class MuPdfEngine {
  fun inspect(file: File): PdfInspection = withDocument(file) { document ->
    require(document.isPDF) { "所选文件不是 PDF" }
    if (document.needsPassword()) throw EncryptedPdfUnsupportedException()
    val count = document.countPages()
    require(count > 0) { "PDF 没有可读取的页面" }
    PdfInspection(
      List(count) { index ->
        val page = document.loadPage(index) as? PDFPage ?: error("无法读取 PDF 第 ${index + 1} 页")
        try {
          val bounds = page.bounds
          require(bounds.isValid && !bounds.isEmpty) { "PDF 第 ${index + 1} 页尺寸无效" }
          val width = (bounds.x1 - bounds.x0) * LOGICAL_UNITS_PER_POINT
          val height = (bounds.y1 - bounds.y0) * LOGICAL_UNITS_PER_POINT
          require(width > 0f && height > 0f) { "PDF 第 ${index + 1} 页尺寸无效" }
          val matrix = page.transform
          PdfImportedPage(
            width = width,
            height = height,
            source = PdfPageSource(
              index,
              Bounds(bounds.x0, bounds.y0, bounds.x1, bounds.y1),
              Transform2D(
                matrix.a * LOGICAL_UNITS_PER_POINT,
                matrix.b * LOGICAL_UNITS_PER_POINT,
                matrix.c * LOGICAL_UNITS_PER_POINT,
                matrix.d * LOGICAL_UNITS_PER_POINT,
                (matrix.e - bounds.x0) * LOGICAL_UNITS_PER_POINT,
                (matrix.f - bounds.y0) * LOGICAL_UNITS_PER_POINT,
              ),
            ),
          )
        } finally {
          page.destroy()
        }
      }
    )
  }

  /** Renders source content plus original PDF annotations. */
  fun renderPage(file: File, pageIndex: Int, targetWidth: Int, maxPixels: Long = 24_000_000L): Bitmap =
    withDocument(file) { document ->
      checkReadable(document, pageIndex)
      val page = document.loadPage(pageIndex)
      try {
        val bounds = page.bounds
        var scale = targetWidth.coerceAtLeast(1) / (bounds.x1 - bounds.x0)
        val estimatedHeight = (bounds.y1 - bounds.y0) * scale
        val pixels = targetWidth.coerceAtLeast(1).toLong() * estimatedHeight.toLong().coerceAtLeast(1L)
        if (pixels > maxPixels) scale *= kotlin.math.sqrt(maxPixels.toDouble() / pixels).toFloat()
        // Pixmap.getPixels() only exposes packed Android ARGB values
        // for RGB/BGR pixmaps that include an alpha channel.
        val pixmap = page.toPixmap(Matrix.Scale(scale), ColorSpace.DeviceRGB, true, true)
        try {
          Bitmap.createBitmap(pixmap.pixels, pixmap.width, pixmap.height, Bitmap.Config.ARGB_8888)
        } finally {
          pixmap.destroy()
        }
      } finally {
        page.destroy()
      }
    }

  /** Opens any single-page format supported by MuPDF, including SVG. */
  fun renderFirstPage(file: File, targetWidth: Int, maxPixels: Long = 24_000_000L): Bitmap =
    withAnyDocument(file) { document ->
      require(document.countPages() > 0) { "模板没有可渲染页面" }
      val page = document.loadPage(0)
      try {
        val bounds = page.bounds
        require(bounds.isValid && !bounds.isEmpty) { "模板尺寸无效" }
        var scale = targetWidth.coerceAtLeast(1) / (bounds.x1 - bounds.x0)
        val estimatedHeight = (bounds.y1 - bounds.y0) * scale
        val pixels = targetWidth.coerceAtLeast(1).toLong() * estimatedHeight.toLong().coerceAtLeast(1L)
        if (pixels > maxPixels) scale *= kotlin.math.sqrt(maxPixels.toDouble() / pixels).toFloat()
        val pixmap = page.toPixmap(Matrix.Scale(scale), ColorSpace.DeviceRGB, true, true)
        try {
          Bitmap.createBitmap(pixmap.pixels, pixmap.width, pixmap.height, Bitmap.Config.ARGB_8888)
        } finally {
          pixmap.destroy()
        }
      } finally {
        page.destroy()
      }
    }

  fun renderRegion(file: File, pageIndex: Int, normalizedBounds: Bounds, targetWidth: Int = 1600): Bitmap {
    requireNormalized(normalizedBounds)
    val full = renderPage(file, pageIndex, targetWidth)
    return try {
      val left = (normalizedBounds.left * full.width).toInt().coerceIn(0, full.width - 1)
      val top = (normalizedBounds.top * full.height).toInt().coerceIn(0, full.height - 1)
      val right = (normalizedBounds.right * full.width).toInt().coerceIn(left + 1, full.width)
      val bottom = (normalizedBounds.bottom * full.height).toInt().coerceIn(top + 1, full.height)
      val cropped = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
      if (cropped === full) full.copy(Bitmap.Config.ARGB_8888, false) else cropped
    } finally {
      full.recycle()
    }
  }

  fun extractText(file: File, pageIndex: Int, normalizedBounds: Bounds): String = withDocument(file) { document ->
    requireNormalized(normalizedBounds)
    checkReadable(document, pageIndex)
    val page = document.loadPage(pageIndex)
    try {
      val pageBounds = page.bounds
      val selection = Bounds(
        pageBounds.x0 + pageBounds.width * normalizedBounds.left,
        pageBounds.y0 + pageBounds.height * normalizedBounds.top,
        pageBounds.x0 + pageBounds.width * normalizedBounds.right,
        pageBounds.y0 + pageBounds.height * normalizedBounds.bottom,
      )
      val text = page.toStructuredText()
      try {
        buildString {
          text.blocks.forEach { block ->
            block.lines.forEach { line ->
              val lineText = buildString {
                line.chars.forEach { char ->
                  val r = char.quad.toRect()
                  if (intersects(selection, Bounds(r.x0, r.y0, r.x1, r.y1))) {
                    appendCodePoint(char.c)
                  }
                }
              }.trimEnd()
              if (lineText.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(lineText)
              }
            }
          }
        }.trim()
      } finally {
        text.destroy()
      }
    } finally {
      page.destroy()
    }
  }

  private fun checkReadable(document: Document, pageIndex: Int) {
    require(document.isPDF) { "资源不是 PDF" }
    if (document.needsPassword()) throw EncryptedPdfUnsupportedException()
    require(pageIndex in 0 until document.countPages()) { "PDF 页码越界" }
  }

  private fun <T> withDocument(file: File, block: (Document) -> T): T {
    require(file.isFile) { "PDF 资源不存在" }
    val document = Document.openDocument(file.absolutePath)
    return try {
      block(document)
    } finally {
      document.destroy()
    }
  }

  private fun <T> withAnyDocument(file: File, block: (Document) -> T): T {
    require(file.isFile) { "模板资源不存在" }
    val document = Document.openDocument(file.absolutePath)
    return try {
      block(document)
    } finally {
      document.destroy()
    }
  }

  private fun requireNormalized(bounds: Bounds) {
    require(
      bounds.left in 0f..1f && bounds.top in 0f..1f &&
        bounds.right in 0f..1f && bounds.bottom in 0f..1f &&
        bounds.right > bounds.left && bounds.bottom > bounds.top
    ) { "区域必须位于 PDF 页面内" }
  }

  private fun intersects(a: Bounds, b: Bounds): Boolean =
    a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

  private val com.artifex.mupdf.fitz.Rect.width get() = x1 - x0
  private val com.artifex.mupdf.fitz.Rect.height get() = y1 - y0

  companion object {
    const val LOGICAL_UNITS_PER_POINT = 96f / 72f
  }
}
