package com.betterhv.note

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
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
import com.betterhv.note.doc.Notebook
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.PageObject
import com.betterhv.note.doc.SelectionSet
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.Transform2D
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

/**
 * Writing surface. The ROM's hvpen service paints live ink onto the hardware
 * overlay for latency, and delivers the sampled points back to us; we turn
 * those into vector [com.betterhv.note.ink.Stroke]s and rasterize them into
 * our own bitmap.
 *
 * Document Core change (spec §7-10, §31-45): the flat stroke list is now a
 * [Page] (Scene + SpatialIndex) inside a single-page [Notebook], and every
 * mutation goes through a [Tool] + [CommandStack] so drawing, erasing,
 * splitting and transforming are all undoable. The bitmap remains a
 * rebuildable cache of [page]'s objects, same invariant as Phase 1 (spec §2.1).
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

    /** Authoritative document content: one Notebook, one Page for now (spec §7-10). */
    private val notebook = Notebook()
    private val page = Page()
    private val commandStack = CommandStack()

    private val renderer = InkRenderer()
    private var penStyle = PenStyle(baseWidth = DEFAULT_PEN_WIDTH)

    private val eraserWidth = DEFAULT_ERASER_WIDTH

    // -- Tools (spec §17, §33-40) ------------------------------------------

    private val penTool = PenTool(page, commandStack, this) { penStyle }
    private val strokeEraserTool = StrokeEraserTool(page, commandStack, this) { eraserWidth / 2.0f }
    private val pointEraserTool = PointEraserTool(page, commandStack, this) { eraserWidth / 2.0f }
    private val selectionTool = SelectionTool(page, commandStack, this) { HANDLE_TOUCH_RADIUS }

    private var toolKind: ToolKind = ToolKind.PEN
    private var currentTool: Tool = penTool

    /** Tail-eraser behavior, toggled from the toolbar (independent of [toolKind]). */
    private var eraserMode: EraserMode = EraserMode.WHOLE_STROKE

    private var onDocChanged: (() -> Unit)? = null

    /** Set by the toolbar to observe undo/redo availability and selection state. */
    fun setOnDocChanged(listener: (() -> Unit)?) {
        onDocChanged = listener
    }

    init {
        notebook.addPage(page)
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
            createBitmap(w, h)
            initPenDraw()
        }
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
        applyRomPenInkState(resetData = true)
        EventLog.log(TAG, "ui input blocked=$blocked")
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
        val stroke = (obj as? StrokeObject)?.stroke ?: return
        val t = obj.transform
        objectMatrix.setValues(floatArrayOf(t.a, t.c, t.tx, t.b, t.d, t.ty, 0f, 0f, 1f))
        val save = canvas.save()
        canvas.concat(objectMatrix)
        renderer.drawStroke(canvas, stroke)
        canvas.restoreToCount(save)
    }

    // -- ToolHost (spec §17): tools call back into the view to repaint or update selection UI --

    override fun requestRepaint(bounds: Bounds) {
        repaintRegion(InkRenderer.dirtyRect(bounds, penStyle))
        onDocChanged?.invoke()
    }

    override fun onSelectionChanged(selection: SelectionSet) {
        onDocChanged?.invoke()
    }

    fun currentSelection(): SelectionSet = selectionTool.currentSelection()

    /** Deletes the current lasso selection as one undo step (toolbar Delete action). */
    fun deleteSelection() {
        selectionTool.deleteSelection()
    }

    fun undo() {
        if (gestureOpen) finishGesture()
        commandStack.undo()
        redrawAll()
        onDocChanged?.invoke()
    }

    fun redo() {
        if (gestureOpen) finishGesture()
        commandStack.redo()
        redrawAll()
        onDocChanged?.invoke()
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
        commandStack.clear()
        foreBitmap?.eraseColor(0)
        clearOverlayInk()
        postInvalidate()
        onDocChanged?.invoke()
        EventLog.log(TAG, "clear")
    }

    fun teardown() {
        handler.removeCallbacks(finishRunnable)
        val pd = penDraw ?: return
        try {
            pd.endService(penDrawPt, this)
            EventLog.log(TAG, "endService handle=$penDrawPt")
        } catch (t: Throwable) {
            EventLog.log(TAG, "ERROR endService: ${t.message}")
        }
        initPenService = false
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
    }
}
