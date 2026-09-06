package com.betterhv.note.export

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Rect
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.storage.PageSnapshot
import java.io.File
import java.util.Date
import kotlin.math.max

/** Writes editable PDF Ink annotations and joins annotated pages without rasterizing ink. */
class PdfInkAnnotationWriter {
  fun copySourcePage(sourcePdf: File, sourcePageIndex: Int, outputPdf: File) {
    outputPdf.parentFile?.mkdirs()
    val source = openPdf(sourcePdf)
    val destination = PDFDocument()
    try {
      require(sourcePageIndex in 0 until source.countPages()) { "PDF source page is out of range" }
      destination.graftPage(0, source, sourcePageIndex)
      graftAnnotations(destination, 0, source, sourcePageIndex)
      destination.save(outputPdf.absolutePath, "garbage=4,compress")
    } catch (error: Throwable) {
      outputPdf.delete()
      throw error
    } finally {
      destination.destroy()
      source.destroy()
    }
  }

  fun addInkAnnotations(basePdf: File, snapshot: PageSnapshot, outputPdf: File) {
    outputPdf.parentFile?.mkdirs()
    val document = openPdf(basePdf)
    var page: PDFPage? = null
    try {
      page = document.loadPage(0) as PDFPage
      snapshot.objects
        .asSequence()
        .filterIsInstance<StrokeObject>()
        .sortedBy { it.zIndex }
        .forEach { stroke -> addAnnotation(document, page, stroke) }
      document.save(outputPdf.absolutePath, "garbage=4,compress")
    } catch (error: Throwable) {
      outputPdf.delete()
      throw error
    } finally {
      page?.destroy()
      document.destroy()
    }
  }

  fun mergePages(pageFiles: List<File>, outputPdf: File) {
    require(pageFiles.isNotEmpty()) { "At least one PDF page is required" }
    outputPdf.parentFile?.mkdirs()
    val destination = PDFDocument()
    try {
      pageFiles.forEach { pageFile ->
        val source = openPdf(pageFile)
        try {
          repeat(source.countPages()) { pageNumber ->
            val destinationPageNumber = destination.countPages()
            destination.graftPage(destinationPageNumber, source, pageNumber)
            graftAnnotations(
              destination,
              destinationPageNumber,
              source,
              pageNumber,
            )
          }
        } finally {
          source.destroy()
        }
      }
      destination.save(outputPdf.absolutePath, "garbage=4,compress")
    } catch (error: Throwable) {
      outputPdf.delete()
      throw error
    } finally {
      destination.destroy()
    }
  }

  /** MuPDF's page graft intentionally omits annotations, so graft that object tree explicitly. */
  private fun graftAnnotations(
    destination: PDFDocument,
    destinationPageNumber: Int,
    source: PDFDocument,
    sourcePageNumber: Int,
  ) {
    var sourcePage: PDFObject? = null
    var sourceAnnotations: PDFObject? = null
    var graftedAnnotations: PDFObject? = null
    var destinationPage: PDFObject? = null
    try {
      sourcePage = source.findPage(sourcePageNumber)
      sourceAnnotations = sourcePage.get("Annots")
      if (sourceAnnotations.isNull || sourceAnnotations.size() == 0) return
      graftedAnnotations = destination.graftObject(sourceAnnotations)
      destinationPage = destination.findPage(destinationPageNumber)
      destinationPage.put("Annots", graftedAnnotations)
    } finally {
      destinationPage?.destroy()
      graftedAnnotations?.destroy()
      sourceAnnotations?.destroy()
      sourcePage?.destroy()
    }
  }

  private fun addAnnotation(document: PDFDocument, page: PDFPage, stroke: StrokeObject) {
    val ink = PdfInkAppearance.from(stroke) ?: return
    val annotation = page.createAnnotation(PDFAnnotation.TYPE_INK)
    try {
      annotation.setFlags(PDFAnnotation.IS_PRINT)
      annotation.setName(stroke.id.toString())
      if (annotation.hasAuthor()) annotation.setAuthor(AUTHOR)
      if (annotation.hasSubject()) annotation.setSubject(stroke.stroke.style.penType.name)
      if (stroke.createdAt > 0L) annotation.setCreationDate(Date(stroke.createdAt))
      if (stroke.updatedAt > 0L) annotation.setModificationDate(Date(stroke.updatedAt))
      annotation.setColor(floatArrayOf(ink.red, ink.green, ink.blue))
      annotation.setOpacity(ink.opacity)
      annotation.setBorderStyle(PDFAnnotation.BORDER_STYLE_SOLID)
      // MuPDF derives an Ink annotation's /Rect from /InkList and border width.
      // Give that calculation an anti-alias margin, then restore the semantic
      // maximum width before replacing the generated appearance.
      annotation.setBorderWidth(ink.borderWidth + RECT_ANTIALIAS_MARGIN * 2f)
      annotation.setInkList(
        arrayOf(ink.inkList.map { Point(it.x, it.y) }.toTypedArray()),
      )

      // Let MuPDF establish the standard Ink dictionary and a conservative annotation rect first.
      annotation.update()
      val rect = annotation.bounds
      val bounds = PdfInkBounds(
        left = rect.x0,
        top = rect.y0,
        right = max(rect.x1, rect.x0 + MIN_APPEARANCE_SIZE),
        bottom = max(rect.y1, rect.y0 + MIN_APPEARANCE_SIZE),
      )
      annotation.setBorderWidth(ink.borderWidth)
      val appearance = ink.buildAppearance(bounds)
      replaceNormalAppearance(document, annotation, bounds, appearance)
    } finally {
      annotation.destroy()
    }
  }

  private fun replaceNormalAppearance(
    document: PDFDocument,
    annotation: PDFAnnotation,
    bounds: PdfInkBounds,
    appearance: PdfInkAppearanceContent,
  ) {
    var resources: PDFObject? = null
    var extGState: PDFObject? = null
    val graphicsStates = mutableListOf<PDFObject>()
    val buffer = Buffer()
    try {
      resources = document.newDictionary()
      if (appearance.opacityBuckets.isNotEmpty()) {
        extGState = document.newDictionary()
        appearance.opacityBuckets.sorted().forEach { bucket ->
          val state = document.newDictionary()
          graphicsStates.add(state)
          val typeName = document.newName("ExtGState")
          try {
            state.put("Type", typeName)
          } finally {
            typeName.destroy()
          }
          val opacity = bucket / 100f
          state.put("CA", opacity)
          state.put("ca", opacity)
          extGState.put(PdfInkAppearance.gsName(bucket), state)
        }
        resources.put("ExtGState", extGState)
      }
      buffer.writeBytes(appearance.bytes)
      annotation.setAppearance(
        "N",
        null,
        Matrix.Identity(),
        Rect(0f, 0f, bounds.width, bounds.height),
        resources,
        buffer,
      )
    } finally {
      buffer.destroy()
      graphicsStates.forEach(PDFObject::destroy)
      extGState?.destroy()
      resources?.destroy()
    }
  }

  private fun openPdf(file: File): PDFDocument {
    require(file.isFile) { "PDF does not exist: ${file.absolutePath}" }
    val document = Document.openDocument(file.absolutePath)
    return document as? PDFDocument
      ?: run {
        document.destroy()
        error("Not a PDF document: ${file.absolutePath}")
      }
  }

  private companion object {
    const val AUTHOR = "BetterHvNote"
    const val MIN_APPEARANCE_SIZE = 0.1f
    const val RECT_ANTIALIAS_MARGIN = 1f
  }
}
