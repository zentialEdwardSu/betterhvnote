package com.betterhv.note.template

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import com.betterhv.note.pdf.MuPdfEngine

/** Bounded decoded background cache shared by screen, thumbnails and export. */
class TemplateRenderer {
  private val muPdf = MuPdfEngine()
  private val cache = TemplateBitmapCache<Bitmap>(
    maxSizeKb = MAX_CACHE_KB,
    sizeKbOf = { it.allocationByteCount / 1024 },
    isValid = { !it.isRecycled },
    onRecycle = { it.recycle() },
  )

  @Synchronized
  fun renderOwned(definition: TemplateDefinition, width: Int, height: Int): Bitmap? {
    val entry = acquireTemplate(definition, width, height) ?: return null
    return if (entry.ephemeral) {
      entry.value
    } else {
      entry.value.copy(Bitmap.Config.ARGB_8888, false)
    }
  }

  @Synchronized
  private fun acquireTemplate(
    definition: TemplateDefinition,
    width: Int,
    height: Int,
  ): TemplateBitmapCache.Entry<Bitmap>? {
    val file = definition.assetFile ?: return null
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val key = "${definition.fingerprint}:$w:$h"
    return cache.acquire(key) {
      val decoded = if (file.extension.equals("svg", true)) {
        runCatching { muPdf.renderFirstPage(file, w) }.getOrNull()
      } else {
        decodePng(file.absolutePath, w, h)
      } ?: return@acquire null
      try {
        val fitted = createBitmap(w, h)
        Canvas(fitted).apply {
          drawColor(Color.WHITE)
          drawBitmap(decoded, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), FILTER_PAINT)
        }
        fitted
      } finally {
        if (!decoded.isRecycled) decoded.recycle()
      }
    }
  }

  @Synchronized
  fun draw(canvas: Canvas, definition: TemplateDefinition, target: RectF) {
    val entry = acquireTemplate(definition, target.width().toInt(), target.height().toInt()) ?: return
    try {
      canvas.drawBitmap(entry.value, null, target, FILTER_PAINT)
    } finally {
      cache.release(entry)
    }
  }

  @Synchronized
  fun clear() = cache.clear()

  private fun decodePng(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidth &&
      bounds.outHeight / (sample * 2) >= targetHeight
    ) {
      sample *= 2
    }
    return BitmapFactory.decodeFile(
      path,
      BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
    )
  }

  companion object {
    private const val MAX_CACHE_KB = 24 * 1024
    private val FILTER_PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  }
}
