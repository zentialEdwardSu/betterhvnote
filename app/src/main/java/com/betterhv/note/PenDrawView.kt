package com.betterhv.note

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
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
import androidx.core.graphics.withClip
import androidx.core.graphics.withMatrix
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import com.betterhv.note.doc.CommandStack
import com.betterhv.note.doc.DEFAULT_TEMPLATE_ID
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.Notebook
import com.betterhv.note.doc.NotebookKind
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.PageKind
import com.betterhv.note.doc.PageObject
import com.betterhv.note.doc.PdfAnchor
import com.betterhv.note.doc.PdfAnchorKind
import com.betterhv.note.doc.SelectionSet
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.TextFontFamily
import com.betterhv.note.doc.TextObject
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.doc.commands.AddObjectCommand
import com.betterhv.note.doc.commands.AddPageCommand
import com.betterhv.note.doc.commands.DeleteObjectsCommand
import com.betterhv.note.doc.commands.DeletePageCommand
import com.betterhv.note.doc.commands.MovePageCommand
import com.betterhv.note.doc.commands.SetPageBookmarkCommand
import com.betterhv.note.doc.commands.SetPageTemplateCommand
import com.betterhv.note.doc.commands.TransformObjectsCommand
import com.betterhv.note.doc.commands.UpdateObjectCommand
import com.betterhv.note.doc.inheritedTemplateId
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.InkRenderer
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.pdf.MuPdfEngine
import com.betterhv.note.pdf.PdfAssetStore
import com.betterhv.note.pdf.PdfImportCoordinator
import com.betterhv.note.storage.AutosaveController
import com.betterhv.note.storage.DocumentChange
import com.betterhv.note.storage.ImageAssetStore
import com.betterhv.note.storage.ImportedImage
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.storage.NotebookSummary
import com.betterhv.note.storage.PageCache
import com.betterhv.note.storage.PageSnapshot
import com.betterhv.note.storage.StartupBehavior
import com.betterhv.note.storage.ThumbnailKey
import com.betterhv.note.storage.ThumbnailManager
import com.betterhv.note.storage.TransferReceipt
import com.betterhv.note.template.TemplateCatalogSnapshot
import com.betterhv.note.template.TemplateStore
import com.betterhv.note.tool.EraserTool
import com.betterhv.note.tool.PenTool
import com.betterhv.note.tool.PointEraserTool
import com.betterhv.note.tool.SelectionTool
import com.betterhv.note.tool.StrokeEraserTool
import com.betterhv.note.tool.Tool
import com.betterhv.note.tool.ToolHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Which tip-driven editing tool the toolbar currently has selected. Erasing is
 * NOT a tip tool: it is done only by the hardware tail eraser (see
 * [EraserOverlayView]) -- the ROM drives that reliably, whereas a tip-driven
 * eraser's moving cursor overlay lagged badly on the e-ink panel. The tail
 * eraser's behavior is chosen by [EraserMode], toggled independently of the tip
 * tool.
 */
enum class ToolKind { PEN, LASSO, NAVIGATION }

enum class LinkedNoteContent { REGION_IMAGE, REGION_TEXT }

data class LinkedNoteTarget(val pageId: UUID, val ordinal: Int)

data class PdfRegionSelection(
  val sourcePageId: UUID,
  val normalizedBounds: Bounds,
  val selectedObjectIds: List<UUID>,
  val screenBounds: Bounds,
)

/**
 * How the hardware tail eraser removes ink: [WHOLE_STROKE] deletes any stroke
 * the disc touches; [POINT] splits strokes at the erased points (spec §33-35).
 * Toggled from the toolbar; applies to the tail eraser regardless of which tip
 * [ToolKind] is active.
 */
enum class EraserMode { WHOLE_STROKE, POINT }

