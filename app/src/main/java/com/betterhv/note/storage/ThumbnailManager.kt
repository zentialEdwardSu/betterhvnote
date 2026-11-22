package com.betterhv.note.storage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withMatrix
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.PageObject
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.TextObject
import com.betterhv.note.ink.InkRenderer
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

data class ThumbnailKey(val pageId: UUID, val contentRevision: Long, val backgroundRevision: String = "")

internal fun staleThumbnailKeys(keys: Iterable<ThumbnailKey>, keep: ThumbnailKey): List<ThumbnailKey> =
  keys.filter { it.pageId == keep.pageId && it != keep }

/** Thread-safe version gate, separated from Android rendering for JVM regression tests. */
class ThumbnailRevisionGate {
  private val expected = HashMap<UUID, ThumbnailKey>()

  @Synchronized
  fun expect(key: ThumbnailKey): Boolean {
    val existing = expected[key.pageId]
    if (existing != null && existing.contentRevision > key.contentRevision) return false
    expected[key.pageId] = key
    return true
  }

  @Synchronized fun isCurrent(key: ThumbnailKey): Boolean = expected[key.pageId] == key

  @Synchronized fun remove(pageId: UUID) {
    expected.remove(pageId)
  }
}

/** Revision-keyed regenerable thumbnail cache. Stale background work is never published. */
class ThumbnailManager(cacheDir: File, private val documentsDir: File = cacheDir) : AutoCloseable {
  private val directory = File(cacheDir, "page-thumbnails").also { it.mkdirs() }
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "inknote-thumbnails").apply { isDaemon = true }
  }
  private val mainHandler = Handler(Looper.getMainLooper())
  private val memory = object : LinkedHashMap<ThumbnailKey, Bitmap>(12, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ThumbnailKey, Bitmap>?): Boolean =
      size > MEMORY_LIMIT
  }
  private val revisionGate = ThumbnailRevisionGate()

  @Volatile private var closed = false

  @Synchronized
  fun get(pageId: UUID, contentRevision: Long, backgroundRevision: String = ""): Bitmap? =
    memory[ThumbnailKey(pageId, contentRevision, backgroundRevision)]

  /** Invalidates prior memory/disk generations immediately after a document mutation. */
  fun invalidate(pageId: UUID, contentRevision: Long, backgroundRevision: String = "") {
    val key = ThumbnailKey(pageId, contentRevision, backgroundRevision)
    if (!revisionGate.expect(key)) return
    synchronized(this) {
      staleThumbnailKeys(memory.keys, key).forEach(memory::remove)
    }
    submit { deleteOtherRevisions(key) }
  }

  fun request(
    snapshot: PageSnapshot,
    backgroundRevision: String = "",
    backgroundProvider: (() -> Bitmap?)? = null,
    onReady: (ThumbnailKey) -> Unit = {},
  ) {
    val key = ThumbnailKey(snapshot.metadata.id, snapshot.metadata.contentRevision, backgroundRevision)
    request(key, { snapshot }, backgroundProvider, onReady)
  }

  /** Provider runs on the thumbnail worker, so an unloaded Page can be read without blocking UI. */
  fun request(
    key: ThumbnailKey,
    snapshotProvider: () -> PageSnapshot?,
    backgroundProvider: (() -> Bitmap?)? = null,
    onReady: (ThumbnailKey) -> Unit = {},
  ) {
    if (!revisionGate.expect(key)) return
    submit {
      if (!isCurrent(key)) return@submit
      val file = fileFor(key)
      val cached = if (file.isFile) android.graphics.BitmapFactory.decodeFile(file.absolutePath) else null
      var bitmap: Bitmap? = cached
      var published = false
      try {
        if (bitmap == null) {
          val snapshot = snapshotProvider() ?: return@submit
          if (snapshot.metadata.contentRevision != key.contentRevision || !isCurrent(key)) return@submit
          val background = backgroundProvider?.invoke()
          bitmap = try {
            render(snapshot, background)
          } finally {
            background?.recycle()
          }
        }
        if (!isCurrent(key)) return@submit
        if (cached == null) {
          val temporary = File(directory, cacheName(key, "tmp"))
          FileOutputStream(temporary).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
          if (!isCurrent(key)) {
            temporary.delete()
            return@submit
          }
          if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) {
              temporary.delete()
            }
          }
        }
        synchronized(this) {
          if (!revisionGate.isCurrent(key)) return@synchronized
          memory[key] = bitmap
          published = true
        }
        if (!published || !isCurrent(key)) return@submit
        deleteOtherRevisions(key)
        mainHandler.post { if (isCurrent(key)) onReady(key) }
      } finally {
        if (!published) {
          bitmap?.recycle()
        }
      }
    }
  }

  fun delete(pageId: UUID) {
    synchronized(this) {
      revisionGate.remove(pageId)
      memory.keys.filter { it.pageId == pageId }.forEach(memory::remove)
    }
    submit {
      directory.listFiles { file -> file.name.startsWith("$pageId-") }
        ?.forEach(File::delete)
    }
  }

  private fun submit(task: () -> Unit) {
    if (closed || executor.isShutdown) return
    try {
      executor.execute {
        if (closed) return@execute
        task()
      }
    } catch (_: RejectedExecutionException) {
      // Closed concurrently with submission.
    }
  }

  private fun isCurrent(key: ThumbnailKey): Boolean = revisionGate.isCurrent(key)

  private fun fileFor(key: ThumbnailKey) = File(directory, cacheName(key, "png"))

  private fun cacheName(key: ThumbnailKey, extension: String) =
    "${key.pageId}-${key.contentRevision}-b${revisionHash(key.backgroundRevision)}-r$RENDER_VERSION.$extension"

  private fun revisionHash(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
      .take(6).joinToString("") { "%02x".format(it) }

  private fun deleteOtherRevisions(key: ThumbnailKey) {
    val pageId = key.pageId
    val keepName = cacheName(key, "png")
    directory.listFiles { file ->
      file.name.startsWith("$pageId-") && file.name != keepName
    }?.forEach(File::delete)
  }

  private fun render(snapshot: PageSnapshot, background: Bitmap?): Bitmap {
    val bitmap = createBitmap(WIDTH, HEIGHT)
    var inkBitmap: Bitmap? = null
    try {
      val canvas = Canvas(bitmap)
      canvas.drawColor(Color.WHITE)
      // Render ink onto transparency first. At a 1:10 preview scale a normal
      // 1-2 px page stroke becomes sub-pixel and is barely visible on an EPD
      // panel. Compositing neighboring one-pixel copies gives every stroke a
      // strong minimum footprint without changing the stored vector style.
      val localInkBitmap = createBitmap(WIDTH, HEIGHT).also { inkBitmap = it }
      val inkCanvas = Canvas(localInkBitmap)
      val contentWidth = snapshot.metadata.width.takeIf { it > 0f }
        ?: snapshot.objects.maxOfOrNull { it.pageBounds.right }?.coerceAtLeast(1f) ?: 1f
      val contentHeight = snapshot.metadata.height.takeIf { it > 0f }
        ?: snapshot.objects.maxOfOrNull { it.pageBounds.bottom }?.coerceAtLeast(1f) ?: 1f
      val scale = minOf((WIDTH - 2f * PADDING) / contentWidth, (HEIGHT - 2f * PADDING) / contentHeight)
      background?.let {
        canvas.drawBitmap(
          it,
          null,
          RectF(PADDING, PADDING, PADDING + contentWidth * scale, PADDING + contentHeight * scale),
          Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
      }
      inkCanvas.translate(PADDING, PADDING)
      inkCanvas.scale(scale, scale)
      val renderer = InkRenderer()
      val matrix = Matrix()
      snapshot.objects.forEach { obj: PageObject ->
        val t = obj.transform
        matrix.setValues(floatArrayOf(t.a, t.c, t.tx, t.b, t.d, t.ty, 0f, 0f, 1f))
        inkCanvas.withMatrix(matrix) {
          when (obj) {
            is StrokeObject -> {
              val sourceStyle = obj.stroke.style
              val previewStyle = sourceStyle.copy(
                baseWidth = maxOf(
                  sourceStyle.baseWidth,
                  MIN_BASE_WIDTH_PX / scale,
                ),
              )
              renderer.drawStroke(inkCanvas, obj.stroke.copy(style = previewStyle))
            }

            is ImageObject -> {
              val file = File(documentsDir, obj.assetPath).canonicalFile
              val root = File(documentsDir, "assets").canonicalFile.toPath()
              val image = file.takeIf { it.toPath().startsWith(root) && it.isFile }
                ?.let {
                  val maxDimension = maxOf(obj.pixelWidth, obj.pixelHeight)
                  var sample = 1
                  while (maxDimension / sample > IMAGE_DECODE_DIMENSION) sample *= 2
                  android.graphics.BitmapFactory.decodeFile(
                    it.absolutePath,
                    android.graphics.BitmapFactory.Options().apply {
                      inSampleSize = sample
                      inPreferredConfig = if (
                        obj.mimeType == "image/jpeg" || obj.mimeType == "image/jpg"
                      ) {
                        Bitmap.Config.RGB_565
                      } else {
                        Bitmap.Config.ARGB_8888
                      }
                    },
                  )
                }
              if (image != null) {
                try {
                  val orientation = Matrix().apply {
                    val w = obj.pixelWidth.toFloat()
                    val h = obj.pixelHeight.toFloat()
                    setValues(
                      when (obj.exifOrientation) {
                        2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
                        3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
                        4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
                        5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                        6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
                        7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
                        8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
                        else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                      },
                    )
                  }
                  inkCanvas.withMatrix(orientation) {
                    inkCanvas.drawBitmap(
                      image,
                      null,
                      RectF(0f, 0f, obj.pixelWidth.toFloat(), obj.pixelHeight.toFloat()),
                      Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                    )
                  }
                } finally {
                  image.recycle()
                }
              }
            }

            is TextObject -> {
              val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = obj.fontSize
                typeface = Typeface.create(obj.fontFamily.androidName, Typeface.NORMAL)
              }
              val metrics = paint.fontMetrics
              var baseline = -metrics.ascent
              val lineHeight = metrics.descent - metrics.ascent
              obj.text.split('\n').forEach { line ->
                inkCanvas.drawText(line, 0f, baseline, paint)
                baseline += lineHeight
              }
            }
          }
        }
      }
      THICKEN_OFFSETS.forEach { (dx, dy) ->
        canvas.drawBitmap(localInkBitmap, dx, dy, null)
      }
      return bitmap
    } catch (t: Throwable) {
      bitmap.recycle()
      throw t
    } finally {
      inkBitmap?.recycle()
    }
  }

  override fun close() {
    closed = true
    executor.shutdown()
    check(executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) { "Thumbnail worker did not stop" }
    mainHandler.removeCallbacksAndMessages(null)
  }

  companion object {
    // Render near the N10Pro card's actual visual size so PDF cover text remains legible.
    const val WIDTH = 360
    const val HEIGHT = 480
    private const val PADDING = 8f
    private const val MEMORY_LIMIT = 24
    private const val RENDER_VERSION = 10
    private const val IMAGE_DECODE_DIMENSION = 1024
    private const val MIN_BASE_WIDTH_PX = 1.5f
    private val THICKEN_OFFSETS = arrayOf(
      -0.25f to -0.25f, 0f to -0.25f, 0.25f to -0.25f,
      -0.25f to 0f, 0f to 0f, 0.25f to 0f,
      -0.25f to 0.25f, 0f to 0.25f, 0.25f to 0.25f,
    )
  }
}
