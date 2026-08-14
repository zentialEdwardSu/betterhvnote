package com.betterhv.note.tool

import com.betterhv.note.doc.CommandStack
import com.betterhv.note.doc.HitTest
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.PageObject
import com.betterhv.note.doc.SelectionSet
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.doc.commands.DeleteObjectsCommand
import com.betterhv.note.doc.commands.TransformObjectsCommand
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Lasso select (spec §36-37) plus, once a selection is active, drag-to-move
 * (drag inside the selection bounds) and drag-to-scale (drag a corner
 * handle), per spec §38-39. Move/scale build ONE [TransformObjectsCommand]
 * that is updated live across the whole drag and only pushed to the
 * [CommandStack] on [onUp] -- the command-merge behavior spec §44 asks for,
 * achieved by never creating the intermediate per-point commands rather than
 * merging them after the fact.
 */
class SelectionTool(
    private val page: Page,
    private val commandStack: CommandStack,
    private val host: ToolHost,
    private val handleTouchRadiusProvider: () -> Float
) : Tool {

    private enum class Mode { NONE, LASSO, MOVE, SCALE }

    private var mode = Mode.NONE
    private var selection: SelectionSet = SelectionSet.EMPTY

    // Lasso path accumulation, flat (x0,y0,x1,y1,...).
    private val lassoPath = ArrayList<Float>()

    // Move/scale drag state.
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var scaleAnchorX = 0f
    private var scaleAnchorY = 0f
    private var scaleStartDist = 0f
    private var beforeTransforms: Map<UUID, Transform2D> = emptyMap()
    private var beforeBounds: Bounds = Bounds(0f, 0f, 0f, 0f)
    private var pendingCommand: TransformObjectsCommand? = null

    fun currentSelection(): SelectionSet = selection

    /**
     * Snapshot of the in-progress lasso polyline (flat x0,y0,x1,y1,...) while a
     * lasso is being drawn, for the view's transient marquee overlay; null when
     * not lassoing. Copied out because [lassoPath] is cleared on finish.
     */
    fun activeLassoPath(): FloatArray? =
        if (mode == Mode.LASSO && lassoPath.size >= 2) lassoPath.toFloatArray() else null

    fun clearSelection() {
        selection = SelectionSet.EMPTY
        host.onSelectionChanged(selection)
    }

    /** Deletes the current selection as one undo step (toolbar Delete action). */
    fun deleteSelection() {
        if (selection.isEmpty) return
        commandStack.execute(DeleteObjectsCommand(page, selection.objectIds))
        host.requestRepaint(selection.bounds)
        clearSelection()
    }

    override fun onDown(x: Float, y: Float) {
        if (!selection.isEmpty) {
            val handle = hitHandle(x, y)
            if (handle != null) {
                beginScale(x, y, handle)
                return
            }
            if (pointInBounds(x, y, selection.bounds)) {
                beginMove(x, y)
                return
            }
            // Outside the current selection: drop it and start a fresh lasso.
            clearSelection()
        }
        mode = Mode.LASSO
        lassoPath.clear()
        lassoPath.add(x)
        lassoPath.add(y)
    }

    override fun onBatch(points: List<InkPoint>) {
        when (mode) {
            Mode.LASSO -> for (p in points) {
                lassoPath.add(p.x)
                lassoPath.add(p.y)
            }
            // A MOVE event can contain many historical samples. Only its latest
            // position affects an absolute drag transform; repainting every
            // historical position would queue redundant e-ink refreshes.
            Mode.MOVE -> points.lastOrNull()?.let { updateMove(it.x, it.y) }
            Mode.SCALE -> points.lastOrNull()?.let { updateScale(it.x, it.y) }
            Mode.NONE -> {}
        }
    }

    override fun onUp() {
        when (mode) {
            Mode.LASSO -> finishLasso()
            Mode.MOVE, Mode.SCALE -> finishTransform()
            Mode.NONE -> {}
        }
        mode = Mode.NONE
    }

    override fun onCancel() {
        if (mode == Mode.MOVE || mode == Mode.SCALE) {
            val changedBounds = selection.bounds
            for ((id, transform) in beforeTransforms) {
                page.updateObjectTransform(id, transform)
            }
            selection = SelectionSet(selection.objectIds, beforeBounds)
            host.requestRepaint(changedBounds.union(beforeBounds))
        }
        lassoPath.clear()
        pendingCommand = null
        mode = Mode.NONE
    }

    // -- Lasso -----------------------------------------------------------

    private fun finishLasso() {
        if (lassoPath.size < 6) { // fewer than 3 points
            lassoPath.clear()
            return
        }
        val polygon = lassoPath.toFloatArray()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < polygon.size) {
            minX = min(minX, polygon[i])
            maxX = max(maxX, polygon[i])
            minY = min(minY, polygon[i + 1])
            maxY = max(maxY, polygon[i + 1])
            i += 2
        }
        val lassoBounds = Bounds(minX, minY, maxX, maxY)
        // hvNote rejects accidental/tap-sized lasso regions below 25 px in
        // either dimension before running its Region intersection query.
        if (lassoBounds.width < MIN_LASSO_SIZE || lassoBounds.height < MIN_LASSO_SIZE) {
            lassoPath.clear()
            selection = SelectionSet.EMPTY
            host.onSelectionChanged(selection)
            return
        }
        val hits = ArrayList<PageObject>()
        for (obj in page.queryObjects(lassoBounds)) {
            if (obj !is StrokeObject) continue
            if (strokeInPolygon(obj, polygon)) hits.add(obj)
        }
        lassoPath.clear()
        selection = SelectionSet.of(hits)
        host.onSelectionChanged(selection)
        if (!selection.isEmpty) host.requestRepaint(selection.bounds)
    }

    private fun strokeInPolygon(obj: StrokeObject, polygon: FloatArray): Boolean {
        val points = obj.stroke.points
        var previous: FloatArray? = null
        for (p in points) {
            val pagePoint = obj.transform.mapPoint(p.x, p.y)
            if (HitTest.pointInPolygon(pagePoint[0], pagePoint[1], polygon)) return true
            val start = previous
            if (start != null && HitTest.segmentIntersectsPolygon(
                    start[0], start[1], pagePoint[0], pagePoint[1], polygon
                )) {
                return true
            }
            previous = pagePoint
        }
        return false
    }

    // -- Move --------------------------------------------------------------

    private fun beginMove(x: Float, y: Float) {
        mode = Mode.MOVE
        dragStartX = x
        dragStartY = y
        beforeTransforms = snapshotTransforms()
        beforeBounds = selection.bounds
        pendingCommand = TransformObjectsCommand(page, selection.objectIds, beforeTransforms, beforeTransforms)
    }

    private fun updateMove(x: Float, y: Float) {
        val dx = x - dragStartX
        val dy = y - dragStartY
        val delta = Transform2D.translate(dx, dy)
        val after = beforeTransforms.mapValues { (_, t) -> delta.times(t) }
        val cmd = pendingCommand ?: return
        cmd.updateAfter(after)
        cmd.applyLive()
        val prevBounds = selection.bounds
        // Map the DRAG-START bounds by the absolute delta, not the already-moved
        // bounds -- compounding delta onto selection.bounds each batch makes the
        // box run away from the strokes across the drag.
        selection = SelectionSet(selection.objectIds, delta.mapRect(beforeBounds))
        // Repaint the union of old and new bounds so the object's previous
        // position is cleared as it moves -- repainting only the new bounds
        // leaves a ghost of the stroke at its start.
        host.requestRepaint(prevBounds.union(selection.bounds))
    }

    // -- Scale ---------------------------------------------------------------

    /** Corner handle indices: 0=TL, 1=TR, 2=BL, 3=BR. */
    private fun hitHandle(x: Float, y: Float): Int? {
        val r = handleTouchRadiusProvider()
        val b = selection.bounds
        val corners = arrayOf(
            b.left to b.top, b.right to b.top,
            b.left to b.bottom, b.right to b.bottom
        )
        for ((idx, corner) in corners.withIndex()) {
            val dx = x - corner.first
            val dy = y - corner.second
            if (dx * dx + dy * dy <= r * r) return idx
        }
        return null
    }

    private fun beginScale(x: Float, y: Float, handle: Int) {
        mode = Mode.SCALE
        val b = selection.bounds
        // Anchor is the opposite corner, so scaling pivots away from the drag.
        val anchor = when (handle) {
            0 -> b.right to b.bottom
            1 -> b.left to b.bottom
            2 -> b.right to b.top
            else -> b.left to b.top
        }
        scaleAnchorX = anchor.first
        scaleAnchorY = anchor.second
        val dx0 = x - scaleAnchorX
        val dy0 = y - scaleAnchorY
        scaleStartDist = max(kotlin.math.hypot(dx0.toDouble(), dy0.toDouble()).toFloat(), 1e-3f)
        beforeTransforms = snapshotTransforms()
        beforeBounds = selection.bounds
        pendingCommand = TransformObjectsCommand(page, selection.objectIds, beforeTransforms, beforeTransforms)
    }

    private fun updateScale(x: Float, y: Float) {
        val dx = x - scaleAnchorX
        val dy = y - scaleAnchorY
        val dist = max(kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat(), 1e-3f)
        val ratio = (dist / scaleStartDist).coerceIn(MIN_SCALE, MAX_SCALE)
        val delta = Transform2D.scaleAbout(scaleAnchorX, scaleAnchorY, ratio)
        val after = beforeTransforms.mapValues { (_, t) -> delta.times(t) }
        val cmd = pendingCommand ?: return
        cmd.updateAfter(after)
        cmd.applyLive()
        val prevBounds = selection.bounds
        // Map the drag-start bounds by the absolute scale ratio, not the
        // already-scaled bounds (see updateMove).
        selection = SelectionSet(selection.objectIds, delta.mapRect(beforeBounds))
        host.requestRepaint(prevBounds.union(selection.bounds))
    }

    // -- Shared --------------------------------------------------------------

    private fun finishTransform() {
        val cmd = pendingCommand
        pendingCommand = null
        if (cmd != null) commandStack.push(cmd)
    }

    private fun snapshotTransforms(): Map<UUID, Transform2D> =
        selection.objectIds.mapNotNull { id -> page.getObject(id)?.let { id to it.transform } }.toMap()

    private fun pointInBounds(x: Float, y: Float, b: Bounds): Boolean =
        x >= b.left && x <= b.right && y >= b.top && y <= b.bottom

    companion object {
        private const val MIN_LASSO_SIZE = 25.0f
        private const val MIN_SCALE = 0.1f
        private const val MAX_SCALE = 10.0f
    }
}
