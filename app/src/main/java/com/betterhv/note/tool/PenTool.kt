package com.betterhv.note.tool

import com.betterhv.note.doc.CommandStack
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.doc.commands.AddObjectCommand
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.StrokeBuilder
import com.betterhv.note.jni.ModelerStrokeSmoother
import java.util.UUID

/**
 * Freehand drawing. Same StrokeBuilder pipeline as Phase 1, except the
 * finished Stroke is now wrapped in a [StrokeObject] (fresh identity,
 * identity transform) and committed via [AddObjectCommand] instead of being
 * appended to a bare list, so drawing participates in undo/redo.
 */
class PenTool(
  private val page: Page,
  private val commandStack: CommandStack,
  private val host: ToolHost,
  private val styleProvider: () -> PenStyle,
) : Tool {
  private var builder: StrokeBuilder? = null

  /** Live outline bounds while the gesture is in progress, for host repaint. */
  fun liveBounds(): Bounds? = builder?.liveBounds()

  fun isActive(): Boolean = builder != null

  fun builderOrNull(): StrokeBuilder? = builder

  override fun onDown(x: Float, y: Float) {
    builder = StrokeBuilder(style = styleProvider(), smoother = ModelerStrokeSmoother())
  }

  override fun onBatch(points: List<InkPoint>) {
    val b = builder ?: run {
      builder = StrokeBuilder(style = styleProvider(), smoother = ModelerStrokeSmoother())
      builder
    } ?: return
    b.append(points)
  }

  override fun onUp() {
    val b = builder ?: return
    builder = null
    val stroke = b.finish() ?: return
    val obj = StrokeObject(
      id = UUID.randomUUID(),
      transform = Transform2D.IDENTITY,
      createdAt = System.currentTimeMillis(),
      updatedAt = System.currentTimeMillis(),
      stroke = stroke,
    )
    commandStack.execute(AddObjectCommand(page, obj))
    // Deliberately NOT calling host.requestRepaint here: the ROM overlay owns
    // the low-latency live image until the next structural materialization,
    // and a CLEAR+redraw at every pen-up visibly flashes on e-ink.
  }

  override fun onCancel() {
    builder = null
  }
}
