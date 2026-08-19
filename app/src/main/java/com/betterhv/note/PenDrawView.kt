package com.betterhv.note

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HvPenDrawListener
import android.os.HvPenDrawManager
import android.os.Looper
import android.os.Message
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.betterhv.note.doc.CommandStack
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.Notebook
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.PageObject
import com.betterhv.note.doc.SelectionSet
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.TextFontFamily
import com.betterhv.note.doc.TextObject
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.doc.commands.AddPageCommand
import com.betterhv.note.doc.commands.AddObjectCommand
import com.betterhv.note.doc.commands.DeletePageCommand
import com.betterhv.note.doc.commands.DeleteObjectsCommand
import com.betterhv.note.doc.commands.MovePageCommand
import com.betterhv.note.doc.commands.SetPageBookmarkCommand
import com.betterhv.note.doc.commands.TransformObjectsCommand
import com.betterhv.note.doc.commands.UpdateObjectCommand
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.InkRenderer
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.tool.EraserTool
import com.betterhv.note.tool.PenTool
import com.betterhv.note.tool.PointEraserTool
import com.betterhv.note.tool.SelectionTool
import com.betterhv.note.tool.StrokeEraserTool
import com.betterhv.note.tool.Tool
import com.betterhv.note.tool.ToolHost
import com.betterhv.note.storage.AutosaveController
import com.betterhv.note.storage.DocumentChange
import com.betterhv.note.storage.ImageAssetStore
import com.betterhv.note.storage.ImportedImage
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.storage.NotebookSummary
import com.betterhv.note.storage.StartupBehavior
import com.betterhv.note.storage.PageCache
import com.betterhv.note.storage.PageSnapshot
import com.betterhv.note.storage.ThumbnailManager
import com.betterhv.note.storage.ThumbnailKey
import com.betterhv.note.storage.TransferReceipt
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Which tip-driven editing tool the toolbar currently has selected. Erasing is
 * NOT a tip tool: it is done only by the hardware tail eraser (see
 * [EraserOverlayView]) -- the ROM drives that reliably, whereas a tip-driven
 * eraser's moving cursor overlay lagged badly on the e-ink panel. The tail
 * eraser's behavior is chosen by [EraserMode], toggled independently of the tip
 * tool.
 */
enum class ToolKind { PEN, LASSO }

/**
 * How the hardware tail eraser removes ink: [WHOLE_STROKE] deletes any stroke
 * the disc touches; [POINT] splits strokes at the erased points (spec §33-35).
 * Toggled from the toolbar; applies to the tail eraser regardless of which tip
 * [ToolKind] is active.
 */
enum class EraserMode { WHOLE_STROKE, POINT }

data class PageUiInfo(
    val id: UUID,
    val pageNumber: Int,
    val bookmarked: Boolean,
    val contentRevision: Long
)

/**
 * Writing surface. The ROM's hvpen service paints live ink onto the hardware
 * overlay for latency, and delivers the sampled points back to us; we turn
 * those into vector [com.betterhv.note.ink.Stroke]s and rasterize them into
 * our own bitmap.
 *
 * Document Core change (spec §7-10, §31-56): the flat stroke list is now a
 * [Page] (Scene + SpatialIndex) inside a multi-page [Notebook], and every
 * mutation goes through a [Tool] + [CommandStack] so drawing, erasing,
 * splitting and transforming are all undoable. The bitmap remains a
 * rebuildable cache of [page]'s objects, same invariant as Phase 1 (spec §2.1).
 * Completed commands are persisted asynchronously as atomic SQLite/WAL
 * transactions; page turns keep only the current/adjacent Scene window loaded.
 *
 * The ROM fires onPenTouchUpStatus() several times across one physical pen
 * gesture with no correlation between calls, so this view brackets normal
 * writing using ACTION_DOWN plus those batches. Lasso gestures are instead
 * driven by [LassoOverlayView], mirroring hvNote's MemoMarkView split.
 */
class PenDrawView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), HvPenDrawListener, ToolHost {

    private var penDraw: HvPenDrawManager? = null
    private var penDrawPt: Long = 0
    private var initPenService = false
    private var romPenInkRequested = true
    private var uiInputBlocked = false

    private val eink = EinkRefreshController(context)

    /** Cache of committed strokes. Regenerable from [page] at any time. */
    private var foreBitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null

