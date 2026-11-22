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
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.storage.PageSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DecimalFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max

/** Computes the PDF user-space Form XObject matrix placing an overlay page over a source page. */
internal object PdfOverlayTransform {
  fun formMatrix(
    pageTransform: Transform2D,
    pageBoundsLeft: Float,
    pageBoundsTop: Float,
    pageBoundsRight: Float,
    pageBoundsBottom: Float,
    overlayWidth: Float,
    overlayHeight: Float,
  ): Transform2D {
    val pageWidth = pageBoundsRight - pageBoundsLeft
    val pageHeight = pageBoundsBottom - pageBoundsTop
    if (
      !pageWidth.isFinite() || !pageHeight.isFinite() ||
      !overlayWidth.isFinite() || !overlayHeight.isFinite() ||
      pageWidth <= 0f || pageHeight <= 0f ||
      overlayWidth <= 0f || overlayHeight <= 0f
    ) {
      return Transform2D.IDENTITY
    }
    val fitzFromForm = Transform2D(
      a = pageWidth / overlayWidth,
      b = 0f,
      c = 0f,
      d = -pageHeight / overlayHeight,
      tx = pageBoundsLeft,
      ty = pageBoundsBottom,
    )
    val inversePageTransform = pageTransform.invert() ?: return Transform2D.IDENTITY
    return inversePageTransform.times(fitzFromForm)
  }
}

/** Save profiles: only the final artifact pays for the expensive deep dedupe. */
object PdfSaveOptions {
  const val PAGE = "garbage=2,compress"
  const val FINAL = "garbage=3,compress"
}

data class PdfMergeTiming(val graftMs: Long, val saveMs: Long)

/** Writes editable PDF Ink annotations and joins annotated pages without rasterizing ink. */
class PdfInkAnnotationWriter {
  /**
   * Composes one PDF source page, optional non-ink overlay PDF, and its ink
   * annotations in a single document, then saves once.
   */
  fun writeAnnotatedSourcePage(
    sourcePdf: File,
    sourcePageIndex: Int,
    snapshot: PageSnapshot,
    outputPdf: File,
    checkActive: () -> Unit = {},
    overlayPdf: File? = null,
  ) {
    outputPdf.parentFile?.mkdirs()
    val source = openPdf(sourcePdf)
    var destination: PDFDocument? = null
    var page: PDFPage? = null
    try {
      destination = PDFDocument()
      require(sourcePageIndex in 0 until source.countPages()) { "PDF source page is out of range" }
      destination.graftPage(0, source, sourcePageIndex)
      graftAnnotations(destination, 0, source, sourcePageIndex)
      page = destination.loadPage(0) as? PDFPage ?: error("Grafted page is not a PDF page")
      if (overlayPdf != null) {
        checkActive()
        graftOverlay(destination, page, overlayPdf)
      }
      writeInkAnnotations(destination, page, snapshot, checkActive)
      destination.save(outputPdf.absolutePath, PdfSaveOptions.PAGE)
    } catch (error: Throwable) {
      outputPdf.delete()
      throw error
    } finally {
      page?.destroy()
      destination?.destroy()
      source.destroy()
    }
  }

  fun addInkAnnotations(
    basePdf: File,
    snapshot: PageSnapshot,
    outputPdf: File,
    checkActive: () -> Unit = {},
  ) {
    outputPdf.parentFile?.mkdirs()
    val document = openPdf(basePdf)
    var page: PDFPage? = null
    try {
      page = document.loadPage(0) as PDFPage
      writeInkAnnotations(document, page, snapshot, checkActive)
      document.save(outputPdf.absolutePath, PdfSaveOptions.PAGE)
    } catch (error: Throwable) {
      outputPdf.delete()
      throw error
    } finally {
      page?.destroy()
      document.destroy()
    }
  }

