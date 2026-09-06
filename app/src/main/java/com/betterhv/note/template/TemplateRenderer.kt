package com.betterhv.note.template

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.core.graphics.createBitmap
import com.betterhv.note.pdf.MuPdfEngine

/** Bounded decoded background cache shared by screen, thumbnails and export. */
class TemplateRenderer {
  private val muPdf = MuPdfEngine()
  private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
      if (oldValue !== newValue && !oldValue.isRecycled) oldValue.recycle()
    }
  }

  @Synchronized
  fun renderOwned(definition: TemplateDefinition, width: Int, height: Int): Bitmap? =
    renderShared(definition, width, height)?.copy(Bitmap.Config.ARGB_8888, false)

  @Synchronized
  private fun renderShared(definition: TemplateDefinition, width: Int, height: Int): Bitmap? {
    val file = definition.assetFile ?: return null
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val key = "${definition.fingerprint}:$w:$h"
    cache.get(key)?.takeUnless(Bitmap::isRecycled)?.let { return it }
    val decoded = if (file.extension.equals("svg", true)) {
      runCatching { muPdf.renderFirstPage(file, w) }.getOrNull()
    } else {
      decodePng(file.absolutePath, w, h)
    } ?: return null
    val fitted = createBitmap(w, h)
    Canvas(fitted).apply {
      drawColor(Color.WHITE)
      drawBitmap(decoded, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), FILTER_PAINT)
    }
    decoded.recycle()
    cache.put(key, fitted)
    return fitted
  }

  @Synchronized
  fun draw(canvas: Canvas, definition: TemplateDefinition, target: RectF) {
    val bitmap = renderShared(definition, target.width().toInt(), target.height().toInt()) ?: return
    canvas.drawBitmap(bitmap, null, target, FILTER_PAINT)
  }

  @Synchronized fun clear() = cache.evictAll()

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
    private val FILTER_PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  }
}