    /** Authoritative multi-page document plus asynchronous persistence (spec §46-56). */
    @Volatile private var persistenceAvailable = true
    @Volatile private var persistenceError: String? = null
    private val repository = NotebookRepository(context.applicationContext)
    private val imageAssets = ImageAssetStore(context.applicationContext)
    private var notebook: Notebook = try {
        repository.openOrCreate()
    } catch (t: Throwable) {
        persistenceAvailable = false
        persistenceError = "Notebook recovery failed; editing is in memory only"
        EventLog.log(TAG, "ERROR opening notebook: ${t.javaClass.simpleName}: ${t.message}")
        Notebook().also { it.addPage(Page()) }
    }
    private var page: Page = notebook.pageAt(0) ?: Page().also { notebook.addPage(it) }
    private val pageCache = PageCache(PAGE_CACHE_SIZE)
    private val notebookOperations = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "inknote-notebooks").apply { isDaemon = true }
    }
    private val thumbnails = ThumbnailManager(
        context.cacheDir, java.io.File(context.filesDir, "documents")
    )
    private val autosave = AutosaveController(repository) { t ->
        persistenceAvailable = false
        persistenceError = "Autosave failed: ${t.message ?: t.javaClass.simpleName}"
        EventLog.log(TAG, "ERROR autosave: ${t.javaClass.simpleName}: ${t.message}")
        post {
            emitNotice(persistenceError ?: "Autosave failed")
            onDocChanged?.invoke()
        }
    }
    private var pendingTransferReceipt: TransferReceipt? = null
    private var pendingTransferPersisted: Boolean? = null
    private val commandStack = CommandStack { command, action ->
        val affectedPageIds = command.affectedObjects.keys + command.affectedPages
        affectedPageIds.forEach { id ->
            command.currentPage(id)?.let(notebook::refreshPageMetadata)
        }
        command.affectedObjects.keys.forEach { id ->
            command.currentPage(id)?.let { changed ->
                thumbnails.invalidate(id, changed.contentRevision)
            }
        }
        val change = DocumentChange.forCommand(notebook, command, action)
        val receipt = pendingTransferReceipt.also { pendingTransferReceipt = null }
        if (receipt != null) {
            pendingTransferPersisted = persistenceAvailable && autosave.persistWithReceipt(change, receipt)
        } else {
            scheduleSave(change)
        }
    }

    private val renderer = InkRenderer()
    private val imageCache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
    private var penStyle = PenStyle(baseWidth = DEFAULT_PEN_WIDTH)

    private val eraserWidth = DEFAULT_ERASER_WIDTH

    // -- Tools (spec §17, §33-40) ------------------------------------------

    private var penTool = PenTool(page, commandStack, this) { penStyle }
    private var strokeEraserTool = StrokeEraserTool(page, commandStack, this) { eraserWidth / 2.0f }
    private var pointEraserTool = PointEraserTool(page, commandStack, this) { eraserWidth / 2.0f }
    private var selectionTool = SelectionTool(page, commandStack, this) { HANDLE_TOUCH_RADIUS }

    private var toolKind: ToolKind = ToolKind.PEN
    private var currentTool: Tool = penTool

    private enum class RichGestureMode { NONE, MOVE, SCALE, ROTATE }
    private var selectedRichObjectId: UUID? = null
    private var richGestureMode = RichGestureMode.NONE
    private var richGestureStartX = 0f
    private var richGestureStartY = 0f
    private var richGestureBefore = Transform2D.IDENTITY
    private var richGestureAnchorX = 0f
    private var richGestureAnchorY = 0f
    private var richGestureStartValue = 0f
    private var richPendingCommand: TransformObjectsCommand? = null

    /** Tail-eraser behavior, toggled from the toolbar (independent of [toolKind]). */
    private var eraserMode: EraserMode = EraserMode.WHOLE_STROKE

    private var onDocChanged: (() -> Unit)? = null
    private var onNotice: ((String) -> Unit)? = null
    private var onTextEditRequested: ((TextObject) -> Unit)? = null

    /** Set by the toolbar to observe undo/redo availability and selection state. */
    fun setOnDocChanged(listener: (() -> Unit)?) {
        onDocChanged = listener
    }

    fun setOnNotice(listener: ((String) -> Unit)?) {
        onNotice = listener
        persistenceError?.let(::emitNotice)
    }

    fun setOnTextEditRequested(listener: ((TextObject) -> Unit)?) {
        onTextEditRequested = listener
    }

    private fun emitNotice(message: String) {
        onNotice?.invoke(message)
    }

    fun importImage(uri: Uri): ImportedImage = imageAssets.import(uri)

    fun stageRemoteImage(file: File, mimeType: String): ImportedImage = imageAssets.stageFile(file, mimeType)

    fun discardImportedImage(image: ImportedImage) {
        imageAssets.discard(image)
    }

    fun placeImage(image: ImportedImage, centerX: Float, centerY: Float): ImageObject =
        placeImageWithId(image, centerX, centerY, UUID.randomUUID())

    private fun placeImageWithId(
        image: ImportedImage, centerX: Float, centerY: Float, objectId: UUID
    ): ImageObject {
        materialize()
        val committed = imageAssets.commit(image)
        val pageWidth = page.width.takeIf { it > 0f } ?: width.toFloat()
        val pageHeight = page.height.takeIf { it > 0f } ?: height.toFloat()
        val displayWidth = if (committed.exifOrientation in 5..8) committed.pixelHeight else committed.pixelWidth
        val displayHeight = if (committed.exifOrientation in 5..8) committed.pixelWidth else committed.pixelHeight
        val scale = minOf(
            1f,
            pageWidth * 0.4f / displayWidth.coerceAtLeast(1),
            pageHeight * 0.4f / displayHeight.coerceAtLeast(1)
        )
        val now = System.currentTimeMillis()
        val obj = ImageObject(
            id = objectId,
            transform = Transform2D(
                scale, 0f, 0f, scale,
                centerX - displayWidth * scale / 2f,
                centerY - displayHeight * scale / 2f
            ),
            zIndex = nextZIndex(), createdAt = now, updatedAt = now,
            assetPath = committed.relativePath, mimeType = committed.mimeType,
            pixelWidth = committed.pixelWidth, pixelHeight = committed.pixelHeight,
            exifOrientation = committed.exifOrientation
        )
        commandStack.execute(AddObjectCommand(page, obj))
        selectedRichObjectId = obj.id
        redrawAll()
        onDocChanged?.invoke()
        return obj
    }

    fun placeTransferredImage(
        image: ImportedImage,
        centerX: Float,
        centerY: Float,
        sourceDeviceId: String,
        itemId: UUID
    ): Result<ImageObject> = runCatching {
        repository.findTransferReceipt(sourceDeviceId, itemId)?.let { existing ->
            return@runCatching (page.getObject(existing) as? ImageObject)
                ?: error("传输项目已经提交到其他页面")
        }
        val objectId = UUID.randomUUID()
        pendingTransferReceipt = TransferReceipt(sourceDeviceId, itemId, objectId)
        pendingTransferPersisted = null
        val placed = placeImageWithId(image, centerX, centerY, objectId)
        if (pendingTransferPersisted != true) {
            commandStack.undo()
            imageAssets.resolve(placed.assetPath)?.delete()
            error("接收图片保存失败")
        }
        pendingTransferPersisted = null
        placed
    }.onFailure {
        pendingTransferReceipt = null
        pendingTransferPersisted = null
        imageAssets.discard(image)
    }

    fun placeText(text: String, x: Float, y: Float): TextObject {
        return placeTextWithId(text, x, y, UUID.randomUUID())
    }

    fun placeTransferredText(
        text: String, x: Float, y: Float, sourceDeviceId: String, itemId: UUID
    ): Result<TextObject> = runCatching {
        repository.findTransferReceipt(sourceDeviceId, itemId)?.let { existing ->
            return@runCatching (page.getObject(existing) as? TextObject)
                ?: error("传输项目已经提交到其他页面")
        }
        val objectId = UUID.randomUUID()
        pendingTransferReceipt = TransferReceipt(sourceDeviceId, itemId, objectId)
        pendingTransferPersisted = null
        val placed = placeTextWithId(text, x, y, objectId)
        if (pendingTransferPersisted != true) {
            commandStack.undo()
            error("接收文字保存失败")
        }
        pendingTransferPersisted = null
        placed
    }.onFailure {
        pendingTransferReceipt = null
        pendingTransferPersisted = null
    }

    private fun placeTextWithId(text: String, x: Float, y: Float, id: UUID): TextObject {
        materialize()
        val now = System.currentTimeMillis()
        val family = TextFontFamily.SANS_SERIF
        val size = 24f
        val obj = TextObject(
            id = id, transform = Transform2D.translate(x, y),
            zIndex = nextZIndex(), createdAt = now, updatedAt = now,
            text = text, fontFamily = family, fontSize = size,
            localBounds = measureTextBounds(text, family, size)
        )
        commandStack.execute(AddObjectCommand(page, obj))
        selectedRichObjectId = obj.id
        redrawAll()
        onDocChanged?.invoke()
        return obj
    }

    fun updateTextObject(
        id: UUID,
        text: String? = null,
        fontFamily: TextFontFamily? = null,
        fontSize: Float? = null
    ): TextObject? {
        val before = page.getObject(id) as? TextObject ?: return null
        val updatedText = text ?: before.text
        if (updatedText.isBlank()) return before
        val updatedFamily = fontFamily ?: before.fontFamily
        val updatedSize = (fontSize ?: before.fontSize).coerceIn(8f, 96f)
        val after = before.copy(
            text = updatedText,
            fontFamily = updatedFamily,
            fontSize = updatedSize,
            localBounds = measureTextBounds(updatedText, updatedFamily, updatedSize),
            updatedAt = System.currentTimeMillis()
        )
        if (after == before) return before
        commandStack.execute(UpdateObjectCommand(page, before, after))
        redrawAll()
        onDocChanged?.invoke()
        return after
    }

    fun selectedRichObject(): PageObject? = selectedRichObjectId?.let(page::getObject)

    internal fun richObjectsForTest(): List<PageObject> = page.scene.all().filter {
        it is ImageObject || it is TextObject
    }

    fun clearRichSelection() {
        if (selectedRichObjectId == null) return
        selectedRichObjectId = null
        richGestureMode = RichGestureMode.NONE
        richPendingCommand = null
        postInvalidate()
        onDocChanged?.invoke()
    }

    /** Side1 direct-selects the topmost image/text object. */
    fun selectRichObjectAt(x: Float, y: Float): Boolean {
        materialize()
        val hit = page.scene.all().asReversed().firstOrNull { obj ->
            (obj is ImageObject || obj is TextObject) && pointInsideObject(obj, x, y)
        }
        selectedRichObjectId = hit?.id
        postInvalidate()
        onDocChanged?.invoke()
        return hit != null
    }

    fun requestSelectedTextEdit(): Boolean {
        val text = selectedRichObject() as? TextObject ?: return false
        onTextEditRequested?.invoke(text)
        return true
    }

    /** Begins a move/scale/rotate gesture after an object was selected with Side1. */
    fun beginRichObjectGesture(x: Float, y: Float): Boolean {
        val obj = selectedRichObject() ?: return false
        val corners = objectCorners(obj)
        val handle = corners.indexOfFirst { point -> distance(x, y, point[0], point[1]) <= HANDLE_TOUCH_RADIUS }
        val rotate = obj is ImageObject && distanceTo(x, y, rotationHandle(obj)) <= HANDLE_TOUCH_RADIUS * 1.35f
        richGestureMode = when {
            rotate -> RichGestureMode.ROTATE
            obj is ImageObject && handle >= 0 -> RichGestureMode.SCALE
            pointInsideObject(obj, x, y) -> RichGestureMode.MOVE
            else -> return false
        }
        richGestureStartX = x
        richGestureStartY = y
        richGestureBefore = obj.transform
        val center = objectCenter(obj)
        when (richGestureMode) {
            RichGestureMode.SCALE -> {
                val opposite = corners[handle xor 3]
                richGestureAnchorX = opposite[0]
                richGestureAnchorY = opposite[1]
                richGestureStartValue = hypot(
                    (x - richGestureAnchorX).toDouble(), (y - richGestureAnchorY).toDouble()
                ).toFloat().coerceAtLeast(1f)
            }
            RichGestureMode.ROTATE -> {
                richGestureAnchorX = center[0]
                richGestureAnchorY = center[1]
                richGestureStartValue = atan2(y - center[1], x - center[0])
            }
            else -> Unit
        }
        richPendingCommand = TransformObjectsCommand(
            page, listOf(obj.id), mapOf(obj.id to obj.transform), mapOf(obj.id to obj.transform)
        )
        setRomPenInkEnabled(false)
        return true
    }

    fun updateRichObjectGesture(x: Float, y: Float) {
        val obj = selectedRichObject() ?: return
        val delta = when (richGestureMode) {
            RichGestureMode.MOVE -> Transform2D.translate(x - richGestureStartX, y - richGestureStartY)
            RichGestureMode.SCALE -> {
                val distance = hypot(
                    (x - richGestureAnchorX).toDouble(), (y - richGestureAnchorY).toDouble()
                ).toFloat().coerceAtLeast(1f)
                Transform2D.scaleAbout(
                    richGestureAnchorX, richGestureAnchorY,
                    (distance / richGestureStartValue).coerceIn(0.1f, 10f)
                )
            }
            RichGestureMode.ROTATE -> Transform2D.rotateAbout(
                richGestureAnchorX, richGestureAnchorY,
                atan2(y - richGestureAnchorY, x - richGestureAnchorX) - richGestureStartValue
            )
            RichGestureMode.NONE -> return
        }
        val oldBounds = obj.pageBounds
        val after = delta.times(richGestureBefore)
        richPendingCommand?.updateAfter(mapOf(obj.id to after))
        richPendingCommand?.applyLive()
        val newBounds = page.getObject(obj.id)?.pageBounds ?: oldBounds
        requestRepaint(oldBounds.union(newBounds).inflate(HANDLE_TOUCH_RADIUS * 3f))
        postInvalidate()
    }

    fun endRichObjectGesture(cancelled: Boolean) {
        val obj = selectedRichObject()
        val command = richPendingCommand
        richPendingCommand = null
        if (obj != null && cancelled) page.updateObjectTransform(obj.id, richGestureBefore)
        if (obj != null && !cancelled && obj.transform != richGestureBefore && command != null) {
            commandStack.push(command)
        }
        richGestureMode = RichGestureMode.NONE
        setRomPenInkEnabled(true)
        redrawAll()
        onDocChanged?.invoke()
    }

    private fun nextZIndex(): Int = (page.scene.all().maxOfOrNull(PageObject::zIndex) ?: -1) + 1

    private fun pointInsideObject(obj: PageObject, x: Float, y: Float): Boolean {
        val inverse = obj.transform.invert() ?: return false
        val local = inverse.mapPoint(x, y)
        val b = obj.localBounds
        return local[0] in b.left..b.right && local[1] in b.top..b.bottom
    }

    private fun objectCorners(obj: PageObject): List<FloatArray> {
        val b = obj.localBounds
        return listOf(
            obj.transform.mapPoint(b.left, b.top), obj.transform.mapPoint(b.right, b.top),
            obj.transform.mapPoint(b.left, b.bottom), obj.transform.mapPoint(b.right, b.bottom)
        )
    }

    private fun objectCenter(obj: PageObject): FloatArray = obj.transform.mapPoint(
        (obj.localBounds.left + obj.localBounds.right) / 2f,
        (obj.localBounds.top + obj.localBounds.bottom) / 2f
    )

    private fun rotationHandle(obj: PageObject): FloatArray {
        val b = obj.localBounds
        val top = obj.transform.mapPoint((b.left + b.right) / 2f, b.top)
        val center = objectCenter(obj)
        val dx = top[0] - center[0]
        val dy = top[1] - center[1]
        val length = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        return floatArrayOf(top[0] + dx / length * ROTATE_HANDLE_OFFSET, top[1] + dy / length * ROTATE_HANDLE_OFFSET)
    }

    private fun distance(x: Float, y: Float, px: Float, py: Float): Float =
        hypot((x - px).toDouble(), (y - py).toDouble()).toFloat()

    private fun distanceTo(x: Float, y: Float, point: FloatArray): Float =
        distance(x, y, point[0], point[1])

    private fun measureTextBounds(text: String, family: TextFontFamily, size: Float): Bounds {
        configureTextPaint(family, size)
        val lines = text.split('\n').ifEmpty { listOf("") }
        val metrics = textPaint.fontMetrics
        val width = lines.maxOfOrNull(textPaint::measureText)?.coerceAtLeast(1f) ?: 1f
        val height = (metrics.descent - metrics.ascent) * lines.size
        return Bounds(0f, 0f, width, height.coerceAtLeast(1f))
    }

    private fun configureTextPaint(family: TextFontFamily, size: Float) {
        textPaint.typeface = Typeface.create(family.androidName, Typeface.NORMAL)
        textPaint.textSize = size
    }

    init {
        pageCache.put(page)
        if (persistenceAvailable) {
            runCatching { imageAssets.cleanupUnreferenced(repository.referencedImageAssets()) }
                .onFailure { EventLog.log(TAG, "asset cleanup skipped: ${it.message}") }
        }
    }

    private val originPos = HvPenDrawManager.getScreenOrgPos()
    private val screenW: Int
    private val screenH: Int

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == MSG_POINTS) {
                val pts = msg.data.getFloatArray("points") ?: return
                onBatch(pts, msg.data.getBoolean("up", false))
            }
        }
    }

    /**
     * Safety net for the case where the ROM delivers a trailing batch after
     * ACTION_UP, or ACTION_UP never arrives. Without this a gesture could stay
     * open forever and silently swallow the next one.
     */
    private val finishRunnable = Runnable {
        if (gestureOpen) {
            EventLog.log(TAG, "quiet-period finalize (no ACTION_UP)")
            finishGesture()
        }
    }

    private var gestureOpen = false

    // -- Transient lasso overlay state: marquee + selection box. Drawn in onDraw
    // on TOP of foreBitmap, never into it, so it vanishes on the next repaint /
    // at materialize and never becomes part of the document. Only live while the
    // lasso tool owns the screen (ROM ink suppressed in beginLasso). There is
    // deliberately NO eraser cursor overlay: the tail eraser fires a repaint per
    // sample and an on-screen disc chasing it lagged badly on the e-ink panel, so
    // the eraser gives no cursor -- the ROM's own erase feedback is enough.

    /** Dashed stroke for the lasso marquee and the selection bounding box. */
    private val marqueePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.0f
        color = 0xAA000000.toInt()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    /** Solid fill for the selection corner handles. */
    private val handlePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
        color = 0xAA000000.toInt()
    }

    private val overlayPath = android.graphics.Path()

    init {
        val dm = context.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        EventLog.log(
            TAG,
            "created screen=${screenW}x$screenH orgPos=$originPos model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}"
        )
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0) {
            val oldRevision = page.contentRevision
            page.updateSize(w.toFloat(), h.toFloat())
            if (page.contentRevision != oldRevision) {
                notebook.refreshPageMetadata(page)
                thumbnails.invalidate(page.id, page.contentRevision)
            }
            createBitmap(w, h)
            initPenDraw()
            scheduleSave(DocumentChange.fullPage(notebook, page, "page:size"))
        }
    }

    // -- Multi-page system (spec §46-48, Phase 6) -------------------------

    fun currentNotebookId(): UUID = notebook.id
    fun currentNotebookTitle(): String = notebook.title
    fun workingNotebookId(): UUID = repository.workingNotebookId()
    fun startupBehavior(): StartupBehavior = repository.startupBehavior()
    fun setStartupBehavior(behavior: StartupBehavior) = repository.setStartupBehavior(behavior)
    fun notebookSummaries(): List<NotebookSummary> = repository.listNotebooks(notebook.id)

    fun notebookCoverThumbnail(summary: NotebookSummary): Bitmap? =
        summary.cover?.let { thumbnails.get(it.id, it.contentRevision) }

    fun requestNotebookCovers(summaries: List<NotebookSummary>) {
        summaries.mapNotNull(NotebookSummary::cover).forEach { metadata ->
            val key = ThumbnailKey(metadata.id, metadata.contentRevision)
            val loaded = notebook.getPage(metadata.id)
            if (loaded != null) {
                thumbnails.request(PageSnapshot.capture(loaded)) { onDocChanged?.invoke() }
            } else {
                thumbnails.request(
                    key,
                    snapshotProvider = { repository.loadPage(metadata.id)?.let(PageSnapshot::capture) }
                ) { onDocChanged?.invoke() }
            }
        }
    }

    fun currentPageToEndIds(): Set<UUID> =
        notebook.pageOrder.drop(currentPageIndex()).toSet()

    fun switchNotebook(id: UUID, onComplete: (Result<UUID>) -> Unit) {
        if (id == notebook.id) {
            onComplete(Result.success(id))
            return
        }
        runNotebookOperation(onComplete) {
            val loaded = repository.loadNotebook(id) ?: error("找不到笔记本")
            repository.setActiveNotebook(id)
            loaded
        }
    }

    fun createBlankNotebook(title: String, onComplete: (Result<UUID>) -> Unit) {
        runNotebookOperation(onComplete) {
            val id = repository.createBlankNotebook(title, width.toFloat(), height.toFloat())
            repository.loadNotebook(id) ?: error("无法加载新笔记本")
        }
    }

    fun transferPagesToNewNotebook(
        selectedPageIds: Set<UUID>,
        title: String,
        onComplete: (Result<UUID>) -> Unit
    ) {
        val sourceNotebookId = notebook.id
        runNotebookOperation(onComplete) {
            val transfer = repository.transferPagesToNewNotebook(sourceNotebookId, selectedPageIds, title)
            repository.loadNotebook(transfer.targetNotebookId) ?: error("无法加载新笔记本")
        }
    }

    fun deleteNotebook(id: UUID, onComplete: (Result<UUID>) -> Unit) {
        runNotebookOperation(onComplete) {
            val deletion = repository.deleteNotebook(id)
            deletion.deletedPageIds.forEach(thumbnails::delete)
            repository.loadNotebook(deletion.activeNotebookId) ?: error("无法加载删除后的笔记本")
        }
    }

    private fun runNotebookOperation(
        onComplete: (Result<UUID>) -> Unit,
        operation: () -> Notebook
    ) {
        materialize()
        scheduleSave(DocumentChange.fullPage(notebook, page, "notebook:before-switch"))
        notebookOperations.execute {
            val result = runCatching {
                check(persistenceAvailable && autosave.flush()) { "保存当前笔记本失败" }
                operation()
            }
            post {
                result.onSuccess(::installNotebook)
                result.exceptionOrNull()?.let { error ->
                    emitNotice("笔记本操作失败：${error.message ?: error.javaClass.simpleName}")
                }
                onComplete(result.map(Notebook::id))
            }
        }
    }

    private fun installNotebook(target: Notebook) {
        val first = target.pageAt(0) ?: error("笔记本没有页面")
        notebook = target
        pageCache.clear()
        page = first
        pageCache.put(first)
        commandStack.clear()
        if (width > 0 && height > 0 && (page.width <= 0f || page.height <= 0f)) {
            page.updateSize(width.toFloat(), height.toFloat())
            notebook.refreshPageMetadata(page)
            thumbnails.invalidate(page.id, page.contentRevision)
            scheduleSave(DocumentChange.fullPage(notebook, page, "page:size"))
        }
        rebuildTools()
        warmAdjacentPages(0)
        redrawAll()
        clearOverlayInk()
        requestThumbnail(page)
        onDocChanged?.invoke()
        EventLog.log(TAG, "notebook opened id=${notebook.id} title=${notebook.title}")
    }

    fun pageCount(): Int = notebook.pageOrder.size
    fun currentPageIndex(): Int = notebook.pageOrder.indexOf(page.id).coerceAtLeast(0)
    fun pageIds(): List<UUID> = notebook.pageOrder.toList()
    fun pageUiItems(): List<PageUiInfo> = notebook.pageOrder.mapIndexedNotNull { index, id ->
        notebook.metadata(id)?.let { metadata ->
            PageUiInfo(id, index + 1, metadata.bookmarked, metadata.contentRevision)
        }
    }
    fun pageThumbnail(id: UUID): Bitmap? = notebook.metadata(id)?.let { metadata ->
        thumbnails.get(id, metadata.contentRevision)
    }
    fun canDeletePage(): Boolean = notebook.pageOrder.size > 1
    fun persistenceWarning(): String? = persistenceError
    fun currentPageBookmarked(): Boolean = page.bookmarked

    private fun scheduleSave(change: DocumentChange) {
        if (persistenceAvailable) autosave.schedule(change)
    }

    fun switchToPage(index: Int): Boolean {
        if (index !in notebook.pageOrder.indices) return false
        if (index == currentPageIndex()) return true
        materialize()
        requestThumbnail(page)
        activatePage(index)
        return true
    }

    fun switchToPage(id: UUID): Boolean = switchToPage(notebook.pageOrder.indexOf(id))

    fun navigatePage(delta: Int): Boolean {
        val target = currentPageIndex() + delta
        if (target !in notebook.pageOrder.indices) {
            if (delta > 0 && autoCreatePageOnNextAtEnd) {
                addPage()
                return true
            }
            return false
        }
        return switchToPage(target)
    }

    /** Updated by the Compose settings surface and read by page-turn overlays. */
    var autoCreatePageOnNextAtEnd: Boolean = false

    fun addPage(): UUID = addPageAfter(page.id, activate = true)

    fun addPageAfter(afterPageId: UUID, activate: Boolean): UUID {
        if (gestureOpen) finishGesture()
        val afterIndex = notebook.pageOrder.indexOf(afterPageId).takeIf { it >= 0 }
            ?: currentPageIndex()
        val insertAt = afterIndex + 1
        val newPage = Page(width = width.toFloat(), height = height.toFloat())
        commandStack.execute(AddPageCommand(notebook, newPage, insertAt))
        pageCache.put(newPage)
        if (activate) activatePage(insertAt) else onDocChanged?.invoke()
        return newPage.id
    }

    fun deleteCurrentPage(): Boolean = deletePage(page.id)

    fun deletePage(pageId: UUID): Boolean {
        if (!canDeletePage() || pageId !in notebook.pageOrder) return false
        val deletingCurrent = page.id == pageId
        if (deletingCurrent) materialize()
        val oldIndex = notebook.pageOrder.indexOf(pageId)
        if (notebook.getPage(pageId) == null) {
            repository.loadPage(pageId)?.let(notebook::attachPage) ?: return false
        }
        commandStack.execute(DeletePageCommand(notebook, pageId))
        thumbnails.delete(pageId)
        pageCache.retain(notebook.pageOrder.toSet())
        if (deletingCurrent) {
            activatePage(oldIndex.coerceAtMost(notebook.pageOrder.lastIndex))
        } else {
            onDocChanged?.invoke()
        }
        return true
    }

    fun moveCurrentPage(delta: Int) {
        val from = currentPageIndex()
        val target = (from + delta).coerceIn(0, notebook.pageOrder.lastIndex)
        movePage(page.id, target)
    }

    fun movePage(pageId: UUID, targetIndex: Int): Boolean {
        val from = notebook.pageOrder.indexOf(pageId)
        if (from < 0) return false
        val target = targetIndex.coerceIn(0, notebook.pageOrder.lastIndex)
        if (target == from) return true
        commandStack.execute(MovePageCommand(notebook, pageId, target))
        onDocChanged?.invoke()
        return true
    }

    fun toggleCurrentPageBookmark(): Boolean {
        val target = !page.bookmarked
        commandStack.execute(SetPageBookmarkCommand(page, target))
        notebook.refreshPageMetadata(page)
        onDocChanged?.invoke()
        return target
    }

    fun requestThumbnails(pageIds: List<UUID>) {
        if (page.id in pageIds) {
            materialize()
            requestThumbnail(page)
        }
        pageIds.filter { it != page.id }.forEach { id ->
            val metadata = notebook.metadata(id) ?: return@forEach
            val key = ThumbnailKey(id, metadata.contentRevision)
            val loaded = notebook.getPage(id)
            if (loaded != null) {
                thumbnails.request(PageSnapshot.capture(loaded)) { onDocChanged?.invoke() }
            } else {
                thumbnails.request(
                    key,
                    snapshotProvider = { repository.loadPage(id)?.let(PageSnapshot::capture) }
                ) { onDocChanged?.invoke() }
            }
        }
    }

    private fun activatePage(index: Int) {
        val id = notebook.pageOrder[index]
        val target = notebook.getPage(id) ?: pageCache.get(id) ?: repository.loadPage(id)
            ?: Page(id = id).also { notebook.attachPage(it) }
        notebook.attachPage(target)
        pageCache.put(target)?.let { evicted ->
            if (evicted.id != target.id) notebook.detachPage(evicted.id)
        }
        page = target
        selectedRichObjectId = null
        if (width > 0 && height > 0 && (page.width <= 0f || page.height <= 0f)) {
            page.updateSize(width.toFloat(), height.toFloat())
            notebook.refreshPageMetadata(page)
            thumbnails.invalidate(page.id, page.contentRevision)
        }
        rebuildTools()
        warmAdjacentPages(index)
        redrawAll()
        clearOverlayInk()
        requestThumbnail(page)
        onDocChanged?.invoke()
        EventLog.log(TAG, "page ${index + 1}/${notebook.pageOrder.size} id=$id")
    }

    private fun rebuildTools() {
        penTool = PenTool(page, commandStack, this) { penStyle }
        strokeEraserTool = StrokeEraserTool(page, commandStack, this) { eraserWidth / 2.0f }
        pointEraserTool = PointEraserTool(page, commandStack, this) { eraserWidth / 2.0f }
        selectionTool = SelectionTool(page, commandStack, this) { HANDLE_TOUCH_RADIUS }
        currentTool = when (toolKind) {
            ToolKind.PEN -> penTool
            ToolKind.LASSO -> selectionTool
        }
    }

    /** Keeps only the current/previous/next Scene window resident. */
    private fun warmAdjacentPages(index: Int) {
        val desired = (index - 1..index + 1)
            .filter { it in notebook.pageOrder.indices }
            .map { notebook.pageOrder[it] }
            .toSet()
        pageCache.retain(desired).forEach { evicted ->
            if (evicted.id != page.id) notebook.detachPage(evicted.id)
        }
        for (id in desired) {
            val loaded = notebook.getPage(id) ?: repository.loadPage(id)?.also(notebook::attachPage)
            if (loaded != null) {
                pageCache.put(loaded)?.let { evicted ->
                    if (evicted.id != page.id) notebook.detachPage(evicted.id)
                }
            }
        }
    }

    private fun requestThumbnail(target: Page) {
        thumbnails.request(PageSnapshot.capture(target)) { onDocChanged?.invoke() }
    }

    private fun createBitmap(w: Int, h: Int) {
        val bmp = foreBitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            foreBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmapCanvas = Canvas(foreBitmap!!)
            EventLog.log(TAG, "bitmap ${w}x$h ARGB_8888")
            // Size changed, so the cache is stale -- rebuild it from the vectors.
            redrawAll()
        }
    }

    /** Port of HandView.intiPenDraw: bind the hvpen service and start the area. */
    private fun initPenDraw() {
        if (penDraw == null) {
            penDraw = context.getSystemService("hvpen") as? HvPenDrawManager
            if (penDraw == null) {
                EventLog.log(TAG, "ERROR getSystemService(hvpen) returned null")
                return
            }
            EventLog.log(TAG, "hvpen service acquired")
        }
        if (initPenService) return
        initPenService = true

        val rotation = display?.rotation ?: 0
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
        val area = PenGeometry.getPenDrawArea(rect, rotation, originPos, screenW, screenH)
        EventLog.log(TAG, "initService area=[${area.left},${area.top},${area.right},${area.bottom}] rot=$rotation")
        try {
            penDrawPt = penDraw!!.initService(area.left, area.top, area.right, area.bottom, this)
            applyPenStyleToService()
            penDraw!!.enablePen(penDrawPt, romPenInkRequested && !uiInputBlocked)
            penDraw!!.setDrawStatus(penDrawPt, HvPenDrawManager.PEN_MODE)
            penDraw!!.setAreaActive(penDrawPt, true)
            EventLog.log(TAG, "pen configured handle=$penDrawPt width=${penStyle.baseWidth} enablePen=true")
        } catch (t: Throwable) {
            EventLog.log(TAG, "ERROR initService: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Resolves eraser / side buttons per gesture, mirroring HandView.onTouchEvent
     * + MemoView.checkPenbtnMode. The ROM intercepts touch dispatch for live ink
     * lower down, so this only observes; it does not consume the event.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // hvNote performs side-button classification in HandView.onTouchEvent.
        // Observe here too because the Hanvon input layer can route vendor tool
        // type 6 directly to the hvpen-registered view without a useful key event.
        PenButtonTracker.observeMotion(event, "pen-view")
        // Only normal pen writing uses this hvpen-registered view. Lasso and
        // tail erasing are driven by unregistered overlay views because those
        // receive the full standard Android touch stream on this ROM.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && toolKind == ToolKind.PEN) {
            beginGesture(event.x, event.y)
        }
        return super.onTouchEvent(event)
    }

    private fun beginGesture(x: Float, y: Float) {
        handler.removeCallbacks(finishRunnable)
        // A gesture starting while one is open means the previous ACTION_UP was
        // lost; close it out so its points do not bleed into this gesture.
        if (gestureOpen) finishGesture()
        // The lasso tool selects against the app bitmap and draws its own
        // transient marquee/selection overlay, so it must own the screen:
        // suppress ROM pen ink and materialize the strokes into our bitmap up
        // front, the same "structural sync" the tail eraser does in beginErase().
        // Done inline rather than via materialize() because we are about to open
        // this gesture and materialize() would finishGesture() it. (Erasing is
        // not a tip tool -- see EraserMode -- so PEN is the only other case and
        // it stays on the ROM's live overlay.)
        if (toolKind != ToolKind.PEN) {
            setRomPenInkEnabled(false)
            // The marquee overlay invalidates on every sample; in the panel's
            // quality/full-refresh mode each repaint is slow and they queue into
            // visible lag. Put the panel in fast/autowrite (A2) mode for the
            // duration of the gesture -- the same enterAutowrite the vendor app
            // wraps its eraser path in -- and revert to a single quality refresh
            // at pen-up (finishGesture).
            eink.enterEraseFastMode()
            redrawAll()
            clearOverlayInk()
        }
        gestureOpen = true
        currentTool.onDown(x, y)
        if (toolKind != ToolKind.PEN) postInvalidate()
    }

    /** Sets the tip-driven tool the toolbar has selected. Any open gesture is finished first. */
    fun setTool(kind: ToolKind) {
        if (gestureOpen) finishGesture()
        toolKind = kind
        currentTool = when (kind) {
            ToolKind.PEN -> penTool
            ToolKind.LASSO -> selectionTool
        }
    }

    fun currentToolKind(): ToolKind = toolKind

    /**
     * A Compose popup does not stop the ROM's hardware pen overlay. Materialize first so all
     * existing overlay ink is safely represented by the app bitmap, then suspend hvpen until
     * the popup is dismissed. This prevents stylus taps on pen controls from becoming strokes.
     */
    fun setUiInputBlocked(blocked: Boolean) {
        if (uiInputBlocked == blocked) return
        if (blocked) materialize()
        uiInputBlocked = blocked
        // UI surfaces only suspend hvpen temporarily. Some gesture paths also
        // change romPenInkRequested, so explicitly restore the user's normal
        // writing state when the last blocking surface closes.
        if (!blocked) romPenInkRequested = true
        applyRomPenInkState(resetData = true)
        EventLog.log(TAG, "ui input blocked=$blocked romRequested=$romPenInkRequested")
    }

    /** Reasserts the vendor pen service after Android has suspended the process. */
    fun recoverAfterWake() {
        if (uiInputBlocked || width <= 0 || height <= 0) return
        romPenInkRequested = true
        try {
            if (!initPenService) {
                initPenDraw()
            } else {
                applyPenStyleToService()
                penDraw?.setAreaActive(penDrawPt, true)
                applyRomPenInkState(resetData = true)
            }
            EventLog.log(TAG, "hvpen recovered after wake handle=$penDrawPt")
        } catch (t: Throwable) {
            EventLog.log(TAG, "ERROR recovering hvpen: ${t.javaClass.simpleName}: ${t.message}")
            initPenService = false
            penDraw = null
            initPenDraw()
        }
    }

    // -- Lasso API, called by LassoOverlayView --------------------------------

    /** Starts a lasso/move/scale gesture from the non-hvpen input overlay. */
    fun beginLasso(x: Float, y: Float, eventTime: Long) {
        if (toolKind != ToolKind.LASSO) return
        beginGesture(x, y)
        EventLog.log(TAG, "lasso begin xy=(${"%.0f".format(x)},${"%.0f".format(y)}) t=$eventTime")
    }

    /** Adds one view-space sample batch from the overlay's MOVE event. */
    fun updateLasso(samples: List<InkPoint>) {
        if (toolKind != ToolKind.LASSO || !gestureOpen) return
        if (samples.isEmpty()) return
        selectionTool.onBatch(samples)
        invalidateToolOverlay()
    }

    /** Completes or cancels the overlay-driven lasso gesture and restores ROM ink. */
    fun endLasso(cancelled: Boolean) {
        if (toolKind != ToolKind.LASSO || !gestureOpen) return
        if (!cancelled) {
            finishGesture()
            return
        }

        handler.removeCallbacks(finishRunnable)
        gestureOpen = false
        selectionTool.onCancel()
        onDocChanged?.invoke()
        eink.exitEraseFastMode()
        redrawAll()
        clearOverlayInk()
        setRomPenInkEnabled(true)
        EventLog.log(TAG, "lasso cancelled")
    }

    /** Sets the tail-eraser behavior (whole-stroke vs point). */
    fun setEraserMode(mode: EraserMode) {
        eraserMode = mode
    }

    fun currentEraserMode(): EraserMode = eraserMode

    /** HvPenDrawListener callback -- one batch of sampled points. */
    override fun onPenTouchUpStatus(up: Boolean, points: FloatArray?) {
        if (points == null || points.isEmpty()) return
        val n = points[0].toInt()
        // Layout is count + N triples read at (i*3+1, i*3+2, i*3+3), so the last
        // index touched is (n-1)*3+3 = n*3, requiring length >= n*3+1.
        if (points.size.toFloat() != points[0] * 3.0f + 1.0f) {
            EventLog.log(TAG, "batch up=$up n=$n len=${points.size} (unexpected layout, skipped)")
            return
        }
        val msg = handler.obtainMessage(MSG_POINTS)
        msg.data = android.os.Bundle().apply {
            putFloatArray("points", points)
            putBoolean("up", up)
        }
        msg.sendToTarget()
    }

    private fun onBatch(raw: FloatArray, up: Boolean) {
        // Lasso is deliberately driven only by LassoOverlayView. beginLasso()
        // disables ROM pen ink, which also suppresses this callback on the
        // target device; ignore any already-queued batch so it cannot duplicate
        // or corrupt the overlay gesture.
        if (toolKind == ToolKind.LASSO) return

        val n = raw[0].toInt()
        if (n <= 0) return

        // A batch can arrive without a preceding ACTION_DOWN if the ROM starts
        // reporting before touch dispatch reaches us; open a gesture rather than
        // discarding real input.
        if (!gestureOpen) {
            gestureOpen = true
            EventLog.log(TAG, "batch without DOWN, opened gesture")
        }

        val now = System.currentTimeMillis()
        val rotation = display?.rotation ?: 0
        val loc = IntArray(2)
        getLocationOnScreen(loc)

        val batch = ArrayList<InkPoint>(n)
        // Tracked so the raw sensor range shows up in the log -- lets us sanity
        // check what pressureCalibrator is converging on against real hardware.
        var rawPressureMin = Float.MAX_VALUE
        var rawPressureMax = -Float.MAX_VALUE
        for (i in 0 until n) {
            // Stride of 3 starting at index 1: x, y, pressure. Note the pressure
            // for point i sits at i*3+3, i.e. the triples are offset by one from
            // the naive [count, x,y,p, ...] reading -- see HandView's use of
            // (i*3+1, i*3+2, i*3+3).
            val base = i * 3
            val sysX = raw[base + 1]
            val sysY = raw[base + 2]
            val rawPressure = raw[base + 3]
            if (rawPressure < rawPressureMin) rawPressureMin = rawPressure
            if (rawPressure > rawPressureMax) rawPressureMax = rawPressure

            // Digitizer system coords -> view coords. Without this the stroke is
            // rendered with its axes swapped (see PenGeometry.pointSysToClient).
            val p = PenGeometry.pointSysToClient(
                sysX, sysY, loc[0], loc[1], rotation, originPos, screenW, screenH
            )

            batch.add(
                InkPoint(
                    x = p[0],
                    y = p[1],
                    pressure = normalizePressure(rawPressure),
                    timestamp = now
                )
            )
        }
        currentTool.onBatch(batch)

        // The lasso tool draws a transient app-side overlay (marquee, selection
        // box) in onDraw; invalidate so it follows the tip. It also mutates the
        // model via ToolHost.requestRepaint, which invalidates the changed
        // strokes' region; this extra invalidate covers the marquee that has no
        // backing document object. (Erasing is driven by the tail-eraser overlay,
        // not batches -- see eraseMove.)
        if (toolKind != ToolKind.PEN) {
            invalidateToolOverlay()
        }

        // Do NOT resetData and do NOT draw into our bitmap while writing. The
        // ROM's hardware overlay is the live layer: it renders the in-flight
        // stroke with the lowest latency and no e-ink flash, so we let it show
        // and stop competing with it. We only accumulate the vector model here;
        // the strokes are materialized into our bitmap (and the ROM overlay
        // cleared) later, at a structural sync point -- page turn or erase (see
        // materialize()). This is the "don't fight the hardware" model: ROM ink
        // during writing, our simplified/modeled geometry after materialize.
        //
        // Consequence: the ink-stroke-modeler smoothing/simplification is only
        // visible AFTER materialize; at that moment the on-screen ink snaps from
        // the ROM's raw rendering to our vector rendering once.

        // Safety net: if ACTION_UP never arrives (the ROM can report points
        // without touch dispatch reaching this view), an open gesture would
        // otherwise swallow everything that follows. Re-armed per batch with a
        // delay far longer than the gap between batches during real writing, so
        // it never splits a slow-but-continuous gesture.
        handler.removeCallbacks(finishRunnable)
        handler.postDelayed(finishRunnable, IDLE_TIMEOUT_MS)

        val first = batch.firstOrNull()
        EventLog.log(
            TAG,
            "batch n=$n tool=$toolKind up=$up " +
                "p=[${"%.0f".format(rawPressureMin)}..${"%.0f".format(rawPressureMax)}] " +
                "xy=(${"%.0f".format(first?.x ?: 0f)},${"%.0f".format(first?.y ?: 0f)})"
        )

        // For non-pen tools, the ROM's `up` flag lets us flush at true pen-up
        // ("抬笔即 flush") instead of waiting for the idle timeout. The PEN tool
        // is left on its existing finalization (idle timeout / next ACTION_DOWN)
        // to avoid stroke-splitting regressions. The idle timeout above stays
        // armed as a backstop in case `up` proves unreliable on-device.
        if (up && toolKind != ToolKind.PEN) {
            finishGesture()
        }
    }

    /**
     * Suspend/resume the ROM's low-latency pen ink.
     *
     * The tail eraser is driven by a separate non-hvpen overlay view (see
     * [EraserOverlayView]) that owns the whole erase gesture. But the hvpen
     * service still sees the same stylus and would paint ink on its hardware
     * overlay while the user is erasing. enablePen(handle, false) tells the ROM
     * to stop drawing pen ink for the duration of the erase, then true restores
     * it -- this is the real purpose of the vendor's enablePen(false) on
     * trace-erase entry (it gates ROM ink, not touch routing). No setDrawStatus
     * pixel-erase (+0x69) is used at all: erasing is pure app-side vector
     * deletion, so the screen never diverges from the [page] model.
     */
    private fun setRomPenInkEnabled(enabled: Boolean) {
        romPenInkRequested = enabled
        applyRomPenInkState(resetData = true)
    }

    private fun applyRomPenInkState(resetData: Boolean) {
        val pd = penDraw ?: return
        val enabled = romPenInkRequested && !uiInputBlocked
        try {
            pd.enablePen(penDrawPt, enabled)
            if (resetData) pd.resetData()
        } catch (t: Throwable) {
            EventLog.log(TAG, "ERROR enablePen($enabled): ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Drop the ink the ROM is holding on the hardware overlay.
     *
     * The overlay is the ROM's own low-latency rendering of the in-flight
     * stroke; it is not cleared automatically when the points are handed to us.
     * Once we have rasterized those points ourselves, the overlay copy is a
     * duplicate, so it has to be released or it stays visible on the panel.
     */
    private fun clearOverlayInk() {
        try {
            penDraw?.resetData()
        } catch (t: Throwable) {
            EventLog.log(TAG, "ERROR resetData: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun finishGesture() {
        handler.removeCallbacks(finishRunnable)
        if (!gestureOpen) return
        gestureOpen = false
        currentTool.onUp()
        onDocChanged?.invoke()

        // Non-pen tools: pen-up is the flush point ("抬笔即 flush"). Do one clean
        // full redraw from the (now reduced/moved) model and drop any ROM
        // overlay residue, then let the ROM paint pen ink again. gestureOpen is
        // already false so materialize()'s finishGesture guard is a no-op.
        if (toolKind != ToolKind.PEN) {
            // Back to quality mode BEFORE the materialize repaint, so the single
            // pen-up refresh is a clean full-quality flush (no A2 residue) and
            // the fast-mode window is scoped exactly to the drag.
            eink.exitEraseFastMode()
            materialize()
            setRomPenInkEnabled(true)
        }

        // Do NOT repaint the region at pen-up for the pen tool: the live pass
        // already painted this stroke, and StrokeGeometry.buildRange made that
        // live geometry match the final simplified outline (spec §2.1 seam fix),
        // so the pixels on screen are already correct -- see PenTool. Erasers
        // and the selection tool request their own repaints via ToolHost as
        // they mutate the model, which IS necessary since nothing painted them
        // live.
        EventLog.log(TAG, "gesture finished tool=$toolKind total=${page.scene.size}")
    }

    /**
     * Re-rasterize one region from the document objects. Only objects whose
     * page-space bounds touch [dirty] are redrawn, so this stays proportional
     * to local content rather than to the page's total object count.
     *
     * Tests candidate objects against [dirty] itself -- the same (inflated)
     * rect that gets cleared -- rather than a tighter un-inflated bounds a
     * caller might otherwise pass. Testing against a tighter region would let
     * an object whose ink falls only in dirty's margin (stroke-width +
     * anti-alias padding, see [InkRenderer.dirtyRect]) get cleared by the
     * CLEAR pass below without ever being selected for redraw.
     */
    private fun repaintRegion(dirty: android.graphics.Rect) {
        val canvas = bitmapCanvas ?: return
        val testBounds = Bounds(
            dirty.left.toFloat(), dirty.top.toFloat(),
            dirty.right.toFloat(), dirty.bottom.toFloat()
        )
        val save = canvas.save()
        canvas.clipRect(dirty)
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        for (obj in page.scene.all()) {
            if (obj.pageBounds.intersects(testBounds)) drawObject(canvas, obj)
        }
        canvas.restoreToCount(save)
        postInvalidate(dirty.left, dirty.top, dirty.right, dirty.bottom)
    }

    private val objectMatrix = Matrix()

    /** Draws one [PageObject] with its [Transform2D] applied (spec §10, §38-39). */
    private fun drawObject(canvas: Canvas, obj: PageObject) {
        val t = obj.transform
        objectMatrix.setValues(floatArrayOf(t.a, t.c, t.tx, t.b, t.d, t.ty, 0f, 0f, 1f))
        val save = canvas.save()
        canvas.concat(objectMatrix)
        when (obj) {
            is StrokeObject -> renderer.drawStroke(canvas, obj.stroke)
            is ImageObject -> drawImageObject(canvas, obj)
            is TextObject -> drawTextObject(canvas, obj)
        }
        canvas.restoreToCount(save)
    }

    private fun drawImageObject(canvas: Canvas, obj: ImageObject) {
        val bitmap = imageCache.get(obj.assetPath) ?: decodeImage(obj)?.also {
            imageCache.put(obj.assetPath, it)
        }
        val rawTarget = RectF(0f, 0f, obj.pixelWidth.toFloat(), obj.pixelHeight.toFloat())
        val displayTarget = RectF(
            obj.localBounds.left, obj.localBounds.top, obj.localBounds.right, obj.localBounds.bottom
        )
        if (bitmap != null) {
            val save = canvas.save()
            canvas.concat(exifMatrix(obj))
            canvas.drawBitmap(bitmap, null, rawTarget, imagePaint)
            canvas.restoreToCount(save)
        } else {
            canvas.drawRect(displayTarget, marqueePaint)
            canvas.drawLine(displayTarget.left, displayTarget.top, displayTarget.right, displayTarget.bottom, marqueePaint)
            canvas.drawLine(displayTarget.right, displayTarget.top, displayTarget.left, displayTarget.bottom, marqueePaint)
        }
    }

    private fun exifMatrix(obj: ImageObject): Matrix = Matrix().apply {
        val w = obj.pixelWidth.toFloat()
        val h = obj.pixelHeight.toFloat()
        val values = when (obj.exifOrientation) {
            2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
            3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
            4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
            5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
            7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
            8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        setValues(values)
    }

    private fun decodeImage(obj: ImageObject): Bitmap? {
        val file = imageAssets.resolve(obj.assetPath) ?: return null
        val maxDimension = maxOf(obj.pixelWidth, obj.pixelHeight)
        var sample = 1
        while (maxDimension / sample > MAX_DECODE_DIMENSION) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun drawTextObject(canvas: Canvas, obj: TextObject) {
        configureTextPaint(obj.fontFamily, obj.fontSize)
        val metrics = textPaint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        var baseline = -metrics.ascent
        obj.text.split('\n').forEach { line ->
            canvas.drawText(line, 0f, baseline, textPaint)
            baseline += lineHeight
        }
    }

    // -- ToolHost (spec §17): tools call back into the view to repaint or update selection UI --

    override fun requestRepaint(bounds: Bounds) {
        repaintRegion(InkRenderer.dirtyRect(bounds, penStyle))
        onDocChanged?.invoke()
    }

    override fun onSelectionChanged(selection: SelectionSet) {
        if (!selection.isEmpty) selectedRichObjectId = null
        onDocChanged?.invoke()
    }

    fun currentSelection(): SelectionSet = selectedRichObject()?.let { SelectionSet.of(listOf(it)) }
        ?: selectionTool.currentSelection()

    /** Deletes the current lasso selection as one undo step (toolbar Delete action). */
    fun deleteSelection() {
        val rich = selectedRichObject()
        if (rich != null) {
            commandStack.execute(DeleteObjectsCommand(page, listOf(rich.id)))
            selectedRichObjectId = null
            requestRepaint(rich.pageBounds.inflate(HANDLE_TOUCH_RADIUS * 2f))
            onDocChanged?.invoke()
        } else {
            selectionTool.deleteSelection()
        }
    }

    fun undo() {
        if (gestureOpen) finishGesture()
        commandStack.undo()
        ensureCurrentPageExists()
        redrawAll()
        onDocChanged?.invoke()
    }

    fun redo() {
        if (gestureOpen) finishGesture()
        commandStack.redo()
        ensureCurrentPageExists()
        redrawAll()
        onDocChanged?.invoke()
    }

    private fun ensureCurrentPageExists() {
        if (page.id in notebook.pageOrder) return
        if (notebook.pageOrder.isEmpty()) {
            val replacement = Page(width = width.toFloat(), height = height.toFloat())
            notebook.addPage(replacement)
        }
        activatePage(0)
    }

    fun canUndo(): Boolean = commandStack.canUndo
    fun canRedo(): Boolean = commandStack.canRedo

    // -- Eraser API, called by EraserOverlayView for the hardware tail eraser --
    //
    // The tail eraser is a separate, non-hvpen overlay view (the only kind of
    // view the ROM forwards ACTION_MOVE to on this panel); it owns the erase
    // gesture and feeds coordinates here. Coordinates are in THIS view's space:
    // the overlay is laid out identically over PenDrawView, so its event.x/y
    // already match the client coords objects are stored in -- no PenGeometry
    // conversion (that is only for the digitizer/system coords the hvpen
    // callback delivers). It always erases using whichever eraser sub-mode
    // (whole-stroke vs point) the toolbar currently has selected, forwarding to
    // [EraserTool] directly rather than through [currentTool] -- the tail
    // eraser has its own independent touch stream and must erase regardless of
    // whether the toolbar's active TOOL is one of the pen-tip eraser tools.

    private fun activeEraserTool(): EraserTool =
        if (eraserMode == EraserMode.POINT) pointEraserTool else strokeEraserTool

    /** Erase gesture starting: stop the ROM painting pen ink while erasing. */
    fun beginErase() {
        setRomPenInkEnabled(false)
        // Fast/autowrite mode for the drag so the per-sample eraser-disc repaints
        // don't queue into lag on the e-ink panel (see beginGesture). Reverted to
        // a single quality refresh in endErase.
        eink.enterEraseFastMode()
        // Writing leaves the live ink on the ROM overlay with our bitmap empty;
        // materialize so the strokes exist as real pixels in our bitmap (and the
        // ROM ink is dropped) before eraseMove starts deleting regions from it.
        materialize()
        EventLog.log(TAG, "erase begin")
    }

    /**
     * One eraser sample in view coordinates, from the tail-eraser overlay. Each
     * hit strokes' region is repainted by the tool via ToolHost.requestRepaint;
     * no cursor is drawn (a per-sample disc lagged on e-ink -- see the overlay
     * state comment).
     */
    fun eraseMove(x: Float, y: Float) {
        activeEraserTool().eraseAt(x, y)
    }

    /** Erase gesture ended: commit the accumulated erase as one undo step, let the ROM paint ink again. */
    fun endErase() {
        activeEraserTool().commit()
        onDocChanged?.invoke()
        // Revert to quality mode before the final full repaint so it's a clean
        // flush, not an A2-mode partial (see finishGesture).
        eink.exitEraseFastMode()
        setRomPenInkEnabled(true)
        postInvalidate()
        EventLog.log(TAG, "erase end")
    }

    /**
     * On this device the ROM reports pressure as a small discrete level, not
     * a fine-grained ADC reading: logged raw values cluster tightly in
     * [PRESSURE_RAW_MIN, PRESSURE_RAW_MAX] across many different strokes (see
     * the batch log's `p=[..]` range). The original constant here (4095) came
     * from an unrelated guess and, being ~1000x too large, collapsed nearly
     * every sample to the bottom of [0,1] -- producing uniformly thin
     * strokes. This app targets one specific device rather than a range of
     * hardware, so a fixed linear scale calibrated to that logged range is
     * simpler and less error-prone than runtime auto-calibration.
     */
    private fun normalizePressure(rawPressure: Float): Float =
        ((rawPressure - PRESSURE_RAW_MIN) / (PRESSURE_RAW_MAX - PRESSURE_RAW_MIN)).coerceIn(0.0f, 1.0f)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        foreBitmap?.let { c.drawBitmap(it, 0f, 0f, null) }
        drawToolOverlay(c)
    }

    /**
     * Draws the transient lasso affordances on top of the document bitmap: the
     * dashed marquee while lassoing, and the dashed selection box with corner
     * handles once a selection exists. Never touches [foreBitmap], so it is not
     * part of the document and clears on the next repaint / at materialize. The
     * eraser deliberately has no cursor overlay (see the overlay state comment).
     */
    private fun drawToolOverlay(c: Canvas) {
        if (toolKind == ToolKind.LASSO) {
            // In-progress lasso marquee.
            val lasso = selectionTool.activeLassoPath()
            if (lasso != null && lasso.size >= 4) {
                overlayPath.rewind()
                overlayPath.moveTo(lasso[0], lasso[1])
                var i = 2
                while (i + 1 < lasso.size) {
                    overlayPath.lineTo(lasso[i], lasso[i + 1])
                    i += 2
                }
                overlayPath.close()
                c.drawPath(overlayPath, marqueePaint)
            }
            // Settled/live selection bounding box + corner handles.
            val sel = selectionTool.currentSelection()
            if (!sel.isEmpty) {
                val b = sel.bounds
                c.drawRect(b.left, b.top, b.right, b.bottom, marqueePaint)
                val h = HANDLE_TOUCH_RADIUS
                for (corner in arrayOf(
                    b.left to b.top, b.right to b.top,
                    b.left to b.bottom, b.right to b.bottom
                )) {
                    c.drawRect(
                        corner.first - h, corner.second - h,
                        corner.first + h, corner.second + h,
                        handlePaint
                    )
                }
            }
        }
        selectedRichObject()?.let { obj ->
            val corners = objectCorners(obj)
            overlayPath.rewind()
            overlayPath.moveTo(corners[0][0], corners[0][1])
            overlayPath.lineTo(corners[1][0], corners[1][1])
            overlayPath.lineTo(corners[3][0], corners[3][1])
            overlayPath.lineTo(corners[2][0], corners[2][1])
            overlayPath.close()
            c.drawPath(overlayPath, marqueePaint)
            if (obj is ImageObject) {
                corners.forEach { point ->
                    c.drawCircle(point[0], point[1], HANDLE_TOUCH_RADIUS, handlePaint)
                }
                val b = obj.localBounds
                val top = obj.transform.mapPoint((b.left + b.right) / 2f, b.top)
                val rotate = rotationHandle(obj)
                c.drawLine(top[0], top[1], rotate[0], rotate[1], marqueePaint)
                c.drawCircle(rotate[0], rotate[1], HANDLE_TOUCH_RADIUS, handlePaint)
            }
        }
    }

    /**
     * Invalidate for the lasso overlay so the marquee / selection box track the
     * tip without a full-screen e-ink refresh per batch. Errs large (whole view,
     * since the marquee and selection box can span it); the clean single full
     * refresh happens once at materialize (pen-up).
     */
    private fun invalidateToolOverlay() {
        if (toolKind == ToolKind.LASSO) postInvalidate()
    }

    /** Rebuild the bitmap cache from the document objects (spec §2.1). */
    private fun redrawAll() {
        val canvas = bitmapCanvas ?: return
        foreBitmap?.eraseColor(0)
        for (obj in page.scene.all()) drawObject(canvas, obj)
        postInvalidate()
    }

    /**
     * Materialize the document into our bitmap and drop the ROM's overlay ink.
     * During writing the live layer is the ROM's hardware overlay and our
     * bitmap is empty; this is the structural sync point that replaces that ROM
     * ink with our own vector rendering (simplified + modeled geometry). Call it
     * before any operation that must work against real pixels in our bitmap
     * (erase) or that resets the surface (page turn). This is the one place the
     * e-ink full-refresh flash is acceptable, because it is a deliberate
     * structural change, not per-stroke.
     */
    private fun materialize() {
        // Close any in-flight gesture so its points are in the model first.
        if (gestureOpen) finishGesture()
        redrawAll()
        clearOverlayInk()
    }

    /**
     * Structural sync point for a page turn (or any full re-render): flush the
     * current document to the bitmap and clear the ROM overlay. The caller
     * (page navigation) is responsible for swapping in the next page's objects
     * afterward. Exposed for the document/page layer.
     */
    fun flushToBitmap() = materialize()

    fun setPenStyle(style: PenStyle) {
        penStyle = style
        if (initPenService) {
            try {
                applyPenStyleToService()
            } catch (t: Throwable) {
                EventLog.log(TAG, "ERROR setPenStyle: ${t.message}")
            }
        }
    }

    /** Keep the ROM's low-latency overlay in sync with the immutable style captured by new strokes. */
    private fun applyPenStyleToService() {
        val pd = penDraw ?: return
        val servicePen = PenProfiles.servicePen(penStyle.penType)
        val serviceColor = PenProfiles.serviceColor(penStyle, HanvonHardware.isColorDevice)
        val serviceWidth = penStyle.baseWidth.toInt().coerceAtLeast(1)
        pd.setPen(penDrawPt, servicePen)
        pd.setPenColor(penDrawPt, serviceColor)
        pd.setPenWidth(penDrawPt, serviceWidth)
        EventLog.log(
            TAG,
            "pen style type=${penStyle.penType} servicePen=$servicePen " +
                "color=0x${serviceColor.toUInt().toString(16)} width=$serviceWidth"
        )
    }


    fun strokeCount(): Int = page.scene.size

    fun clear() {
        handler.removeCallbacks(finishRunnable)
        gestureOpen = false
        page.clear()
        notebook.refreshPageMetadata(page)
        thumbnails.invalidate(page.id, page.contentRevision)
        commandStack.clear()
        scheduleSave(DocumentChange.fullPage(notebook, page, "page:clear"))
        foreBitmap?.eraseColor(0)
        clearOverlayInk()
        postInvalidate()
        onDocChanged?.invoke()
        EventLog.log(TAG, "clear")
    }

    fun teardown() {
        handler.removeCallbacks(finishRunnable)
        if (gestureOpen) finishGesture()
        requestThumbnail(page)
        scheduleSave(DocumentChange.fullPage(notebook, page, "lifecycle:teardown"))
        val pd = penDraw
        if (pd != null) {
            try {
                pd.endService(penDrawPt, this)
                EventLog.log(TAG, "endService handle=$penDrawPt")
            } catch (t: Throwable) {
                EventLog.log(TAG, "ERROR endService: ${t.message}")
            }
        }
        initPenService = false
        notebookOperations.shutdown()
        notebookOperations.awaitTermination(10, TimeUnit.SECONDS)
        autosave.close()
        thumbnails.close()
    }

    /** UI "save": drain pending operations and checkpoint the WAL (spec §56). */
    fun flushPersistence(): Boolean {
        if (gestureOpen) finishGesture()
        if (!persistenceAvailable) return false
        scheduleSave(DocumentChange.fullPage(notebook, page, "manual:flush"))
        return autosave.flush()
    }

    suspend fun flushPersistenceForExport(): Boolean {
        val ready = withContext(Dispatchers.Main.immediate) {
            if (gestureOpen) finishGesture()
            if (!persistenceAvailable) return@withContext false
            scheduleSave(DocumentChange.fullPage(notebook, page, "export:flush"))
            true
        }
        return ready && withContext(Dispatchers.IO) { autosave.flush() }
    }

    companion object {
        private const val TAG = "PenDrawView"
        private const val MSG_POINTS = 0x5dd

        private const val DEFAULT_PEN_WIDTH = 3.0f

        /**
         * Eraser diameter. The vendor ships {10, 20, 30} as its three sizes
         * (EraserSetting.ERASER_PIXEL_WIDTH); this is the middle one.
         */
        private const val DEFAULT_ERASER_WIDTH = 20

        /** Touch radius for the selection tool's corner scale handles, in view px. */
        private const val HANDLE_TOUCH_RADIUS = 24.0f
        private const val ROTATE_HANDLE_OFFSET = 56.0f
        private const val MAX_DECODE_DIMENSION = 2048

        /**
         * Observed raw pressure range on-device: batch logs consistently show
         * `p=[0..4]` / `p=[1..4]` / `p=[1..3]` across many different strokes,
         * so the ROM appears to report a small discrete level rather than a
         * fine-grained ADC value. Widen these if a wider range is ever logged.
         */
        private const val PRESSURE_RAW_MIN = 0.0f
        private const val PRESSURE_RAW_MAX = 4.0f

        /** Long enough to catch a trailing batch, short enough to feel immediate. */
        private const val FINALIZE_DELAY_MS = 80L

        /**
         * Backstop for a gesture that never gets an ACTION_UP. Must stay well
         * above the inter-batch gap seen while writing, or one slow stroke would
         * be chopped into several.
         */
        private const val IDLE_TIMEOUT_MS = 600L
        private const val PAGE_CACHE_SIZE = 3
    }
}
