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
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.PageObject
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.TextObject
import com.betterhv.note.ink.InkRenderer
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

data class ThumbnailKey(val pageId: UUID, val contentRevision: Long)

/** Thread-safe version gate, separated from Android rendering for JVM regression tests. */
class ThumbnailRevisionGate {
    private val expected = HashMap<UUID, Long>()

    @Synchronized
    fun expect(key: ThumbnailKey): Boolean {
        val existing = expected[key.pageId]
        if (existing != null && existing > key.contentRevision) return false
        expected[key.pageId] = key.contentRevision
        return true
    }

    @Synchronized fun isCurrent(key: ThumbnailKey): Boolean =
        expected[key.pageId] == key.contentRevision

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

    @Synchronized
    fun get(pageId: UUID, contentRevision: Long): Bitmap? =
        memory[ThumbnailKey(pageId, contentRevision)]

    /** Invalidates prior memory/disk generations immediately after a document mutation. */
    fun invalidate(pageId: UUID, contentRevision: Long) {
        if (!revisionGate.expect(ThumbnailKey(pageId, contentRevision))) return
        synchronized(this) {
            memory.keys.filter { it.pageId == pageId && it.contentRevision != contentRevision }
                .forEach(memory::remove)
        }
        executor.execute { deleteOtherRevisions(pageId, contentRevision) }
    }

    fun request(snapshot: PageSnapshot, onReady: (ThumbnailKey) -> Unit = {}) {
        val key = ThumbnailKey(snapshot.metadata.id, snapshot.metadata.contentRevision)
        request(key, { snapshot }, onReady)
    }

    /** Provider runs on the thumbnail worker, so an unloaded Page can be read without blocking UI. */
    fun request(
        key: ThumbnailKey,
        snapshotProvider: () -> PageSnapshot?,
        onReady: (ThumbnailKey) -> Unit = {}
    ) {
        if (!revisionGate.expect(key)) return
        executor.execute {
            if (!isCurrent(key)) return@execute
            val file = fileFor(key)
            val cached = if (file.isFile) android.graphics.BitmapFactory.decodeFile(file.absolutePath) else null
            val bitmap = cached ?: run {
                val snapshot = snapshotProvider() ?: return@execute
                if (snapshot.metadata.contentRevision != key.contentRevision || !isCurrent(key)) return@execute
                render(snapshot)
            }
            if (!isCurrent(key)) return@execute
            if (cached == null) {
                val temporary = File(directory, cacheName(key, "tmp"))
                FileOutputStream(temporary).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                if (!isCurrent(key)) {
                    temporary.delete()
                    return@execute
                }
                if (!temporary.renameTo(file)) {
                    file.delete()
                    temporary.renameTo(file)
                }
            }
            synchronized(this) {
                if (!revisionGate.isCurrent(key)) return@synchronized
                memory[key] = bitmap
            }
            if (!isCurrent(key)) return@execute
            deleteOtherRevisions(key.pageId, key.contentRevision)
            mainHandler.post { if (isCurrent(key)) onReady(key) }
        }
    }

    fun delete(pageId: UUID) {
        synchronized(this) {
            revisionGate.remove(pageId)
            memory.keys.filter { it.pageId == pageId }.forEach(memory::remove)
        }
        executor.execute {
            directory.listFiles { file -> file.name.startsWith("$pageId-") }
                ?.forEach(File::delete)
        }
    }

    private fun isCurrent(key: ThumbnailKey): Boolean = revisionGate.isCurrent(key)

    private fun fileFor(key: ThumbnailKey) = File(directory, cacheName(key, "png"))

    private fun cacheName(key: ThumbnailKey, extension: String) =
        "${key.pageId}-${key.contentRevision}-r$RENDER_VERSION.$extension"

    private fun deleteOtherRevisions(pageId: UUID, keepRevision: Long) {
        val keepName = cacheName(ThumbnailKey(pageId, keepRevision), "png")
        directory.listFiles { file ->
            file.name.startsWith("$pageId-") && file.name != keepName
        }?.forEach(File::delete)
    }

    private fun render(snapshot: PageSnapshot): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        // Render ink onto transparency first. At a 1:10 preview scale a normal
        // 1-2 px page stroke becomes sub-pixel and is barely visible on an EPD
        // panel. Compositing neighboring one-pixel copies gives every stroke a
        // strong minimum footprint without changing the stored vector style.
        val inkBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val inkCanvas = Canvas(inkBitmap)
        val contentWidth = snapshot.metadata.width.takeIf { it > 0f }
            ?: snapshot.objects.maxOfOrNull { it.pageBounds.right }?.coerceAtLeast(1f) ?: 1f
        val contentHeight = snapshot.metadata.height.takeIf { it > 0f }
            ?: snapshot.objects.maxOfOrNull { it.pageBounds.bottom }?.coerceAtLeast(1f) ?: 1f
        val scale = minOf((WIDTH - 2f * PADDING) / contentWidth, (HEIGHT - 2f * PADDING) / contentHeight)
        inkCanvas.translate(PADDING, PADDING)
        inkCanvas.scale(scale, scale)
        val renderer = InkRenderer()
        val matrix = Matrix()
        snapshot.objects.forEach { obj: PageObject ->
            val t = obj.transform
            matrix.setValues(floatArrayOf(t.a, t.c, t.tx, t.b, t.d, t.ty, 0f, 0f, 1f))
            val save = inkCanvas.save()
            inkCanvas.concat(matrix)
            when (obj) {
                is StrokeObject -> {
                    val sourceStyle = obj.stroke.style
                    val previewStyle = sourceStyle.copy(
                        baseWidth = maxOf(sourceStyle.baseWidth, MIN_BASE_WIDTH_PX / scale),
                        pressureCurve = sourceStyle.pressureCurve.copy(
                            a = maxOf(sourceStyle.pressureCurve.a, MIN_PRESSURE_FLOOR)
                        )
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
                                    ) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                                }
                            )
                        }
                    if (image != null) {
                        val orientation = Matrix().apply {
                            val w = obj.pixelWidth.toFloat(); val h = obj.pixelHeight.toFloat()
                            setValues(when (obj.exifOrientation) {
                                2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
                                3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
                                4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
                                5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                                6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
                                7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
                                8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
                                else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                            })
                        }
                        val imageSave = inkCanvas.save()
                        inkCanvas.concat(orientation)
                        inkCanvas.drawBitmap(
                            image, null,
                            RectF(0f, 0f, obj.pixelWidth.toFloat(), obj.pixelHeight.toFloat()),
                            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        )
                        inkCanvas.restoreToCount(imageSave)
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
            inkCanvas.restoreToCount(save)
        }
        THICKEN_OFFSETS.forEach { (dx, dy) ->
            canvas.drawBitmap(inkBitmap, dx, dy, null)
        }
        return bitmap
    }

    override fun close() {
        executor.shutdown()
    }

    companion object {
        const val WIDTH = 120
        const val HEIGHT = 160
        private const val PADDING = 4f
        private const val MEMORY_LIMIT = 24
        private const val RENDER_VERSION = 6
        private const val IMAGE_DECODE_DIMENSION = 512
        private const val MIN_BASE_WIDTH_PX = 0.5f
        private const val MIN_PRESSURE_FLOOR = 0.65f
        private val THICKEN_OFFSETS = arrayOf(
            -0.25f to -0.25f, 0f to -0.25f, 0.25f to -0.25f,
            -0.25f to 0f, 0f to 0f, 0.25f to 0f,
            -0.25f to 0.25f, 0f to 0.25f, 0.25f to 0.25f
        )
    }
}
