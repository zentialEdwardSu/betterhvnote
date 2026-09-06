package com.betterhv.note.tool

import com.betterhv.note.doc.CommandStack
import com.betterhv.note.doc.HitTest
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.commands.CompositeCommand
import com.betterhv.note.doc.commands.DeleteObjectsCommand
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint
import java.util.UUID

/**
 * Whole-stroke eraser (spec §33): any stroke the eraser disc touches is
 * removed entirely, and removed the moment the disc touches it -- same
 * immediate feedback as Phase 1's eraseMove -- rather than waiting for the
 * gesture to end. Each hit executes its own [DeleteObjectsCommand]
 * immediately; all of them are bundled into one [CompositeCommand] on
 * [commit] so a single undo reverts the entire drag, not one press per
 * stroke touched.
 */
class StrokeEraserTool(
  private val page: Page,
  private val commandStack: CommandStack,
  private val host: ToolHost,
  private val eraserRadiusProvider: () -> Float,
) : EraserTool {
  private val hitIdsThisGesture = LinkedHashSet<UUID>()
  private val executedThisGesture = ArrayList<DeleteObjectsCommand>()

  override fun onDown(x: Float, y: Float) {
    hitIdsThisGesture.clear()
    executedThisGesture.clear()
    eraseAt(x, y)
  }

  override fun onBatch(points: List<InkPoint>) {
    for (p in points) eraseAt(p.x, p.y)
  }

  override fun onUp() {
    commit()
  }

  override fun onCancel() {
    for (cmd in executedThisGesture.asReversed()) cmd.undo()
    hitIdsThisGesture.clear()
    executedThisGesture.clear()
  }

  /** One eraser sample in page/view coordinates -- also called directly by the tail-eraser overlay. */
  override fun eraseAt(x: Float, y: Float) {
    val radius = eraserRadiusProvider()
    val hit = Bounds(x - radius, y - radius, x + radius, y + radius)
    var dirty: Bounds? = null
    val newlyHit = ArrayList<UUID>()
    for (obj in page.queryObjects(hit)) {
      if (obj !is StrokeObject) continue
      if (obj.id in hitIdsThisGesture) continue
      if (!obj.pageBounds.intersects(hit)) continue
      val inv = obj.transform.invert() ?: continue
      val local = inv.mapPoint(x, y)
      val localRadius = radius / obj.transform.approximateScale().coerceAtLeast(1e-3f)
      if (HitTest.strokeTouchesLocal(obj.stroke.points, local[0], local[1], localRadius)) {
        hitIdsThisGesture.add(obj.id)
        newlyHit.add(obj.id)
        dirty = dirty?.union(obj.pageBounds) ?: obj.pageBounds
      }
    }
    if (newlyHit.isEmpty()) return
    val cmd = DeleteObjectsCommand(page, newlyHit)
    cmd.execute()
    executedThisGesture.add(cmd)
    dirty?.let { host.requestRepaint(it) }
  }

  /** Bundles every deletion executed so far into one undo step and clears gesture state. */
  override fun commit() {
    if (executedThisGesture.isNotEmpty()) {
      commandStack.push(CompositeCommand(executedThisGesture.toList()))
    }
    hitIdsThisGesture.clear()
    executedThisGesture.clear()
  }
}
