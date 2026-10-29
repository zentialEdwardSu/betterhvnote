package com.betterhv.note.export

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.betterhv.note.doc.DEFAULT_TEMPLATE_ID
import com.betterhv.note.doc.Notebook
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.PenType
import com.betterhv.note.ink.Stroke
import com.betterhv.note.storage.DocumentChange
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.storage.PageSnapshot
import java.io.File
import java.util.UUID
import kotlin.math.sin

internal data class SeededNotebook(val id: UUID, val pageIds: List<UUID>)

/**
 * Creates a notebook with deterministic multi-sample strokes so export tests
 * exercise real annotation and raster paths rather than blank pages.
 */
internal fun seedInkNotebook(
  notebooks: NotebookRepository,
  title: String,
  pageCount: Int,
  strokesPerPage: Int,
  width: Float = 600f,
  height: Float = 800f,
): SeededNotebook {
  // Make sure a Working Copy exists so the seeded notebook is never adopted as
  // one (the repository would then refuse to delete it in test cleanup).
  notebooks.workingNotebookId()
  val notebookId = notebooks.createBlankNotebook(title, width, height)
  val notebook = Notebook(id = notebookId, title = title)
  val pages = ArrayList<Page>(pageCount)
  repeat(pageCount) { pageIndex ->
    val page = Page(width = width, height = height, templateId = DEFAULT_TEMPLATE_ID)
    repeat(strokesPerPage) { strokeIndex ->
      page.addObject(sampleStroke(pageIndex, strokeIndex, width, height))
    }
    notebook.addPage(page)
    pages += page
  }
  notebooks.persist(
    DocumentChange(
      notebookId = notebookId,
      title = title,
      notebookKind = notebook.kind,
      createdAt = notebook.createdAt,
      updatedAt = notebook.updatedAt,
      pageOrder = notebook.pageOrder.toList(),
      pages = notebook.allPageMetadata(),
      objectChanges = emptyList(),
      fullPages = pages.map(PageSnapshot::capture),
      description = "test:seed-ink",
    ),
  )
  return SeededNotebook(notebookId, pages.map(Page::id))
}

private fun sampleStroke(pageIndex: Int, strokeIndex: Int, width: Float, height: Float): StrokeObject {  val baseline = 40f + ((pageIndex * 53 + strokeIndex * 19) % (height.toInt() - 90))
  val points = (0 until 24).map { step ->
    val t = step / 23f
    InkPoint(
      x = 24f + (width - 48f) * t,
      y = baseline + sin(t * 6.2831855f + strokeIndex) * 12f,
      pressure = 0.2f + 0.8f * t,
      timestamp = step.toLong(),
    )
  }
  return StrokeObject(
    id = UUID.randomUUID(),
    transform = Transform2D.IDENTITY,
    zIndex = strokeIndex,
    createdAt = 1_700_000_000_000L + strokeIndex,
    updatedAt = 1_700_000_000_000L + strokeIndex,
    stroke = Stroke(
      points,
      PenStyle(baseWidth = 5f, color = 0xFF101010.toInt(), penType = PenType.NormalPen),
    ),
  )
}

internal inline fun openPdf(file: File, block: (PDFDocument) -> Unit) {
  val document = Document.openDocument(file.absolutePath) as PDFDocument
  try {
    block(document)
  } finally {
    document.destroy()
  }
}

internal fun newSampleStroke(index: Int, width: Float = 600f, height: Float = 800f): StrokeObject =
  sampleStroke(0, index, width, height)
