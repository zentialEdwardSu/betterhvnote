package com.betterhv.note

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.HvPenDrawListener
import android.os.HvPenDrawManager
import android.os.Looper
import android.os.Message
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Core Phase-1 migration: a plain View that wires the Hanvon ROM pen
 * service (HvPenDrawManager) for low-latency EPD ink, mirroring
 * hvNote's HandView.intiPenDraw(). The ROM draws live strokes to the
 * hardware overlay; on pen-up onPenTouchUpStatus() delivers final points,
 * which we persist into m_foreBitmap via HWPenEngine.drawPencil32.
 */
class PenDrawView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), HvPenDrawListener {

    private var penDraw: HvPenDrawManager? = null
    private var penDrawPt: Long = 0
    private var initPenService = false
    private var foreBitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var penWidth = 4
    // Resolved per-stroke at ACTION_DOWN from MotionEvent tool type / button state,
    // mirroring HandView.onTouchEvent's bPenEraser logic. Snapshotted into pendingMode
    // since onPenTouchUpStatus() arrives asynchronously from the ROM, by which time this
    // field may already have reverted for the next stroke.
    private var pendingMode = 0  // 0=PEN, 1=ERASER_TRACE (unimplemented), 2=ERASER_PIXEL
    private var eraserWidth = 12
    private val originPos = HvPenDrawManager.getScreenOrgPos()
    private val screenW: Int
    private val screenH: Int

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == MSG_UPDATE) {
                val pts = msg.data.getFloatArray("points") ?: return
                commit(pts)
            }
        }
    }

    init {
        val dm = context.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        EventLog.log("PenDrawView", "created screen=${screenW}x$screenH orgPos=$originPos model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0) {
            createStrokeBitmap(w, h)
            initPenDraw()
        }
    }

    /** Mirror of HandView.createStrokeBitmap: ARGB_8888 view-sized buffer. */
    private fun createStrokeBitmap(w: Int, h: Int) {
        val bmp = foreBitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            foreBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            canvas = Canvas(foreBitmap!!)
            EventLog.log("PenDrawView", "createStrokeBitmap ${w}x$h ARGB_8888")
        } else {
            bmp.eraseColor(0)
        }
    }

    /** Port of HandView.intiPenDraw: bind hvpen service and start the area. */
    private fun initPenDraw() {
        if (penDraw == null) {
            penDraw = context.getSystemService("hvpen") as? HvPenDrawManager
            if (penDraw == null) {
                EventLog.log("PenDrawView", "ERROR getSystemService(hvpen) returned null")
                return
            }
            EventLog.log("PenDrawView", "hvpen service acquired")
        }
        if (initPenService) return
        initPenService = true

        val rotation = display?.rotation ?: 0
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
        val area = PenGeometry.getPenDrawArea(rect, rotation, originPos, screenW, screenH)
        EventLog.log("PenDrawView", "initService area=[${area.left},${area.top},${area.right},${area.bottom}] rot=$rotation")
        try {
            penDrawPt = penDraw!!.initService(area.left, area.top, area.right, area.bottom, this)
            EventLog.log("PenDrawView", "initService -> handle=$penDrawPt")
            penDraw!!.setPen(penDrawPt, HvPenDrawManager.PEN_MODE)
            penDraw!!.setPenColor(penDrawPt, HvPenDrawManager.PEN_BLACK_COLOR)
            penDraw!!.setPenWidth(penDrawPt, penWidth)
            penDraw!!.setDrawStatus(penDrawPt, HvPenDrawManager.PEN_MODE)
            penDraw!!.setAreaActive(penDrawPt, true)
            EventLog.log("PenDrawView", "pen configured (mode=PEN color=BLACK width=$penWidth) areaActive=true")
        } catch (t: Throwable) {
            EventLog.log("PenDrawView", "ERROR initService: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** HvPenDrawListener callback — final stroke points on pen-up. */
    override fun onPenTouchUpStatus(up: Boolean, points: FloatArray?) {
        if (points == null || points.isEmpty()) return
        val n = points[0].toInt()
        // guard mirrors HandView: expect length == n*3+1
        if (points.size.toFloat() != points[0] * 3.0f + 1.0f) {
            EventLog.log("PenDrawView", "stroke up=$up n=$n len=${points.size} (unexpected len, skipped)")
            return
        }
        EventLog.log("PenDrawView", "onPenTouchUpStatus up=$up points=$n")
        val msg = handler.obtainMessage(MSG_UPDATE)
        msg.data = android.os.Bundle().apply { putFloatArray("points", points) }
        msg.sendToTarget()
    }

    private fun commit(pts: FloatArray) {
        val bmp = foreBitmap ?: return
        val mode = pendingMode
        try {
            val ok = StrokeRenderer.commitStroke(bmp, pts, Color.BLACK, mode)
            EventLog.log("PenDrawView", "commitStroke n=${pts[0].toInt()} mode=$mode rendered=$ok")
            postInvalidate()
        } catch (t: Throwable) {
            EventLog.log("PenDrawView", "ERROR commitStroke: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        foreBitmap?.let { c.drawBitmap(it, 0f, 0f, null) }
    }

    /**
     * Resolves pen tail eraser / side buttons from the MotionEvent itself, mirroring
     * HandView.onTouchEvent + MemoView.checkPenbtnMode. The ROM's HvPenDrawManager
     * intercepts touch dispatch for live ink at a lower level, so this override only
     * observes tool type / button state per stroke; it doesn't consume the event.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val toolType = event.getToolType(0)
                val buttonState = event.getButtonState()
                pendingMode = when {
                    toolType == MotionEvent.TOOL_TYPE_ERASER -> 2
                    buttonState == MotionEvent.BUTTON_STYLUS_PRIMARY -> 2
                    buttonState == MotionEvent.BUTTON_STYLUS_SECONDARY -> {
                        EventLog.log("PenDrawView", "side button 2 pressed (buttonState=0x${buttonState.toString(16)}, no-op)")
                        0
                    }
                    buttonState == BUTTON_STYLUS_TERTIARY -> {
                        EventLog.log("PenDrawView", "side button 3 pressed (buttonState=0x${buttonState.toString(16)}, no-op)")
                        0
                    }
                    else -> 0
                }
                EventLog.log("PenDrawView", "ACTION_DOWN toolType=$toolType buttonState=$buttonState -> pendingMode=$pendingMode")
            }
            // No ACTION_UP reset: pendingMode is fully recomputed on the next ACTION_DOWN,
            // and onPenTouchUpStatus() (which reads pendingMode in commit()) arrives async
            // via the ROM's Handler message, racing after ACTION_UP fires here.
        }
        return super.onTouchEvent(event)
    }

    fun clear() {
        foreBitmap?.eraseColor(0)
        penDraw?.resetData()
        postInvalidate()
        EventLog.log("PenDrawView", "clear")
    }

    fun teardown() {
        val pd = penDraw ?: return
        try {
            pd.endService(penDrawPt, this)
            EventLog.log("PenDrawView", "endService handle=$penDrawPt")
        } catch (t: Throwable) {
            EventLog.log("PenDrawView", "ERROR endService: ${t.message}")
        }
        initPenService = false
    }

    companion object {
        private const val MSG_UPDATE = 0x5dd
        // Third stylus side button — this ROM's driver adds this bit; no stock
        // MotionEvent.BUTTON_* constant covers it (confirmed via MemoView.checkPenbtnMode).
        private const val BUTTON_STYLUS_TERTIARY = 0x80
    }
}