data class PageUiInfo(val id: UUID, val pageNumber: Int, val bookmarked: Boolean, val contentRevision: Long)

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
class PenDrawView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) :
  View(context, attrs, defStyle),
  HvPenDrawListener,
  ToolHost {

  private var penDraw: HvPenDrawManager? = null
  private var penDrawPt: Long = 0
  private var initPenService = false
  private var romPenInkRequested = true
  private var uiInputBlocked = false
  private var toolbarDragBlocked = false

  private val eink = EinkRefreshController(context)

  /** Cache of committed strokes. Regenerable from [page] at any time. */
  private var foreBitmap: Bitmap? = null
  private var bitmapCanvas: Canvas? = null
  private var renderedPageId: UUID? = null
  private var renderedContentRevision: Long = Long.MIN_VALUE
  private var renderedTemplateFingerprint: String = ""

  /** Authoritative multi-page document plus asynchronous persistence (spec §46-56). */
  @Volatile private var persistenceAvailable = true

  @Volatile private var persistenceError: String? = null
  private val repository = NotebookRepository(context.applicationContext)
  private val imageAssets = ImageAssetStore(context.applicationContext)
  private val pdfAssets = PdfAssetStore(context.applicationContext)
  private val pdfImport = PdfImportCoordinator(context.applicationContext, repository, pdfAssets)
  private val pdfEngine = MuPdfEngine()
  private val templateStore = TemplateStore.get(context.applicationContext)
  private var notebook: Notebook = try {
    repository.openOrCreate()
  } catch (t: Throwable) {
    persistenceAvailable = false
    persistenceError = "Notebook recovery failed; editing is in memory only"
    EventLog.log(TAG, "ERROR opening notebook: ${t.javaClass.simpleName}: ${t.message}")
    Notebook().also { it.addPage(Page()) }
  }
  private var page: Page = notebook.pageOrder.asSequence().mapNotNull(notebook::getPage).firstOrNull()
    ?: notebook.pageOrder.firstOrNull()?.let(repository::loadPage)?.also { notebook.attachPage(it) }
    ?: Page().also { notebook.addPage(it) }
  private val pageCache = PageCache(PAGE_CACHE_SIZE)
  private val notebookOperations = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "inknote-notebooks").apply { isDaemon = true }
  }
  private val thumbnails = ThumbnailManager(
    context.cacheDir,
    java.io.File(context.filesDir, "documents"),
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
    affectedPageIds.forEach { id ->
      command.currentPage(id)?.let { changed ->
        thumbnails.invalidate(
          id,
          changed.contentRevision,
          templateStore.visualFingerprint(changed.templateId),
        )
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

  private class RenderContext {
    val renderer = InkRenderer()
    val objectMatrix = Matrix()
    val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
    val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = 0xAA000000.toInt()
      style = Paint.Style.STROKE
      strokeWidth = 2f
    }
  }

  private data class PageRenderKey(
    val notebookId: UUID,
    val pageId: UUID,
    val contentRevision: Long,
    val backgroundRevision: String,
    val width: Int,
    val height: Int,
    val viewportRevision: Int,
  )

  private val pdfViewportStates = ConcurrentHashMap<UUID, PdfViewportState>()
  private data class DocumentLocation(
    val notebookId: UUID,
    val pageId: UUID,
    val viewport: PdfViewportState?,
    val focusedObjectId: UUID?,
    val focusedAnchorId: UUID?,
  )
  private val documentBackStack = ArrayDeque<DocumentLocation>()
  private var visiblePdfAnchors: List<PdfAnchor> = emptyList()
  private var visibleLinkedNoteAnchors: List<PdfAnchor> = emptyList()
  private var highlightedAnchorId: UUID? = null
  private val anchorFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.BLACK
    style = Paint.Style.STROKE
    strokeWidth = 2f
  }
  private val anchorRegionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.BLACK
    style = Paint.Style.STROKE
    strokeWidth = 2f
  }
  private val anchorBadgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    style = Paint.Style.FILL
  }
  private val linkIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.BLACK
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private enum class PdfNavigationGesture { NONE, PAN, ZOOM }
  private var navigationGesture = PdfNavigationGesture.NONE
  private var navigationStartX = 0f
  private var navigationStartY = 0f
  private var navigationCurrentX = 0f
  private var navigationCurrentY = 0f
  private var navigationDeltaX = 0f
  private var navigationDeltaY = 0f
  private var navigationStartState = PdfViewportState()
  private var navigationPreviewState: PdfViewportState? = null
  private val zoomSliderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.BLACK
    strokeWidth = 3f
    style = Paint.Style.STROKE
    textSize = 28f
  }

  private val viewRenderContext = RenderContext()
  private val imageCache = object : android.util.LruCache<String, Bitmap>(IMAGE_CACHE_BYTES) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
  }
  private val pageBitmapCache = object : android.util.LruCache<PageRenderKey, Bitmap>(PAGE_BITMAP_CACHE_BYTES) {
    override fun sizeOf(key: PageRenderKey, value: Bitmap): Int = value.byteCount
  }
  private val pagePreRenderExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "inknote-page-prerender").apply { isDaemon = true }
  }
  private val pagePreRenderInFlight = ConcurrentHashMap.newKeySet<PageRenderKey>()

  @Volatile private var desiredWarmPageIds: Set<UUID> = emptySet()

  @Volatile private var desiredWarmNotebookId: UUID? = notebook.id

  @Volatile private var pagePreRenderClosed = false
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
  private var onPdfRegionSelected: ((PdfRegionSelection) -> Unit)? = null
  private var pdfRegionStart: FloatArray? = null
  private var pdfRegionEnd: FloatArray? = null
  private data class PendingLinkedNotePlacement(
    val sourceNotebookId: UUID,
    val sourcePageId: UUID,
    val notePageId: UUID,
    val normalizedBounds: Bounds,
    val content: LinkedNoteContent,
    val selectedText: String?,
    val preparedImage: ImportedImage?,
    val createdNotePage: Boolean,
  )
  private var pendingLinkedNotePlacement: PendingLinkedNotePlacement? = null

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

  fun importImage(uri: Uri): ImportedImage = imageAssets.commit(imageAssets.import(uri)).also(::warmImageCache)

  fun stageRemoteImage(file: File, mimeType: String): ImportedImage =
    imageAssets.commit(imageAssets.stageFile(file, mimeType)).also(::warmImageCache)

  fun discardImportedImage(image: ImportedImage) {
    imageAssets.discard(image)
  }

  fun placeImage(image: ImportedImage, centerX: Float, centerY: Float): ImageObject =
    screenToPage(centerX, centerY).let { placeImageWithId(image, it[0], it[1], UUID.randomUUID()) }

  private fun placeImageWithId(image: ImportedImage, centerX: Float, centerY: Float, objectId: UUID): ImageObject {
    materialize()
    val committed = imageAssets.commit(image)
    val pageWidth = page.width.takeIf { it > 0f } ?: width.toFloat()
    val pageHeight = page.height.takeIf { it > 0f } ?: height.toFloat()
    val displayWidth = if (committed.exifOrientation in 5..8) committed.pixelHeight else committed.pixelWidth
    val displayHeight = if (committed.exifOrientation in 5..8) committed.pixelWidth else committed.pixelHeight
    val scale = minOf(
      1f,
      pageWidth * 0.4f / displayWidth.coerceAtLeast(1),
      pageHeight * 0.4f / displayHeight.coerceAtLeast(1),
    )
    val now = System.currentTimeMillis()
    val obj = ImageObject(
      id = objectId,
      transform = Transform2D(
        scale,
        0f,
        0f,
        scale,
        centerX - displayWidth * scale / 2f,
        centerY - displayHeight * scale / 2f,
      ),
      zIndex = nextZIndex(), createdAt = now, updatedAt = now,
      assetPath = committed.relativePath, mimeType = committed.mimeType,
      pixelWidth = committed.pixelWidth, pixelHeight = committed.pixelHeight,
      exifOrientation = committed.exifOrientation,
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
    itemId: UUID,
  ): Result<ImageObject> = runCatching {
    repository.findTransferReceipt(sourceDeviceId, itemId)?.let { existing ->
      return@runCatching (page.getObject(existing) as? ImageObject)
        ?: error("传输项目已经提交到其他页面")
    }
    val objectId = UUID.randomUUID()
    pendingTransferReceipt = TransferReceipt(sourceDeviceId, itemId, objectId)
    pendingTransferPersisted = null
    val point = screenToPage(centerX, centerY)
    val placed = placeImageWithId(image, point[0], point[1], objectId)
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
    val point = screenToPage(x, y)
    return placeTextWithId(text, point[0], point[1], UUID.randomUUID())
  }

  fun placeTransferredText(text: String, x: Float, y: Float, sourceDeviceId: String, itemId: UUID): Result<TextObject> =
    runCatching {
      repository.findTransferReceipt(sourceDeviceId, itemId)?.let { existing ->
        return@runCatching (page.getObject(existing) as? TextObject)
          ?: error("传输项目已经提交到其他页面")
      }
      val objectId = UUID.randomUUID()
      pendingTransferReceipt = TransferReceipt(sourceDeviceId, itemId, objectId)
      pendingTransferPersisted = null
      val point = screenToPage(x, y)
      val placed = placeTextWithId(text, point[0], point[1], objectId)
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
      localBounds = measureTextBounds(text, family, size),
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
    fontSize: Float? = null,
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
      updatedAt = System.currentTimeMillis(),
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
    val point = screenToPage(x, y)
    val hit = page.scene.all().asReversed().firstOrNull { obj ->
      (obj is ImageObject || obj is TextObject) && pointInsideObject(obj, point[0], point[1])
    }
    selectedRichObjectId = hit?.id
    postInvalidate()
    onDocChanged?.invoke()
    return hit != null
  }

  fun activatePdfRegionEdit(selection: PdfRegionSelection): Boolean {
    if (page.id != selection.sourcePageId || selection.selectedObjectIds.isEmpty()) return false
    materialize()
    val objects = selection.selectedObjectIds.mapNotNull(page::getObject)
    if (objects.isEmpty()) return false
    if (objects.size == 1 && (objects[0] is ImageObject || objects[0] is TextObject)) {
      selectionTool.clearSelection()
      selectedRichObjectId = objects[0].id
    } else {
      selectedRichObjectId = null
      selectionTool.selectObjectIds(objects.map(PageObject::id))
    }
    postInvalidate()
    onDocChanged?.invoke()
    return true
  }

  fun requestSelectedTextEdit(): Boolean {
    val text = selectedRichObject() as? TextObject ?: return false
    onTextEditRequested?.invoke(text)
    return true
  }

  /** Begins a move/scale/rotate gesture after an object was selected with Side1. */
  fun beginRichObjectGesture(x: Float, y: Float): Boolean {
    val obj = selectedRichObject() ?: return false
    val pagePoint = screenToPage(x, y)
    val pageX = pagePoint[0]
    val pageY = pagePoint[1]
    val corners = objectCorners(obj)
    val handleRadius = currentViewport().screenDistanceToPage(HANDLE_TOUCH_RADIUS)
    val handle = corners.indexOfFirst { point -> distance(pageX, pageY, point[0], point[1]) <= handleRadius }
    val rotate = obj is ImageObject && distanceTo(pageX, pageY, rotationHandle(obj)) <= handleRadius * 1.35f
    richGestureMode = when {
      rotate -> RichGestureMode.ROTATE
      (obj is ImageObject || obj is TextObject) && handle >= 0 -> RichGestureMode.SCALE
      pointInsideObject(obj, pageX, pageY) -> RichGestureMode.MOVE
      else -> return false
    }
    richGestureStartX = pageX
    richGestureStartY = pageY
    richGestureBefore = obj.transform
    val center = objectCenter(obj)
    when (richGestureMode) {
      RichGestureMode.SCALE -> {
        val opposite = corners[handle xor 3]
        richGestureAnchorX = opposite[0]
        richGestureAnchorY = opposite[1]
        richGestureStartValue = hypot(
          (pageX - richGestureAnchorX).toDouble(),
          (pageY - richGestureAnchorY).toDouble(),
        ).toFloat().coerceAtLeast(1f)
      }

      RichGestureMode.ROTATE -> {
        richGestureAnchorX = center[0]
        richGestureAnchorY = center[1]
        richGestureStartValue = atan2(pageY - center[1], pageX - center[0])
      }

      else -> Unit
    }
    richPendingCommand = TransformObjectsCommand(
      page,
      listOf(obj.id),
      mapOf(obj.id to obj.transform),
      mapOf(obj.id to obj.transform),
    )
    setRomPenInkEnabled(false)
    return true
  }

  fun updateRichObjectGesture(x: Float, y: Float) {
    val obj = selectedRichObject() ?: return
    val pagePoint = screenToPage(x, y)
    val pageX = pagePoint[0]
    val pageY = pagePoint[1]
    val delta = when (richGestureMode) {
      RichGestureMode.MOVE -> Transform2D.translate(pageX - richGestureStartX, pageY - richGestureStartY)

      RichGestureMode.SCALE -> {
        val distance = hypot(
          (pageX - richGestureAnchorX).toDouble(),
          (pageY - richGestureAnchorY).toDouble(),
        ).toFloat().coerceAtLeast(1f)
        Transform2D.scaleAbout(
          richGestureAnchorX,
          richGestureAnchorY,
          (distance / richGestureStartValue).coerceIn(0.1f, 10f),
        )
      }

      RichGestureMode.ROTATE -> Transform2D.rotateAbout(
        richGestureAnchorX,
        richGestureAnchorY,
        atan2(pageY - richGestureAnchorY, pageX - richGestureAnchorX) - richGestureStartValue,
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
      obj.transform.mapPoint(b.left, b.top),
      obj.transform.mapPoint(b.right, b.top),
      obj.transform.mapPoint(b.left, b.bottom),
      obj.transform.mapPoint(b.right, b.bottom),
    )
  }

  private fun objectCenter(obj: PageObject): FloatArray = obj.transform.mapPoint(
    (obj.localBounds.left + obj.localBounds.right) / 2f,
    (obj.localBounds.top + obj.localBounds.bottom) / 2f,
  )

  private fun rotationHandle(obj: PageObject): FloatArray {
    val b = obj.localBounds
    val top = obj.transform.mapPoint((b.left + b.right) / 2f, b.top)
    val center = objectCenter(obj)
    val dx = top[0] - center[0]
    val dy = top[1] - center[1]
    val length = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
    val offset = currentViewport().screenDistanceToPage(ROTATE_HANDLE_OFFSET)
    return floatArrayOf(top[0] + dx / length * offset, top[1] + dy / length * offset)
  }

  fun setOnPdfRegionSelected(listener: ((PdfRegionSelection) -> Unit)?) {
    onPdfRegionSelected = listener
  }

  private fun currentViewport(): ViewportTransform = viewportFor(page.id, page.width, page.height, width, height)

  private fun refreshVisiblePdfAnchors() {
    visiblePdfAnchors = if (persistenceAvailable && page.kind == PageKind.PDF_SOURCE) {
      runCatching { repository.pdfAnchors(page.id) }.getOrDefault(emptyList())
    } else {
      emptyList()
    }
    visibleLinkedNoteAnchors = if (persistenceAvailable && page.kind == PageKind.LINKED_NOTE) {
      runCatching { repository.pdfAnchorsForNotePage(page.id) }.getOrDefault(emptyList())
    } else {
      emptyList()
    }
  }

  private fun anchorPageBounds(anchor: PdfAnchor): Bounds = Bounds(
    anchor.normalizedBounds.left * page.width,
    anchor.normalizedBounds.top * page.height,
    anchor.normalizedBounds.right * page.width,
    anchor.normalizedBounds.bottom * page.height,
  )

  private fun viewportFor(
    pageId: UUID,
    pageWidth: Float,
    pageHeight: Float,
    viewWidth: Int,
    viewHeight: Int,
  ): ViewportTransform {
    val base = ViewportTransform.fit(pageWidth, pageHeight, viewWidth, viewHeight)
    val state = pdfViewportStates[pageId] ?: return base
    val scale = base.scale * state.zoom
    return ViewportTransform(
      scale,
      (viewWidth - pageWidth * scale) / 2f + state.panX,
      (viewHeight - pageHeight * scale) / 2f + state.panY,
    )
  }

  private fun screenToPage(x: Float, y: Float): FloatArray = currentViewport().screenToPage(x, y)

  private fun screenPointToPage(point: InkPoint): InkPoint {
    val mapped = screenToPage(point.x, point.y)
    return point.copy(x = mapped[0], y = mapped[1])
  }

  private fun distance(x: Float, y: Float, px: Float, py: Float): Float =
    hypot((x - px).toDouble(), (y - py).toDouble()).toFloat()

  private fun distanceTo(x: Float, y: Float, point: FloatArray): Float = distance(x, y, point[0], point[1])

  private fun measureTextBounds(text: String, family: TextFontFamily, size: Float): Bounds {
    val paint = viewRenderContext.textPaint
    configureTextPaint(paint, family, size)
    val lines = text.split('\n').ifEmpty { listOf("") }
    val metrics = paint.fontMetrics
    val width = lines.maxOfOrNull(paint::measureText)?.coerceAtLeast(1f) ?: 1f
    val height = (metrics.descent - metrics.ascent) * lines.size
    return Bounds(0f, 0f, width, height.coerceAtLeast(1f))
  }

  private fun configureTextPaint(paint: Paint, family: TextFontFamily, size: Float) {
    paint.typeface = Typeface.create(family.androidName, Typeface.NORMAL)
    paint.textSize = size
  }

  init {
    pageCache.put(page)
    refreshVisiblePdfAnchors()
    if (persistenceAvailable) {
      runCatching { imageAssets.cleanupUnreferenced(repository.referencedImageAssets()) }
        .onFailure { EventLog.log(TAG, "asset cleanup skipped: ${it.message}") }
      runCatching { pdfAssets.cleanupUnreferenced(repository.referencedPdfAssets()) }
        .onFailure { EventLog.log(TAG, "PDF asset cleanup skipped: ${it.message}") }
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
  private var eraseGestureOpen = false

  /** Hardware editor commands must not change document/tool state mid gesture. */
  fun isInputGestureActive(): Boolean = gestureOpen || eraseGestureOpen

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
      "created screen=${screenW}x$screenH orgPos=$originPos model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}",
    )
  }

  override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
    super.onSizeChanged(w, h, ow, oh)
    if (w > 0 && h > 0) {
      if (page.kind != PageKind.PDF_SOURCE) {
        val oldRevision = page.contentRevision
        page.updateSize(w.toFloat(), h.toFloat())
        if (page.contentRevision != oldRevision) {
          notebook.refreshPageMetadata(page)
          thumbnails.invalidate(page.id, page.contentRevision)
        }
      }
      createBitmap(w, h)
      initPenDraw()
      scheduleSave(DocumentChange.fullPage(notebook, page, "page:size"))
      warmAdjacentPages(currentPageIndex())
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
    summary.cover?.let { thumbnails.get(it.id, it.contentRevision, backgroundRevision(it)) }

  fun requestNotebookCovers(summaries: List<NotebookSummary>) {
    summaries.forEach { summary ->
      val metadata = summary.cover ?: return@forEach
      val key = ThumbnailKey(metadata.id, metadata.contentRevision, backgroundRevision(metadata))
      val loaded = notebook.getPage(metadata.id)
      if (loaded != null) {
        requestThumbnail(summary.id, PageSnapshot.capture(loaded))
      } else {
        thumbnails.request(
          key,
          snapshotProvider = { repository.loadPage(metadata.id)?.let(PageSnapshot::capture) },
          backgroundProvider = pageThumbnailBackground(summary.id, metadata),
        ) { onDocChanged?.invoke() }
      }
    }
  }

  fun currentPageToEndIds(): Set<UUID> = notebook.pageOrder.drop(currentPageIndex()).toSet()

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

  fun createBlankNotebook(title: String, templateId: String = DEFAULT_TEMPLATE_ID, onComplete: (Result<UUID>) -> Unit) {
    runNotebookOperation(onComplete) {
      val id = repository.createBlankNotebook(title, width.toFloat(), height.toFloat(), templateId)
      repository.loadNotebook(id) ?: error("无法加载新笔记本")
    }
  }

  fun importPdf(uri: Uri, onComplete: (Result<UUID>) -> Unit) {
    runNotebookOperation(onComplete) {
      val id = pdfImport.importLocalBlocking(uri)
      repository.loadNotebook(id) ?: error("无法加载导入的 PDF")
    }
  }

  fun importPdfFile(file: File, displayName: String, onComplete: (Result<UUID>) -> Unit) {
    runNotebookOperation(onComplete) {
      val id = pdfImport.importFileBlocking(file, displayName)
      repository.loadNotebook(id) ?: error("无法加载导入的 PDF")
    }
  }

  fun transferPagesToNewNotebook(selectedPageIds: Set<UUID>, title: String, onComplete: (Result<UUID>) -> Unit) {
    val sourceNotebookId = notebook.id
    runNotebookOperation(onComplete) {
      val transfer = repository.transferPagesToNewNotebook(sourceNotebookId, selectedPageIds, title)
      repository.loadNotebook(transfer.targetNotebookId) ?: error("无法加载新笔记本")
    }
  }

  fun deleteNotebook(id: UUID, onComplete: (Result<UUID>) -> Unit) {
    runNotebookOperation(onComplete) {
      val pdf = repository.pdfDocument(id)
      val deletion = repository.deleteNotebook(id)
      deletion.deletedPageIds.forEach(thumbnails::delete)
      pdf?.let { record -> pdfAssets.resolve(record.assetPath)?.delete() }
      repository.loadNotebook(deletion.activeNotebookId) ?: error("无法加载删除后的笔记本")
    }
  }

  private fun runNotebookOperation(onComplete: (Result<UUID>) -> Unit, operation: () -> Notebook) {
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
    val initialId = repository.lastOpenedPageId(target.id) ?: target.pageOrder.firstOrNull()
      ?: error("笔记本没有页面")
    val initial = target.getPage(initialId) ?: repository.loadPage(initialId)
      ?: error("无法加载上次停留页面")
    target.attachPage(initial)
    val notebookChanged = notebook.id != target.id
    notebook = target
    if (notebookChanged) documentBackStack.clear()
    pageCache.clear()
    pageBitmapCache.evictAll()
    pagePreRenderInFlight.clear()
    desiredWarmPageIds = emptySet()
    desiredWarmNotebookId = target.id
    page = initial
    refreshVisiblePdfAnchors()
    pageCache.put(initial)
    commandStack.clear()
    if (width > 0 && height > 0 && (page.width <= 0f || page.height <= 0f)) {
      page.updateSize(width.toFloat(), height.toFloat())
      notebook.refreshPageMetadata(page)
      thumbnails.invalidate(page.id, page.contentRevision)
      scheduleSave(DocumentChange.fullPage(notebook, page, "page:size"))
    }
    rebuildTools()
    warmAdjacentPages(target.pageOrder.indexOf(initialId).coerceAtLeast(0))
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
    thumbnails.get(id, metadata.contentRevision, backgroundRevision(metadata))
  }
  fun canDeletePage(): Boolean = notebook.pageOrder.size > 1
  fun persistenceWarning(): String? = persistenceError
  fun currentPageBookmarked(): Boolean = page.bookmarked
  fun currentPageKind(): PageKind = page.kind
  fun currentPageSize(): Pair<Float, Float> = page.width to page.height
  fun currentPageTemplateId(): String? = page.templateId
  fun templateCatalog(): TemplateCatalogSnapshot = templateStore.snapshot()

  fun templatePreview(templateId: String, targetWidth: Int, targetHeight: Int): Bitmap? =
    templateStore.resolve(templateId)?.let { definition ->
      templateStore.renderer.renderOwned(definition, targetWidth, targetHeight)
    }

  fun refreshTemplates(force: Boolean = false): TemplateCatalogSnapshot {
    val before = templateStore.snapshot().generation
    val updated = templateStore.refresh()
    if (force || updated.generation != before) {
      pageBitmapCache.evictAll()
      notebook.allPageMetadata().forEach { metadata ->
        thumbnails.invalidate(metadata.id, metadata.contentRevision, backgroundRevision(metadata))
      }
      redrawAll()
      requestThumbnail(page)
      onDocChanged?.invoke()
    }
    return updated
  }

  fun setCurrentPageTemplate(templateId: String): Boolean {
    if (page.kind == PageKind.PDF_SOURCE) return false
    val definition = templateStore.resolve(templateId) ?: return false
    if (!definition.isCompatible(page.width, page.height)) return false
    if (page.templateId == templateId) return true
    materialize()
    commandStack.execute(SetPageTemplateCommand(page, templateId))
    notebook.refreshPageMetadata(page)
    thumbnails.invalidate(page.id, page.contentRevision, backgroundRevision(page.toMetadataForTemplate()))
    pageBitmapCache.evictAll()
    redrawAll()
    requestThumbnail(page)
    onDocChanged?.invoke()
    return true
  }

  private fun scheduleSave(change: DocumentChange) {
    if (persistenceAvailable) autosave.schedule(change)
  }

  fun switchToPage(index: Int): Boolean {
    if (index !in notebook.pageOrder.indices) return false
    if (index == currentPageIndex()) return true
    materialize()
    requestThumbnail(page)
    cacheCurrentPageBitmap()
    activatePage(index)
    return true
  }

  fun switchToPage(id: UUID): Boolean = switchToPage(notebook.pageOrder.indexOf(id))

  fun canNavigateDocumentBack(): Boolean = documentBackStack.isNotEmpty()

  /** Claims an ordinary pen tap only when it starts on a visible Link icon. */
  fun beginLinkedNavigationIconTap(screenX: Float, screenY: Float): Boolean {
    if (!isLinkedNavigationIconHit(screenX, screenY)) return false
    setRomPenInkEnabled(false)
    return true
  }

  fun isLinkedNavigationIconHit(screenX: Float, screenY: Float): Boolean = linkedAnchorAtIcon(screenX, screenY) != null

  /** Completes the dedicated Link-icon gesture without sharing Side1 image editing. */
  fun endLinkedNavigationIconTap(screenX: Float, screenY: Float, cancelled: Boolean): Boolean {
    val navigated = !cancelled && navigateLinkedContentAt(screenX, screenY)
    setRomPenInkEnabled(true)
    return navigated
  }

  /** Link-icon activation for either a PDF region or its linked-note screenshot. */
  fun navigateLinkedContentAt(screenX: Float, screenY: Float): Boolean {
    materialize()
    return when (page.kind) {
      PageKind.PDF_SOURCE -> {
        val anchor = linkedAnchorAtIcon(screenX, screenY) ?: return false
        pushDocumentLocation(focusedAnchorId = anchor.id)
        if (!switchToPage(anchor.notePageId)) {
          documentBackStack.pollLast()
          false
        } else {
          selectedRichObjectId = null
          highlightedAnchorId = null
          postInvalidate()
          onDocChanged?.invoke()
          true
        }
      }

      PageKind.LINKED_NOTE -> {
        val anchor = linkedAnchorAtIcon(screenX, screenY) ?: return false
        pushDocumentLocation(focusedObjectId = null, focusedAnchorId = null)
        if (!switchToPage(anchor.sourcePageId)) {
          documentBackStack.pollLast()
          false
        } else {
          highlightAnchor(anchor.id)
          onDocChanged?.invoke()
          true
        }
      }

      PageKind.BLANK -> false
    }
  }

  private fun linkedAnchorAtIcon(screenX: Float, screenY: Float): PdfAnchor? {
    val density = resources.displayMetrics.density
    val hitRadius = ANCHOR_HIT_RADIUS_DP * density
    val viewport = currentViewport()
    val radius = viewport.screenDistanceToPage(LINK_ICON_RADIUS_DP * density)
    val candidates = when (page.kind) {
      PageKind.PDF_SOURCE -> visiblePdfAnchors
      PageKind.LINKED_NOTE -> visibleLinkedNoteAnchors
      PageKind.BLANK -> return null
    }
    return candidates.asReversed().firstOrNull { candidate ->
      val iconPage = when (page.kind) {
        PageKind.PDF_SOURCE -> anchorBadgePoint(candidate, radius)

        PageKind.LINKED_NOTE -> page.getObject(candidate.noteObjectId)?.let {
          linkedNoteBadgePoint(it, radius)
        } ?: return@firstOrNull false

        PageKind.BLANK -> return@firstOrNull false
      }
      val icon = viewport.pageToScreen(iconPage[0], iconPage[1])
      distance(screenX, screenY, icon[0], icon[1]) <= hitRadius
    }
  }

  fun navigateDocumentBack(): Boolean {
    while (documentBackStack.isNotEmpty()) {
      val location = documentBackStack.removeLast()
      if (location.notebookId != notebook.id || location.pageId !in notebook.pageOrder) continue
      if (!switchToPage(location.pageId)) continue
      if (page.kind == PageKind.PDF_SOURCE && location.viewport != null) {
        pdfViewportStates[page.id] = clampViewportState(
          location.viewport.copy(revision = location.viewport.revision + 1),
        )
        renderedPageId = null
        redrawAll()
      }
      selectedRichObjectId = location.focusedObjectId?.takeIf { page.getObject(it) != null }
      location.focusedAnchorId?.let(::highlightAnchor)
      postInvalidate()
      onDocChanged?.invoke()
      return true
    }
    onDocChanged?.invoke()
    return false
  }

  private fun pushDocumentLocation(
    focusedObjectId: UUID? = selectedRichObjectId,
    focusedAnchorId: UUID? = highlightedAnchorId,
  ) {
    if (documentBackStack.size >= MAX_DOCUMENT_HISTORY) documentBackStack.removeFirst()
    documentBackStack.addLast(
      DocumentLocation(
        notebookId = notebook.id,
        pageId = page.id,
        viewport = if (page.kind == PageKind.PDF_SOURCE) {
          pdfViewportStates[page.id] ?: PdfViewportState()
        } else {
          null
        },
        focusedObjectId = focusedObjectId,
        focusedAnchorId = focusedAnchorId,
      ),
    )
    onDocChanged?.invoke()
  }

  private fun highlightAnchor(anchorId: UUID) {
    highlightedAnchorId = anchorId
    postInvalidate()
    handler.postDelayed({
      if (highlightedAnchorId == anchorId) {
        highlightedAnchorId = null
        postInvalidate()
      }
    }, ANCHOR_HIGHLIGHT_MS)
  }

  fun navigatePage(delta: Int): Boolean {
    val current = currentPageIndex()
    val target = if (notebook.kind == NotebookKind.PDF && !studyNavigation) {
      generateSequence(current + delta) { it + delta }
        .takeWhile { it in notebook.pageOrder.indices }
        .firstOrNull { index -> notebook.metadata(notebook.pageOrder[index])?.kind != PageKind.LINKED_NOTE }
        ?: if (delta > 0) notebook.pageOrder.size else -1
    } else {
      current + delta
    }
    if (target !in notebook.pageOrder.indices) {
      if (delta > 0 && autoCreatePageOnNextAtEnd && notebook.kind != NotebookKind.PDF) {
        addPage()
        return true
      }
      return false
    }
    return switchToPage(target)
  }

  /** Updated by the Compose settings surface and read by page-turn overlays. */
  var autoCreatePageOnNextAtEnd: Boolean = false
  private var studyNavigation: Boolean = true

  fun isStudyNavigation(): Boolean = studyNavigation

  fun toggleStudyNavigation(): Boolean {
    studyNavigation = !studyNavigation
    onDocChanged?.invoke()
    return studyNavigation
  }

  fun addPage(): UUID = addPageAfter(page.id, activate = true)

  fun addPageAfter(afterPageId: UUID, activate: Boolean): UUID {
    if (activate) {
      materialize()
      requestThumbnail(page)
      cacheCurrentPageBitmap()
    } else if (gestureOpen) {
      finishGesture()
    }
    val afterIndex = notebook.pageOrder.indexOf(afterPageId).takeIf { it >= 0 }
      ?: currentPageIndex()
    val insertAt = afterIndex + 1
    val afterMetadata = notebook.metadata(notebook.pageOrder[afterIndex])
    val parentPdfPageId = when (afterMetadata?.kind) {
      PageKind.PDF_SOURCE -> afterMetadata.id
      PageKind.LINKED_NOTE -> afterMetadata.parentPdfPageId
      else -> null
    }
    val newTemplateId = inheritedTemplateId(afterMetadata)
    val newPage = if (notebook.kind == NotebookKind.PDF) {
      requireNotNull(parentPdfPageId) { "PDF 笔记本只能在源页后添加夹纸" }
      Page(
        width = width.toFloat(),
        height = height.toFloat(),
        kind = PageKind.LINKED_NOTE,
        parentPdfPageId = parentPdfPageId,
        templateId = newTemplateId,
      )
    } else {
      Page(width = width.toFloat(), height = height.toFloat(), templateId = newTemplateId)
    }
    commandStack.execute(AddPageCommand(notebook, newPage, insertAt))
    pageCache.put(newPage)
    if (activate) {
      activatePage(insertAt)
    } else {
      warmAdjacentPages(currentPageIndex())
      onDocChanged?.invoke()
    }
    return newPage.id
  }

  fun deleteCurrentPage(): Boolean = deletePage(page.id)

  fun deletePage(pageId: UUID): Boolean {
    if (!canDeletePage() || pageId !in notebook.pageOrder) return false
    if (notebook.metadata(pageId)?.kind == PageKind.PDF_SOURCE) {
      emitNotice("PDF 源页不能删除")
      return false
    }
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
      warmAdjacentPages(currentPageIndex())
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
    if (notebook.kind == NotebookKind.PDF) {
      emitNotice("PDF 源页与夹纸的顺序由关联关系维护")
      return false
    }
    val from = notebook.pageOrder.indexOf(pageId)
    if (from < 0) return false
    val target = targetIndex.coerceIn(0, notebook.pageOrder.lastIndex)
    if (target == from) return true
    commandStack.execute(MovePageCommand(notebook, pageId, target))
    warmAdjacentPages(currentPageIndex())
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
      val key = ThumbnailKey(id, metadata.contentRevision, backgroundRevision(metadata))
      val loaded = notebook.getPage(id)
      if (loaded != null) {
        requestThumbnail(notebook.id, PageSnapshot.capture(loaded))
      } else {
        thumbnails.request(
          key,
          snapshotProvider = { repository.loadPage(id)?.let(PageSnapshot::capture) },
          backgroundProvider = pageThumbnailBackground(notebook.id, metadata),
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
    if (persistenceAvailable) {
      runCatching { repository.setLastOpenedPage(notebook.id, page.id) }
        .onFailure { EventLog.log(TAG, "last-page save failed: ${it.message}") }
    }
    selectedRichObjectId = null
    refreshVisiblePdfAnchors()
    if (width > 0 && height > 0 && (page.width <= 0f || page.height <= 0f)) {
      page.updateSize(width.toFloat(), height.toFloat())
      notebook.refreshPageMetadata(page)
      thumbnails.invalidate(page.id, page.contentRevision)
    }
    rebuildTools()
    showPageBitmap(target)
    warmAdjacentPages(index)
    clearOverlayInk()
    requestThumbnail(page)
    onDocChanged?.invoke()
    EventLog.log(TAG, "page ${index + 1}/${notebook.pageOrder.size} id=$id")
  }

  private fun rebuildTools() {
    penTool = PenTool(page, commandStack, this) {
      penStyle.copy(baseWidth = currentViewport().screenDistanceToPage(penStyle.baseWidth))
    }
    strokeEraserTool = StrokeEraserTool(page, commandStack, this) {
      currentViewport().screenDistanceToPage(eraserWidth / 2.0f)
    }
    pointEraserTool = PointEraserTool(page, commandStack, this) {
      currentViewport().screenDistanceToPage(eraserWidth / 2.0f)
    }
    selectionTool = SelectionTool(page, commandStack, this) {
      currentViewport().screenDistanceToPage(HANDLE_TOUCH_RADIUS)
    }
    currentTool = when (toolKind) {
      ToolKind.PEN -> penTool
      ToolKind.LASSO -> selectionTool
      ToolKind.NAVIGATION -> selectionTool
    }
  }

  /** Keeps the current/previous/next Scene window resident and rasterizes neighbors off-thread. */
  private fun warmAdjacentPages(index: Int) {
    val desired = (index - 1..index + 1)
      .filter { it in notebook.pageOrder.indices }
      .map { notebook.pageOrder[it] }
      .toSet()
    desiredWarmNotebookId = notebook.id
    desiredWarmPageIds = desired
    pageCache.retain(desired).forEach { evicted ->
      if (evicted.id != page.id) notebook.detachPage(evicted.id)
    }
    for (id in desired) {
      if (id != page.id) schedulePagePreRender(id)
    }
  }

  private fun schedulePagePreRender(id: UUID) {
    val metadata = notebook.metadata(id) ?: return
    val renderWidth = width
    val renderHeight = height
    if (renderWidth <= 0 || renderHeight <= 0) return
    val notebookId = notebook.id
    val viewportRevision = pdfViewportStates[id]?.revision ?: 0
    val key = PageRenderKey(
      notebookId,
      id,
      metadata.contentRevision,
      backgroundRevision(metadata),
      renderWidth,
      renderHeight,
      viewportRevision,
    )
    if (pageBitmapCache.get(key) != null || !pagePreRenderInFlight.add(key)) return
    val residentSnapshot = (notebook.getPage(id) ?: pageCache.get(id))?.let(PageSnapshot::capture)
    pagePreRenderExecutor.execute {
      try {
        if (pagePreRenderClosed || desiredWarmNotebookId != notebookId || id !in desiredWarmPageIds) {
          return@execute
        }
        val loaded = if (residentSnapshot == null) repository.loadPage(id) else null
        val snapshot = residentSnapshot ?: loaded?.let(PageSnapshot::capture) ?: return@execute
        if (pagePreRenderClosed || desiredWarmNotebookId != notebookId ||
          snapshot.metadata.contentRevision != key.contentRevision || id !in desiredWarmPageIds
        ) {
          return@execute
        }
        val viewport = viewportFor(
          snapshot.metadata.id,
          snapshot.metadata.width,
          snapshot.metadata.height,
          renderWidth,
          renderHeight,
        )
        val rendered = renderPageBitmap(notebookId, snapshot, renderWidth, renderHeight, viewport)
        post {
          val currentMetadata = if (notebook.id == notebookId) notebook.metadata(id) else null
          if (!pagePreRenderClosed && desiredWarmNotebookId == notebookId &&
            currentMetadata?.contentRevision == key.contentRevision &&
            currentMetadata?.let(::backgroundRevision) == key.backgroundRevision &&
            id in desiredWarmPageIds
          ) {
            if (loaded != null && notebook.getPage(id) == null) {
              notebook.attachPage(loaded)
              pageCache.put(loaded)?.let { evicted ->
                if (evicted.id != page.id) notebook.detachPage(evicted.id)
              }
            }
            pageBitmapCache.put(key, rendered)
            if (page.id == id) {
              val ready = pageBitmapCache.remove(key)
              if (ready != null) {
                foreBitmap = ready
                bitmapCanvas = Canvas(ready)
                markCurrentPageRendered()
                postInvalidate()
              }
            }
          }
        }
      } finally {
        pagePreRenderInFlight.remove(key)
      }
    }
  }

  private fun currentPageRenderKey(target: Page = page): PageRenderKey? = if (width > 0 && height > 0) {
    PageRenderKey(
      notebook.id,
      target.id,
      target.contentRevision,
      backgroundRevision(target.toMetadataForTemplate()),
      width,
      height,
      pdfViewportStates[target.id]?.revision ?: 0,
    )
  } else {
    null
  }

  private fun cacheCurrentPageBitmap() {
    val key = currentPageRenderKey() ?: return
    val bitmap = foreBitmap ?: return
    if (!bitmap.isRecycled && bitmap.width == key.width && bitmap.height == key.height) {
      pageBitmapCache.put(key, bitmap)
    }
  }

  private fun showPageBitmap(target: Page) {
    val key = currentPageRenderKey(target)
    val prepared = key?.let(pageBitmapCache::remove)?.takeUnless(Bitmap::isRecycled)
    val bitmap = prepared ?: Bitmap.createBitmap(
      width.coerceAtLeast(1),
      height.coerceAtLeast(1),
      Bitmap.Config.ARGB_8888,
    )
    foreBitmap = bitmap
    bitmapCanvas = Canvas(bitmap)
    if (prepared == null) {
      redrawAll()
    } else {
      markCurrentPageRendered()
      postInvalidate()
    }
  }

  private fun renderPageBitmap(
    notebookId: UUID,
    snapshot: PageSnapshot,
    renderWidth: Int,
    renderHeight: Int,
    viewport: ViewportTransform,
  ): Bitmap {
    val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    if (snapshot.metadata.kind == PageKind.PDF_SOURCE) {
      drawPdfBackground(canvas, notebookId, snapshot.metadata, viewport)
    } else {
      drawTemplateBackground(canvas, snapshot.metadata, viewport)
    }
    val renderContext = RenderContext()
    canvas.withTranslation(viewport.offsetX, viewport.offsetY) {
      canvas.scale(viewport.scale, viewport.scale)
      snapshot.objects.forEach { drawObject(canvas, it, renderContext) }
    }
    return bitmap
  }

  private fun drawPdfBackground(
    canvas: Canvas,
    notebookId: UUID,
    metadata: Notebook.PageMetadata,
    viewport: ViewportTransform,
  ) {
    val source = metadata.pdfSource ?: return
    val record = repository.pdfDocument(notebookId) ?: return
    val file = pdfAssets.resolve(record.assetPath) ?: return
    val targetWidth = (metadata.width * viewport.scale).toInt().coerceAtLeast(1)
    val rendered = pdfEngine.renderPage(file, source.sourcePageIndex, targetWidth)
    try {
      val target = RectF(
        viewport.offsetX,
        viewport.offsetY,
        viewport.offsetX + metadata.width * viewport.scale,
        viewport.offsetY + metadata.height * viewport.scale,
      )
      canvas.drawBitmap(rendered, null, target, null)
    } finally {
      rendered.recycle()
    }
  }

  private fun drawTemplateBackground(canvas: Canvas, metadata: Notebook.PageMetadata, viewport: ViewportTransform) {
    val definition = templateStore.resolve(metadata.templateId) ?: return
    if (!definition.isCompatible(metadata.width, metadata.height)) return
    canvas.withTranslation(viewport.offsetX, viewport.offsetY) {
      canvas.scale(viewport.scale, viewport.scale)
      templateStore.renderer.draw(
        canvas,
        definition,
        RectF(0f, 0f, metadata.width.coerceAtLeast(1f), metadata.height.coerceAtLeast(1f)),
      )
    }
  }

  private fun requestThumbnail(target: Page) {
    requestThumbnail(notebook.id, PageSnapshot.capture(target))
  }

  private fun requestThumbnail(notebookId: UUID, snapshot: PageSnapshot) {
    thumbnails.request(
      snapshot,
      backgroundRevision = backgroundRevision(snapshot.metadata),
      backgroundProvider = pageThumbnailBackground(notebookId, snapshot.metadata),
    ) { onDocChanged?.invoke() }
  }

  /** MuPDF background used by PDF page thumbnails and, in particular, PDF notebook covers. */
  private fun pdfThumbnailBackground(notebookId: UUID, metadata: Notebook.PageMetadata): (() -> Bitmap?)? {
    val source = metadata.pdfSource ?: return null
    return {
      val record = repository.pdfDocument(notebookId)
      val file = record?.let { pdfAssets.resolve(it.assetPath) }
      file?.let {
        pdfEngine.renderPage(
          it,
          source.sourcePageIndex,
          ThumbnailManager.WIDTH - 8,
        )
      }
    }
  }

  private fun pageThumbnailBackground(notebookId: UUID, metadata: Notebook.PageMetadata): (() -> Bitmap?)? {
    if (metadata.kind == PageKind.PDF_SOURCE) return pdfThumbnailBackground(notebookId, metadata)
    val definition = templateStore.resolve(metadata.templateId) ?: return null
    if (!definition.isCompatible(metadata.width, metadata.height)) return null
    return {
      val targetWidth = ThumbnailManager.WIDTH - 16
      val targetHeight = (targetWidth * metadata.height / metadata.width).toInt().coerceAtLeast(1)
      templateStore.renderer.renderOwned(definition, targetWidth, targetHeight)
    }
  }

  private fun backgroundRevision(metadata: Notebook.PageMetadata): String =
    if (metadata.kind == PageKind.PDF_SOURCE) {
      "pdf:${metadata.pdfSource?.sourcePageIndex ?: -1}"
    } else {
      templateStore.visualFingerprint(metadata.templateId)
    }

  private fun Page.toMetadataForTemplate() = Notebook.PageMetadata(
    id, width, height, bookmarked, contentRevision, createdAt, updatedAt,
    kind, parentPdfPageId, pdfSource, templateId,
  )

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
      penDraw!!.enablePen(penDrawPt, romPenInkRequested && !uiInputBlocked && !toolbarDragBlocked)
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
    val point = screenToPage(x, y)
    currentTool.onDown(point[0], point[1])
    if (toolKind != ToolKind.PEN) postInvalidate()
  }

  /** Sets the tip-driven tool the toolbar has selected. Any open gesture is finished first. */
  fun setTool(kind: ToolKind) {
    if (gestureOpen) finishGesture()
    toolKind = kind
    currentTool = when (kind) {
      ToolKind.PEN -> penTool
      ToolKind.LASSO -> selectionTool
      ToolKind.NAVIGATION -> selectionTool
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

  /**
   * Drag recognition happens after the stylus has crossed touch slop. Disable the vendor
   * overlay synchronously at that point and reset any dot/short trail it painted while the
   * gesture was being recognised. This block is independent from Compose popup blocking so
   * ending a drag cannot re-enable ink underneath a still-active toolbar touch.
   */
  fun setToolbarDragActive(active: Boolean) {
    if (toolbarDragBlocked == active) return
    toolbarDragBlocked = active
    applyRomPenInkState(resetData = true)
    if (active) clearOverlayInk()
    EventLog.log(TAG, "toolbar drag blocked=$active")
  }

  /** Reasserts the vendor pen service after Android has suspended the process. */
  fun recoverAfterWake() {
    if (uiInputBlocked || toolbarDragBlocked || width <= 0 || height <= 0) return
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
    selectionTool.onBatch(samples.map(::screenPointToPage))
    invalidateToolOverlay()
  }

  /** Completes or cancels the overlay-driven lasso gesture and restores ROM ink. */
  fun endLasso(cancelled: Boolean) {
    if (toolKind != ToolKind.LASSO || !gestureOpen) return
    if (!cancelled) {
      finishGesture()
      emitCompletedPdfSelection()
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
        sysX,
        sysY,
        loc[0],
        loc[1],
        rotation,
        originPos,
        screenW,
        screenH,
      )

      batch.add(
        screenPointToPage(
          InkPoint(
            x = p[0],
            y = p[1],
            pressure = normalizePressure(rawPressure),
            timestamp = now,
          )
        ),
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
        "xy=(${"%.0f".format(first?.x ?: 0f)},${"%.0f".format(first?.y ?: 0f)})",
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
    val enabled = romPenInkRequested && !uiInputBlocked && !toolbarDragBlocked
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
      dirty.left.toFloat(),
      dirty.top.toFloat(),
      dirty.right.toFloat(),
      dirty.bottom.toFloat(),
    )
    canvas.withClip(dirty) {
      canvas.drawColor(android.graphics.Color.WHITE)
      drawTemplateBackground(canvas, page.toMetadataForTemplate(), currentViewport())
      for (obj in page.scene.all()) {
        if (obj.pageBounds.intersects(testBounds)) drawObject(canvas, obj, viewRenderContext)
      }
    }
    markCurrentPageRendered()
    postInvalidate(dirty.left, dirty.top, dirty.right, dirty.bottom)
  }

  /** Draws one [PageObject] with its [Transform2D] applied (spec §10, §38-39). */
  private fun drawObject(canvas: Canvas, obj: PageObject, renderContext: RenderContext) {
    val t = obj.transform
    renderContext.objectMatrix.setValues(floatArrayOf(t.a, t.c, t.tx, t.b, t.d, t.ty, 0f, 0f, 1f))
    canvas.withMatrix(renderContext.objectMatrix) {
      when (obj) {
        is StrokeObject -> renderContext.renderer.drawStroke(canvas, obj.stroke)
        is ImageObject -> drawImageObject(canvas, obj, renderContext)
        is TextObject -> drawTextObject(canvas, obj, renderContext)
      }
    }
  }

  private fun drawImageObject(canvas: Canvas, obj: ImageObject, renderContext: RenderContext) {
    val bitmap = imageCache.get(obj.assetPath) ?: decodeImage(obj)?.also {
      imageCache.put(obj.assetPath, it)
    }
    val rawTarget = RectF(0f, 0f, obj.pixelWidth.toFloat(), obj.pixelHeight.toFloat())
    val displayTarget = RectF(
      obj.localBounds.left,
      obj.localBounds.top,
      obj.localBounds.right,
      obj.localBounds.bottom,
    )
    if (bitmap != null) {
      canvas.withMatrix(exifMatrix(obj)) {
        canvas.drawBitmap(bitmap, null, rawTarget, renderContext.imagePaint)
      }
    } else {
      canvas.drawRect(displayTarget, renderContext.placeholderPaint)
      canvas.drawLine(
        displayTarget.left,
        displayTarget.top,
        displayTarget.right,
        displayTarget.bottom,
        renderContext.placeholderPaint,
      )
      canvas.drawLine(
        displayTarget.right,
        displayTarget.top,
        displayTarget.left,
        displayTarget.bottom,
        renderContext.placeholderPaint,
      )
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

  private fun decodeImage(obj: ImageObject): Bitmap? =
    decodeImage(obj.assetPath, obj.pixelWidth, obj.pixelHeight, obj.mimeType)

  /** Called by the import IO path so the first placement never decodes on the UI thread. */
  private fun warmImageCache(image: ImportedImage) {
    if (imageCache.get(image.relativePath) != null) return
    decodeImage(image.relativePath, image.pixelWidth, image.pixelHeight, image.mimeType)?.let {
      imageCache.put(image.relativePath, it)
    }
  }

  private fun decodeImage(assetPath: String, pixelWidth: Int, pixelHeight: Int, mimeType: String): Bitmap? {
    val file = imageAssets.resolve(assetPath) ?: return null
    val maxDimension = maxOf(pixelWidth, pixelHeight)
    var sample = 1
    while (maxDimension / sample > MAX_DECODE_DIMENSION) sample *= 2
    return BitmapFactory.decodeFile(
      file.absolutePath,
      BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = if (mimeType == "image/jpeg" || mimeType == "image/jpg") {
          Bitmap.Config.RGB_565
        } else {
          Bitmap.Config.ARGB_8888
        }
      },
    )
  }

  private fun drawTextObject(canvas: Canvas, obj: TextObject, renderContext: RenderContext) {
    val paint = renderContext.textPaint
    configureTextPaint(paint, obj.fontFamily, obj.fontSize)
    val metrics = paint.fontMetrics
    val lineHeight = metrics.descent - metrics.ascent
    var baseline = -metrics.ascent
    obj.text.split('\n').forEach { line ->
      canvas.drawText(line, 0f, baseline, paint)
      baseline += lineHeight
    }
  }

  // -- ToolHost (spec §17): tools call back into the view to repaint or update selection UI --

  override fun requestRepaint(bounds: Bounds) {
    if (page.kind == PageKind.PDF_SOURCE) {
      redrawAll()
    } else {
      val screenBounds = currentViewport().pageToScreen(bounds)
      repaintRegion(InkRenderer.dirtyRect(screenBounds, penStyle))
    }
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

  private fun activeEraserTool(): EraserTool = if (eraserMode == EraserMode.POINT) pointEraserTool else strokeEraserTool

  /** Erase gesture starting: stop the ROM painting pen ink while erasing. */
  fun beginErase() {
    eraseGestureOpen = true
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
    val point = screenToPage(x, y)
    activeEraserTool().eraseAt(point[0], point[1])
  }

  /** Erase gesture ended: commit the accumulated erase as one undo step, let the ROM paint ink again. */
  fun endErase() {
    eraseGestureOpen = false
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
    val preview = navigationPreviewState
    if (preview != null) {
      c.drawColor(android.graphics.Color.WHITE)
    }
    foreBitmap?.let { bitmap ->
      if (preview == null) {
        c.drawBitmap(bitmap, 0f, 0f, null)
      } else {
        val oldViewport = viewportForState(navigationStartState)
        val newViewport = viewportForState(preview)
        val ratio = newViewport.scale / oldViewport.scale
        val tx = newViewport.offsetX - oldViewport.offsetX * ratio
        val ty = newViewport.offsetY - oldViewport.offsetY * ratio
        c.withTranslation(tx, ty) {
          c.scale(ratio, ratio)
          c.drawBitmap(bitmap, 0f, 0f, null)
        }
      }
    }
    drawToolOverlay(c)
    if (navigationGesture == PdfNavigationGesture.ZOOM) drawZoomSlider(c)
  }

  /**
   * Draws the transient lasso affordances on top of the document bitmap: the
   * dashed marquee while lassoing, and the dashed selection box with corner
   * handles once a selection exists. Never touches [foreBitmap], so it is not
   * part of the document and clears on the next repaint / at materialize. The
   * eraser deliberately has no cursor overlay (see the overlay state comment).
   */
  private fun drawToolOverlay(c: Canvas) {
    val viewport = currentViewport()
    c.withTranslation(viewport.offsetX, viewport.offsetY) {
      c.scale(viewport.scale, viewport.scale)
      val oldMarqueeWidth = marqueePaint.strokeWidth
      marqueePaint.strokeWidth = oldMarqueeWidth / viewport.scale
      val handleRadius = viewport.screenDistanceToPage(HANDLE_TOUCH_RADIUS)
      if (navigationPreviewState == null && page.kind == PageKind.PDF_SOURCE) {
        drawPdfAnchors(c, viewport)
      }
      if (page.kind == PageKind.LINKED_NOTE) {
        drawLinkedNoteAnchors(c, viewport)
      }
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
          val h = handleRadius
          for (corner in arrayOf(
            b.left to b.top,
            b.right to b.top,
            b.left to b.bottom,
            b.right to b.bottom,
          )) {
            c.drawRect(
              corner.first - h,
              corner.second - h,
              corner.first + h,
              corner.second + h,
              handlePaint,
            )
          }
        }
      }
      val regionStart = pdfRegionStart
      val regionEnd = pdfRegionEnd
      if (regionStart != null && regionEnd != null) {
        c.drawRect(
          minOf(regionStart[0], regionEnd[0]),
          minOf(regionStart[1], regionEnd[1]),
          maxOf(regionStart[0], regionEnd[0]),
          maxOf(regionStart[1], regionEnd[1]),
          marqueePaint,
        )
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
        if (obj is ImageObject || obj is TextObject) {
          corners.forEach { point ->
            c.drawCircle(point[0], point[1], handleRadius, handlePaint)
          }
        }
        if (obj is ImageObject) {
          val b = obj.localBounds
          val top = obj.transform.mapPoint((b.left + b.right) / 2f, b.top)
          val rotate = rotationHandle(obj)
          c.drawLine(top[0], top[1], rotate[0], rotate[1], marqueePaint)
          c.drawCircle(rotate[0], rotate[1], handleRadius, handlePaint)
        }
      }
      marqueePaint.strokeWidth = oldMarqueeWidth
    }
  }

  private fun drawPdfAnchors(canvas: Canvas, viewport: ViewportTransform) {
    if (visiblePdfAnchors.isEmpty()) return
    val density = resources.displayMetrics.density
    val radius = viewport.screenDistanceToPage(LINK_ICON_RADIUS_DP * density)
    val oldRegionWidth = anchorRegionPaint.strokeWidth
    val dash = viewport.screenDistanceToPage(8f * density)
    anchorRegionPaint.pathEffect = DashPathEffect(floatArrayOf(dash, dash * 0.75f), 0f)
    visiblePdfAnchors.forEach { anchor ->
      val bounds = anchorPageBounds(anchor)
      anchorRegionPaint.strokeWidth = viewport.screenDistanceToPage(
        if (anchor.id == highlightedAnchorId) 4f else 2f,
      )
      canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, anchorRegionPaint)
      val badge = anchorBadgePoint(anchor, radius)
      val badgeX = badge[0]
      val badgeY = badge[1]
      canvas.drawCircle(badgeX, badgeY, radius, anchorBadgeFillPaint)
      canvas.drawCircle(badgeX, badgeY, radius, anchorFramePaint)
      drawLinkIcon(canvas, badgeX, badgeY, radius, viewport)
    }
    anchorRegionPaint.strokeWidth = oldRegionWidth
  }

  /** Mirrors the PDF badge on each pasted screenshot so the link is visible on both ends. */
  private fun drawLinkedNoteAnchors(canvas: Canvas, viewport: ViewportTransform) {
    if (visibleLinkedNoteAnchors.isEmpty()) return
    val density = resources.displayMetrics.density
    val radius = viewport.screenDistanceToPage(LINK_ICON_RADIUS_DP * density)
    visibleLinkedNoteAnchors.forEach { anchor ->
      val obj = page.getObject(anchor.noteObjectId) ?: return@forEach
      val badge = linkedNoteBadgePoint(obj, radius)
      val badgeX = badge[0]
      val badgeY = badge[1]
      canvas.drawCircle(badgeX, badgeY, radius, anchorBadgeFillPaint)
      canvas.drawCircle(badgeX, badgeY, radius, anchorFramePaint)
      drawLinkIcon(canvas, badgeX, badgeY, radius, viewport)
    }
  }

  private fun drawLinkIcon(canvas: Canvas, centerX: Float, centerY: Float, radius: Float, viewport: ViewportTransform) {
    val oldWidth = linkIconPaint.strokeWidth
    linkIconPaint.strokeWidth = viewport.screenDistanceToPage(2f * resources.displayMetrics.density)
    canvas.withRotation(-35f, centerX, centerY) {
      val halfHeight = radius * 0.22f
      val round = radius * 0.24f
      canvas.drawRoundRect(
        centerX - radius * 0.64f,
        centerY - halfHeight,
        centerX + radius * 0.08f,
        centerY + halfHeight,
        round,
        round,
        linkIconPaint,
      )
      canvas.drawRoundRect(
        centerX - radius * 0.08f,
        centerY - halfHeight,
        centerX + radius * 0.64f,
        centerY + halfHeight,
        round,
        round,
        linkIconPaint,
      )
    }
    linkIconPaint.strokeWidth = oldWidth
  }

  private fun linkedNoteBadgePoint(obj: PageObject, radius: Float): FloatArray {
    val bounds = obj.pageBounds
    return floatArrayOf(
      bounds.right.coerceIn(radius, (page.width - radius).coerceAtLeast(radius)),
      bounds.top.coerceIn(radius, (page.height - radius).coerceAtLeast(radius)),
    )
  }

  private fun anchorBadgePoint(anchor: PdfAnchor, radius: Float): FloatArray {
    val bounds = anchorPageBounds(anchor)
    return floatArrayOf(
      bounds.right.coerceIn(radius, (page.width - radius).coerceAtLeast(radius)),
      bounds.top.coerceIn(radius, (page.height - radius).coerceAtLeast(radius)),
    )
  }

  fun beginPdfRegion(x: Float, y: Float): Boolean {
    if (toolKind != ToolKind.LASSO) return false
    setRomPenInkEnabled(false)
    val point = screenToPage(x, y)
    pdfRegionStart = point
    pdfRegionEnd = point.copyOf()
    postInvalidate()
    return true
  }

  fun beginPdfNavigation(x: Float, y: Float, modifier: PenSideButton): Boolean {
    if (toolKind != ToolKind.NAVIGATION || page.kind != PageKind.PDF_SOURCE) return false
    navigationGesture = when (modifier) {
      PenSideButton.SIDE_1 -> PdfNavigationGesture.PAN
      PenSideButton.SIDE_2 -> PdfNavigationGesture.ZOOM
      else -> return false
    }
    setRomPenInkEnabled(false)
    navigationStartX = x
    navigationStartY = y
    navigationCurrentX = x
    navigationCurrentY = y
    navigationDeltaX = 0f
    navigationDeltaY = 0f
    navigationStartState = pdfViewportStates[page.id] ?: PdfViewportState()
    navigationPreviewState = navigationStartState
    return true
  }

  fun updatePdfNavigation(x: Float, y: Float) {
    if (navigationGesture == PdfNavigationGesture.NONE) return
    navigationCurrentX = x
    navigationCurrentY = y
    navigationDeltaX = x - navigationStartX
    navigationDeltaY = y - navigationStartY
    navigationPreviewState = when (navigationGesture) {
      PdfNavigationGesture.PAN -> clampViewportState(
        navigationStartState.copy(
          panX = navigationStartState.panX + navigationDeltaX,
          panY = navigationStartState.panY + navigationDeltaY,
        ),
      )

      PdfNavigationGesture.ZOOM -> zoomStateAt(
        navigationStartState,
        (navigationStartState.zoom * exp((navigationStartY - y) / ZOOM_DRAG_DISTANCE)).coerceIn(1f, 4f),
        navigationStartX,
        navigationStartY,
      )

      PdfNavigationGesture.NONE -> null
    }
    postInvalidate()
  }

  fun endPdfNavigation(cancelled: Boolean) {
    val preview = navigationPreviewState
    if (!cancelled && preview != null && preview != navigationStartState) {
      pdfViewportStates[page.id] = preview.copy(revision = navigationStartState.revision + 1)
      renderedPageId = null
      redrawAll()
    }
    navigationGesture = PdfNavigationGesture.NONE
    navigationPreviewState = null
    navigationDeltaX = 0f
    navigationDeltaY = 0f
    setRomPenInkEnabled(true)
    postInvalidate()
  }

  fun zoomPdf(factor: Float): Boolean {
    if (page.kind != PageKind.PDF_SOURCE) return false
    val previous = pdfViewportStates[page.id] ?: PdfViewportState()
    val nextZoom = (previous.zoom * factor).coerceIn(1f, 4f)
    if (nextZoom == previous.zoom) return false
    pdfViewportStates[page.id] = previous.copy(zoom = nextZoom, revision = previous.revision + 1)
    renderedPageId = null
    redrawAll()
    return true
  }

  fun fitPdf(): Boolean {
    if (page.kind != PageKind.PDF_SOURCE) return false
    val previous = pdfViewportStates[page.id] ?: PdfViewportState()
    pdfViewportStates[page.id] = PdfViewportState(revision = previous.revision + 1)
    renderedPageId = null
    redrawAll()
    return true
  }

  private fun viewportForState(state: PdfViewportState): ViewportTransform =
    PdfViewportMath.transform(state, page.width, page.height, width, height)

  private fun clampViewportState(state: PdfViewportState): PdfViewportState =
    PdfViewportMath.clamp(state, page.width, page.height, width, height)

  private fun zoomStateAt(start: PdfViewportState, nextZoom: Float, focusX: Float, focusY: Float): PdfViewportState =
    PdfViewportMath.zoomAt(
      start,
      nextZoom,
      focusX,
      focusY,
      page.width,
      page.height,
      width,
      height,
    )

  private fun drawZoomSlider(canvas: Canvas) {
    val zoom = navigationPreviewState?.zoom ?: navigationStartState.zoom
    val density = resources.displayMetrics.density
    val trackHeight = 120f * density
    val x = (navigationCurrentX + 28f * density).coerceAtMost(width - 76f * density)
    val top = (navigationCurrentY + 24f * density)
      .coerceAtMost(height - trackHeight - 48f * density).coerceAtLeast(8f * density)
    val bottom = top + trackHeight
    zoomSliderPaint.textSize = 14f * density
    zoomSliderPaint.strokeWidth = 2f * density
    canvas.drawLine(x, top, x, bottom, zoomSliderPaint)
    val thumbY = bottom - ((zoom - 1f) / 3f) * trackHeight
    canvas.drawCircle(x, thumbY, 8f * density, zoomSliderPaint)
    zoomSliderPaint.style = Paint.Style.FILL
    canvas.drawText("${"%.2f".format(zoom)}×", x + 16f * density, thumbY + 5f * density, zoomSliderPaint)
    zoomSliderPaint.style = Paint.Style.STROKE
  }

  fun updatePdfRegion(x: Float, y: Float) {
    if (pdfRegionStart == null) return
    pdfRegionEnd = screenToPage(x, y)
    postInvalidate()
  }

  fun endPdfRegion(cancelled: Boolean) {
    val start = pdfRegionStart
    val end = pdfRegionEnd
    pdfRegionStart = null
    pdfRegionEnd = null
    setRomPenInkEnabled(true)
    postInvalidate()
    if (cancelled || start == null || end == null || page.width <= 0f || page.height <= 0f) return
    val bounds = Bounds(
      minOf(start[0], end[0]).coerceIn(0f, page.width),
      minOf(start[1], end[1]).coerceIn(0f, page.height),
      maxOf(start[0], end[0]).coerceIn(0f, page.width),
      maxOf(start[1], end[1]).coerceIn(0f, page.height),
    )
    val minSize = currentViewport().screenDistanceToPage(24f)
    if (bounds.width < minSize || bounds.height < minSize) {
      emitNotice("选区太小")
      return
    }
    val selected = selectionTool.selectRectangle(bounds)
    selectionTool.consumeCompletedRegion()
    if (page.kind == PageKind.PDF_SOURCE) emitPdfSelection(bounds, selected)
  }

  private fun emitCompletedPdfSelection() {
    val bounds = selectionTool.consumeCompletedRegion() ?: return
    if (page.kind == PageKind.PDF_SOURCE) emitPdfSelection(bounds, selectionTool.currentSelection())
  }

  private fun emitPdfSelection(bounds: Bounds, selected: SelectionSet) {
    if (page.width <= 0f || page.height <= 0f) return
    val normalized = Bounds(
      bounds.left / page.width,
      bounds.top / page.height,
      bounds.right / page.width,
      bounds.bottom / page.height,
    )
    onPdfRegionSelected?.invoke(
      PdfRegionSelection(
        sourcePageId = page.id,
        normalizedBounds = normalized,
        selectedObjectIds = selected.objectIds,
        screenBounds = currentViewport().pageToScreen(bounds),
      ),
    )
  }

  fun linkedNoteTargetsForCurrentPdfSource(): List<LinkedNoteTarget> {
    if (page.kind != PageKind.PDF_SOURCE) return emptyList()
    return notebook.allPageMetadata()
      .filter { it.kind == PageKind.LINKED_NOTE && it.parentPdfPageId == page.id }
      .mapIndexed { index, metadata -> LinkedNoteTarget(metadata.id, index + 1) }
  }

  fun hasPendingLinkedNotePlacement(): Boolean = pendingLinkedNotePlacement != null

  /**
   * Prepares the selected PDF region and opens the destination note page. The
   * object is intentionally not created yet: the next ordinary page tap is
   * its exact insertion point.
   */
  fun prepareLinkedNoteFromRegion(
    normalizedBounds: Bounds,
    content: LinkedNoteContent,
    targetNotePageId: UUID?,
    onComplete: (Result<UUID>) -> Unit,
  ) {
    if (page.kind != PageKind.PDF_SOURCE) {
      onComplete(Result.failure(IllegalStateException("当前页不是 PDF 源页")))
      return
    }
    val sourcePageId = page.id
    val sourceIndex = page.pdfSource?.sourcePageIndex
      ?: return onComplete(Result.failure(IllegalStateException("PDF 页面信息缺失")))
    val sourceNotebookId = notebook.id
    val noteWidth = width.toFloat().coerceAtLeast(1f)
    val noteHeight = height.toFloat().coerceAtLeast(1f)
    materialize()
    val sourceSnapshot = PageSnapshot.capture(page)
    scheduleSave(DocumentChange.fullPage(notebook, page, "linked-note:before-create"))
    notebookOperations.execute {
      var linkedPageId: UUID? = targetNotePageId
      var createdNotePage = false
      var importedImage: ImportedImage? = null
      val result = runCatching {
        check(persistenceAvailable && autosave.flush()) { "保存当前 PDF 批注失败" }
        val record = repository.pdfDocument(sourceNotebookId) ?: error("PDF 资源记录缺失")
        val file = pdfAssets.resolve(record.assetPath) ?: error("PDF 资源文件缺失")
        val selectedText: String?
        val preparedImage: ImportedImage?
        when (content) {
          LinkedNoteContent.REGION_IMAGE -> {
            val bitmap = renderCompositePdfRegion(
              file,
              sourceIndex,
              normalizedBounds,
              sourceSnapshot,
            )
            try {
              preparedImage = imageAssets.commit(imageAssets.importPng(bitmap))
              importedImage = preparedImage
            } finally {
              bitmap.recycle()
            }
            selectedText = null
          }

          LinkedNoteContent.REGION_TEXT -> {
            selectedText = pdfEngine.extractText(file, sourceIndex, normalizedBounds)
              .takeIf(String::isNotBlank) ?: error("选区中没有可提取文字；公式或图片请使用截图")
            preparedImage = null
          }
        }
        val notePageId = targetNotePageId ?: repository.createLinkedNotePage(
          sourcePageId,
          noteWidth,
          noteHeight,
        ).also {
          linkedPageId = it
          createdNotePage = true
        }
        linkedPageId = notePageId
        val loaded = repository.loadNotebook(sourceNotebookId) ?: error("无法重载 PDF 笔记本")
        val notePage = repository.loadPage(notePageId) ?: error("无法加载夹纸页")
        check(notePage.kind == PageKind.LINKED_NOTE && notePage.parentPdfPageId == sourcePageId) {
          "所选夹纸不属于当前 PDF 页面"
        }
        loaded.attachPage(notePage)
        Pair(
          loaded,
          PendingLinkedNotePlacement(
            sourceNotebookId = sourceNotebookId,
            sourcePageId = sourcePageId,
            notePageId = notePageId,
            normalizedBounds = normalizedBounds,
            content = content,
            selectedText = selectedText,
            preparedImage = preparedImage,
            createdNotePage = createdNotePage,
          ),
        )
      }.onFailure {
        if (createdNotePage) {
          linkedPageId?.let { id ->
            runCatching { repository.deleteLinkedNotePage(id) }
          }
        }
        importedImage?.let(imageAssets::discard)
      }
      post {
        result.onSuccess { (loaded, pending) ->
          pendingLinkedNotePlacement?.preparedImage?.let(imageAssets::discard)
          pendingLinkedNotePlacement = pending
          installNotebook(loaded)
          switchToPage(pending.notePageId)
          onDocChanged?.invoke()
        }.exceptionOrNull()?.let { emitNotice("准备夹纸失败：${it.message}") }
        onComplete(result.map { it.second.notePageId })
      }
    }
  }

  /** Renders the visible PDF region without transient lasso/anchor/navigation overlays. */
  private fun renderCompositePdfRegion(
    file: File,
    sourceIndex: Int,
    normalizedBounds: Bounds,
    snapshot: PageSnapshot,
  ): Bitmap {
    val bitmap = pdfEngine.renderRegion(file, sourceIndex, normalizedBounds)
    val metadata = snapshot.metadata
    val pageRegion = Bounds(
      normalizedBounds.left * metadata.width,
      normalizedBounds.top * metadata.height,
      normalizedBounds.right * metadata.width,
      normalizedBounds.bottom * metadata.height,
    )
    if (pageRegion.width <= 0f || pageRegion.height <= 0f) return bitmap
    val canvas = Canvas(bitmap)
    canvas.withScale(bitmap.width / pageRegion.width, bitmap.height / pageRegion.height) {
      canvas.translate(-pageRegion.left, -pageRegion.top)
      val renderContext = RenderContext()
      snapshot.objects.asSequence()
        .filter { it.pageBounds.intersects(pageRegion) }
        .sortedBy(PageObject::zIndex)
        .forEach { drawObject(canvas, it, renderContext) }
    }
    return bitmap
  }

  fun placePendingLinkedNote(screenX: Float, screenY: Float, onComplete: (Result<UUID>) -> Unit) {
    val pending = pendingLinkedNotePlacement
      ?: return onComplete(Result.failure(IllegalStateException("没有待放置的夹纸内容")))
    if (page.id != pending.notePageId) {
      onComplete(Result.failure(IllegalStateException("请先回到目标夹纸页")))
      return
    }
    val point = screenToPage(screenX, screenY)
    val pageX = point[0].coerceIn(0f, page.width)
    val pageY = point[1].coerceIn(0f, page.height)
    val targetPageId = page.id
    notebookOperations.execute {
      val result = runCatching {
        check(persistenceAvailable && autosave.flush()) { "保存夹纸失败" }
        val loaded = repository.loadNotebook(pending.sourceNotebookId) ?: error("无法重载 PDF 笔记本")
        val notePage = repository.loadPage(targetPageId) ?: error("无法加载目标夹纸")
        check(
          notePage.kind == PageKind.LINKED_NOTE &&
            notePage.parentPdfPageId == pending.sourcePageId,
        ) { "目标夹纸与 PDF 锚点不匹配" }
        loaded.attachPage(notePage)
        val now = System.currentTimeMillis()
        val zIndex = (notePage.scene.all().maxOfOrNull(PageObject::zIndex) ?: -1) + 1
        val obj: PageObject = pending.preparedImage?.let { image ->
          val displayWidth = image.pixelWidth.toFloat()
          val displayHeight = image.pixelHeight.toFloat()
          val scale = minOf(
            notePage.width * 0.72f / displayWidth.coerceAtLeast(1f),
            notePage.height * 0.38f / displayHeight.coerceAtLeast(1f),
            1f,
          )
          ImageObject(
            id = UUID.randomUUID(),
            transform = Transform2D(
              scale,
              0f,
              0f,
              scale,
              pageX - displayWidth * scale / 2f,
              pageY - displayHeight * scale / 2f,
            ),
            zIndex = zIndex, createdAt = now, updatedAt = now,
            assetPath = image.relativePath, mimeType = image.mimeType,
            pixelWidth = image.pixelWidth, pixelHeight = image.pixelHeight,
          )
        } ?: run {
          val text = requireNotNull(pending.selectedText)
          val fontSize = 24f
          val bounds = measureTextBoundsOffThread(text, fontSize, notePage.width * 0.76f)
          TextObject(
            id = UUID.randomUUID(),
            transform = Transform2D.translate(pageX - bounds.width / 2f, pageY),
            zIndex = zIndex, createdAt = now, updatedAt = now,
            text = text, fontFamily = TextFontFamily.SANS_SERIF, fontSize = fontSize,
            localBounds = bounds,
          )
        }
        notePage.addObject(obj)
        loaded.refreshPageMetadata(notePage)
        val ordinal = (
          repository.pdfAnchors(pending.sourcePageId)
            .maxOfOrNull(PdfAnchor::ordinal) ?: 0
          ) + 1
        repository.persistWithPdfAnchor(
          DocumentChange.fullPage(loaded, notePage, "linked-note:place-anchor-object"),
          PdfAnchor(
            sourcePageId = pending.sourcePageId,
            notePageId = pending.notePageId,
            noteObjectId = obj.id,
            kind = if (pending.content == LinkedNoteContent.REGION_IMAGE) {
              PdfAnchorKind.REGION_IMAGE
            } else {
              PdfAnchorKind.REGION_TEXT
            },
            normalizedBounds = pending.normalizedBounds,
            selectedText = pending.selectedText,
            ordinal = ordinal,
          ),
        )
        Triple(loaded, notePage.id, obj.id)
      }
      post {
        result.onSuccess { (loaded, notePageId, _) ->
          pendingLinkedNotePlacement = null
          installNotebook(loaded)
          switchToPage(notePageId)
          selectedRichObjectId = null
          onDocChanged?.invoke()
        }.exceptionOrNull()?.let { emitNotice("放置夹纸内容失败：${it.message}") }
        onComplete(result.map { it.third })
      }
    }
  }

  fun cancelPendingLinkedNotePlacement() {
    val pending = pendingLinkedNotePlacement ?: return
    pendingLinkedNotePlacement = null
    pending.preparedImage?.let(imageAssets::discard)
    if (!pending.createdNotePage) {
      onDocChanged?.invoke()
      return
    }
    notebookOperations.execute {
      runCatching { repository.deleteLinkedNotePage(pending.notePageId) }
      val loaded = runCatching { repository.loadNotebook(pending.sourceNotebookId) }.getOrNull()
      post {
        if (loaded != null) {
          installNotebook(loaded)
          switchToPage(pending.sourcePageId)
        } else {
          onDocChanged?.invoke()
        }
      }
    }
  }

  private fun measureTextBoundsOffThread(text: String, fontSize: Float, maxWidth: Float): Bounds {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = Typeface.create(TextFontFamily.SANS_SERIF.androidName, Typeface.NORMAL)
      textSize = fontSize
    }
    val lines = text.lines().ifEmpty { listOf("") }
    val width = lines.maxOfOrNull(paint::measureText)?.coerceAtMost(maxWidth)?.coerceAtLeast(1f) ?: 1f
    val height = (paint.fontMetrics.descent - paint.fontMetrics.ascent) * lines.size
    return Bounds(0f, 0f, width, height.coerceAtLeast(1f))
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
    foreBitmap?.eraseColor(android.graphics.Color.WHITE)
    val viewport = currentViewport()
    if (page.kind != PageKind.PDF_SOURCE) {
      drawTemplateBackground(canvas, page.toMetadataForTemplate(), viewport)
    }
    canvas.withTranslation(viewport.offsetX, viewport.offsetY) {
      canvas.scale(viewport.scale, viewport.scale)
      for (obj in page.scene.all()) drawObject(canvas, obj, viewRenderContext)
    }
    if (page.kind == PageKind.PDF_SOURCE) schedulePagePreRender(page.id)
    markCurrentPageRendered()
    postInvalidate()
  }

  private fun markCurrentPageRendered() {
    renderedPageId = page.id
    renderedContentRevision = page.contentRevision
    renderedTemplateFingerprint = templateStore.visualFingerprint(page.templateId)
  }

  private fun isCurrentPageRendered(): Boolean =
    renderedPageId == page.id && renderedContentRevision == page.contentRevision &&
      renderedTemplateFingerprint == templateStore.visualFingerprint(page.templateId)

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
    if (!isCurrentPageRendered()) redrawAll()
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
    val serviceWidth = PenProfiles.serviceWidth(penStyle)
    pd.setPen(penDrawPt, servicePen)
    pd.setPenColor(penDrawPt, serviceColor)
    pd.setPenWidth(penDrawPt, serviceWidth)
    EventLog.log(
      TAG,
      "pen style type=${penStyle.penType} servicePen=$servicePen " +
        "color=0x${serviceColor.toUInt().toString(16)} width=$serviceWidth",
    )
  }

  fun strokeCount(): Int = page.scene.size

  fun clear() {
    handler.removeCallbacks(finishRunnable)
    gestureOpen = false
    page.clear()
    notebook.refreshPageMetadata(page)
    thumbnails.invalidate(page.id, page.contentRevision, templateStore.visualFingerprint(page.templateId))
    commandStack.clear()
    scheduleSave(DocumentChange.fullPage(notebook, page, "page:clear"))
    redrawAll()
    clearOverlayInk()
    onDocChanged?.invoke()
    EventLog.log(TAG, "clear")
  }

  fun teardown() {
    handler.removeCallbacks(finishRunnable)
    pagePreRenderClosed = true
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
    pagePreRenderExecutor.shutdownNow()
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
    private const val MAX_DECODE_DIMENSION = 1024
    private const val IMAGE_CACHE_BYTES = 32 * 1024 * 1024
    private const val PAGE_BITMAP_CACHE_BYTES = 24 * 1024 * 1024
    private const val ZOOM_DRAG_DISTANCE = 320f
    private const val MAX_DOCUMENT_HISTORY = 64
    private const val ANCHOR_HIGHLIGHT_MS = 1_200L
    private const val LINK_ICON_RADIUS_DP = 12f
    private const val ANCHOR_HIT_RADIUS_DP = 22f

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