  /**
   * Grafts all page files into one document. [onPage] receives the number of
   * processed *files* (page caches are single-page files), and [onSaveStart]
   * lets the caller switch to the indeterminate writing stage before the
   * uninterruptible native save begins.
   */
  fun mergePages(
    pageFiles: List<File>,
    outputPdf: File,
    onPage: (processed: Int, total: Int) -> Unit = { _, _ -> },
    onSaveStart: () -> Unit = {},
    checkActive: () -> Unit = {},
  ): PdfMergeTiming {
    require(pageFiles.isNotEmpty()) { "At least one PDF page is required" }
    outputPdf.parentFile?.mkdirs()
    val destination = PDFDocument()
    val graftStart = System.nanoTime()
    try {
      pageFiles.forEachIndexed { index, pageFile ->
        checkActive()
        val source = openPdf(pageFile)
        try {
          repeat(source.countPages()) { pageNumber ->
            checkActive()
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
        onPage(index + 1, pageFiles.size)
      }
      val graftMs = (System.nanoTime() - graftStart) / 1_000_000
      onSaveStart()
      val saveStart = System.nanoTime()
      destination.save(outputPdf.absolutePath, PdfSaveOptions.FINAL)
      val saveMs = (System.nanoTime() - saveStart) / 1_000_000
      return PdfMergeTiming(graftMs, saveMs)
    } catch (error: Throwable) {
      outputPdf.delete()
      throw error
    } finally {
      destination.destroy()
    }
  }

  private fun writeInkAnnotations(
    document: PDFDocument,
    page: PDFPage,
    snapshot: PageSnapshot,
    checkActive: () -> Unit,
  ) {
    val formatter = PdfInkAppearance.newFormatter()
    snapshot.objects
      .asSequence()
      .filterIsInstance<StrokeObject>()
      .sortedBy { it.zIndex }
      .forEach { stroke ->
        checkActive()
        addAnnotation(document, page, stroke, formatter)
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

  private fun graftOverlay(
    destination: PDFDocument,
    destPdfPage: PDFPage,
    overlayPdf: File,
  ) {
    val overlay = openPdf(overlayPdf)
    var overlayPage: PDFPage? = null
    var overlayPageObj: PDFObject? = null
    var overlayContents: PDFObject? = null
    var overlayResources: PDFObject? = null
    var graftedResources: PDFObject? = null
    var formDict: PDFObject? = null
    var typeName: PDFObject? = null
    var subtypeName: PDFObject? = null
    var bbox: PDFObject? = null
    var formBuffer: Buffer? = null
    var formRef: PDFObject? = null
    var destPageObj: PDFObject? = null
    var destResources: PDFObject? = null
    var createdDestResources: PDFObject? = null
    var xobjDict: PDFObject? = null
    var createdXObjDict: PDFObject? = null
    var emptyDictQ: PDFObject? = null
    var qBuffer: Buffer? = null
    var qStream: PDFObject? = null
    var emptyDictInvoke: PDFObject? = null
    var invokeBuffer: Buffer? = null
    var invokeStream: PDFObject? = null
    var existingContents: PDFObject? = null
    var newContents: PDFObject? = null
    try {
      if (overlay.countPages() <= 0) return
      overlayPage = overlay.loadPage(0) as? PDFPage ?: error("Overlay page is not a PDF page")
      val overlayBounds = overlayPage.bounds
      val overlayWidth = overlayBounds.x1 - overlayBounds.x0
      val overlayHeight = overlayBounds.y1 - overlayBounds.y0
      if (overlayWidth <= 0f || overlayHeight <= 0f) return

      overlayPageObj = overlay.findPage(0)
      overlayContents = overlayPageObj.get("Contents")
      val overlayBytes = readContentBytes(overlayContents)
      if (overlayBytes.isEmpty()) return

      overlayResources = overlayPageObj.get("Resources")
      graftedResources = if (!overlayResources.isNull) {
        destination.graftObject(overlayResources)
      } else {
        destination.newDictionary()
      }

      formDict = destination.newDictionary()
      typeName = destination.newName("XObject")
      subtypeName = destination.newName("Form")
      formDict.put("Type", typeName)
      formDict.put("Subtype", subtypeName)
      formDict.put("FormType", 1)
      bbox = destination.newArray()
      bbox.push(0f)
      bbox.push(0f)
      bbox.push(overlayWidth)
      bbox.push(overlayHeight)
      formDict.put("BBox", bbox)
      formDict.put("Resources", graftedResources)

      formBuffer = Buffer()
      formBuffer.writeBytes(overlayBytes)
      formRef = destination.addStream(formBuffer, formDict)

      destPageObj = destination.findPage(0)
      destResources = destPageObj.get("Resources")
      val activeResources = if (!destResources.isNull) {
        destResources
      } else {
        destination.newDictionary().also {
          createdDestResources = it
          destPageObj.put("Resources", it)
        }
      }
      xobjDict = activeResources.get("XObject")
      val activeXObjDict = if (!xobjDict.isNull) {
        xobjDict
      } else {
        destination.newDictionary().also {
          createdXObjDict = it
          activeResources.put("XObject", it)
        }
      }

      val xobjName = allocateOverlayName(activeXObjDict)
      activeXObjDict.put(xobjName, formRef)

      val destBounds = destPdfPage.bounds
      val destTransform = destPdfPage.transform
      val placement = PdfOverlayTransform.formMatrix(
        pageTransform = Transform2D(
          a = destTransform.a,
          b = destTransform.b,
          c = destTransform.c,
          d = destTransform.d,
          tx = destTransform.e,
          ty = destTransform.f,
        ),
        pageBoundsLeft = destBounds.x0,
        pageBoundsTop = destBounds.y0,
        pageBoundsRight = destBounds.x1,
        pageBoundsBottom = destBounds.y1,
        overlayWidth = overlayWidth,
        overlayHeight = overlayHeight,
      )
      val formatter = PdfInkAppearance.newFormatter()
      fun fmt(value: Float): String = formatter.format(if (abs(value) < 0.00005f) 0f else value)
      val invokeBytes = (
        "\nQ\nq\n${fmt(placement.a)} ${fmt(placement.b)} ${fmt(placement.c)} ${fmt(placement.d)} " +
          "${fmt(placement.tx)} ${fmt(placement.ty)} cm\n/$xobjName Do\nQ\n"
        ).toByteArray(Charsets.US_ASCII)

      emptyDictQ = destination.newDictionary()
      qBuffer = Buffer()
      qBuffer.writeBytes("q\n".toByteArray(Charsets.US_ASCII))
      qStream = destination.addStream(qBuffer, emptyDictQ)

      emptyDictInvoke = destination.newDictionary()
      invokeBuffer = Buffer()
      invokeBuffer.writeBytes(invokeBytes)
      invokeStream = destination.addStream(invokeBuffer, emptyDictInvoke)

      existingContents = destPageObj.get("Contents")
      newContents = destination.newArray()
      newContents.push(qStream)
      if (!existingContents.isNull) {
        if (existingContents.isArray) {
          for (i in 0 until existingContents.size()) {
            val part = existingContents.get(i)
            try {
              newContents.push(part)
            } finally {
              part.destroy()
            }
          }
        } else {
          newContents.push(existingContents)
        }
      }
      newContents.push(invokeStream)
      destPageObj.put("Contents", newContents)
    } finally {
      newContents?.destroy()
      existingContents?.destroy()
      invokeStream?.destroy()
      invokeBuffer?.destroy()
      emptyDictInvoke?.destroy()
      qStream?.destroy()
      qBuffer?.destroy()
      emptyDictQ?.destroy()
      createdXObjDict?.destroy()
      xobjDict?.destroy()
      createdDestResources?.destroy()
      destResources?.destroy()
      destPageObj?.destroy()
      formRef?.destroy()
      formBuffer?.destroy()
      bbox?.destroy()
      subtypeName?.destroy()
      typeName?.destroy()
      formDict?.destroy()
      graftedResources?.destroy()
      overlayResources?.destroy()
      overlayContents?.destroy()
      overlayPageObj?.destroy()
      overlayPage?.destroy()
      overlay.destroy()
    }
  }

  private fun readContentBytes(contents: PDFObject): ByteArray {
    if (contents.isNull) return ByteArray(0)
    if (contents.isStream) return contents.readStream()
    if (!contents.isArray) return ByteArray(0)
    val output = ByteArrayOutputStream()
    for (i in 0 until contents.size()) {
      val part = contents.get(i)
      try {
        if (part.isStream) {
          val bytes = part.readStream()
          if (bytes.isNotEmpty()) {
            if (output.size() > 0) output.write('\n'.code)
            output.write(bytes)
          }
        }
      } finally {
        part.destroy()
      }
    }
    return output.toByteArray()
  }

  private fun allocateOverlayName(xobjDict: PDFObject): String {
    var index = 0
    while (true) {
      val candidateName = "HvOverlay$index"
      val existing = xobjDict.get(candidateName)
      val occupied = !existing.isNull
      existing.destroy()
      if (!occupied) return candidateName
      index++
    }
  }

  private fun addAnnotation(
    document: PDFDocument,
    page: PDFPage,
    stroke: StrokeObject,
    formatter: DecimalFormat,
  ) {
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
      val appearance = ink.buildAppearance(bounds, formatter)
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
