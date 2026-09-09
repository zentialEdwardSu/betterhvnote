package com.betterhv.note.tool

import com.betterhv.note.doc.CommandStack
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.commands.CompositeCommand
import com.betterhv.note.doc.commands.SplitStrokeCommand
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.Stroke
import java.util.UUID

/**
 * Point eraser (spec §34-35): points within the eraser disc are deleted from
 * a stroke's centerline and the remaining continuous runs become new, separate
 * strokes -- "split" rather than "delete whole". One [SplitStrokeCommand] per
 * originally-affected stroke, all committed together on [onUp] so one undo
 * reverts the whole erase drag.
 */
class PointEraserTool(
  private val page: Page,
  private val commandStack: CommandStack,
  private val host: ToolHost,
  private val eraserRadiusProvider: () -> Float,
) : EraserTool {
  // Original stroke id -> set of point indices marked for removal this gesture.
  private val erasedIndices = HashMap<UUID, MutableSet<Int>>()
  private val touchedOriginals = LinkedHashMap<UUID, StrokeObject>()
  private var dirtyRegion: Bounds? = null

  override fun onDown(x: Float, y: Float) {
    erasedIndices.clear()
    touchedOriginals.clear()
    dirtyRegion = null
    eraseAt(x, y)
  }

  override fun onBatch(points: List<InkPoint>) {
    for (p in points) eraseAt(p.x, p.y)
  }

  override fun onUp() {
    commit()
  }

  override fun onCancel() {
    erasedIndices.clear()
    touchedOriginals.clear()
    dirtyRegion = null
  }

  override fun eraseAt(x: Float, y: Float) {
    val radius = eraserRadiusProvider()
    val hit = Bounds(x - radius, y - radius, x + radius, y + radius)
    for (obj in page.queryObjects(hit)) {
      if (obj !is StrokeObject) continue
      if (!obj.pageBounds.intersects(hit)) continue
      val inv = obj.transform.invert() ?: continue
      val local = inv.mapPoint(x, y)
      val localRadius = radius / obj.transform.approximateScale().coerceAtLeast(1e-3f)
      val points = obj.stroke.points
      val marked = erasedIndices.getOrPut(obj.id) { HashSet() }
      var any = false
      for (i in points.indices) {
        if (i in marked) continue
        val p = points[i]
        val halfWidth = obj.stroke.style.widthAt(p) * 0.5f
        val dx = p.x - local[0]
        val dy = p.y - local[1]
        if (dx * dx + dy * dy <= (localRadius + halfWidth) * (localRadius + halfWidth)) {
          marked.add(i)
          any = true
        }
      }
      if (any) {
        touchedOriginals.putIfAbsent(obj.id, obj)
        dirtyRegion = dirtyRegion?.union(obj.pageBounds) ?: obj.pageBounds
      }
    }
  }

  /** Splits every touched stroke into its surviving runs and commits one command per original. */
  override fun commit() {
    if (touchedOriginals.isEmpty()) {
      erasedIndices.clear()
      touchedOriginals.clear()
      dirtyRegion = null
      return
    }
    val splits = ArrayList<SplitStrokeCommand>(touchedOriginals.size)
    for ((id, original) in touchedOriginals) {
      val marked = erasedIndices[id] ?: emptySet()
      val pieces = splitIntoPieces(original, marked)
      val cmd = SplitStrokeCommand(page, id, pieces)
      cmd.execute()
      splits.add(cmd)
    }
    commandStack.push(CompositeCommand(splits))
    dirtyRegion?.let { host.requestRepaint(it) }
    erasedIndices.clear()
    touchedOriginals.clear()
    dirtyRegion = null
  }

  /** Regroups the surviving (non-erased) index runs of [original]'s points into new StrokeObjects. */
  private fun splitIntoPieces(original: StrokeObject, erased: Set<Int>): List<StrokeObject> {
    val points = original.stroke.points
    val pieces = ArrayList<StrokeObject>()
    var runStart = -1
    for (i in points.indices) {
      val kept = i !in erased
      if (kept && runStart == -1) {
        runStart = i
      } else if (!kept && runStart != -1) {
        addPieceIfViable(pieces, original, points, runStart, i)
        runStart = -1
      }
    }
    if (runStart != -1) addPieceIfViable(pieces, original, points, runStart, points.size)
    return pieces
  }

  private fun addPieceIfViable(
    out: MutableList<StrokeObject>,
    original: StrokeObject,
    points: List<InkPoint>,
    fromInclusive: Int,
    toExclusive: Int,
  ) {
    // A lone surviving point is still a valid dot stroke (StrokeGeometry
    // handles single-point outlines), so the minimum run length is 1, not 2.
    if (toExclusive <= fromInclusive) return
    val runPoints = points.subList(fromInclusive, toExclusive).toList()
    val piece = Stroke(points = runPoints, style = original.stroke.style)
    out.add(
      StrokeObject(
        id = UUID.randomUUID(),
        transform = original.transform,
        zIndex = original.zIndex,
        createdAt = original.createdAt,
        updatedAt = System.currentTimeMillis(),
        stroke = piece,
      ),
    )
  }
}
