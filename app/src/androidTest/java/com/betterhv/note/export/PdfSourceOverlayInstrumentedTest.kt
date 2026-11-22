package com.betterhv.note.export

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.PdfPageSource
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.TextFontFamily
import com.betterhv.note.doc.TextObject
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.PenType
import com.betterhv.note.ink.Stroke
import com.betterhv.note.pdf.MuPdfEngine
import com.betterhv.note.pdf.PdfAssetStore
import com.betterhv.note.storage.DocumentChange
import com.betterhv.note.storage.ImageAssetStore
import com.betterhv.note.storage.ImportedImage
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.storage.PdfImportCommit
import com.betterhv.note.storage.PdfImportedPage
import com.betterhv.note.template.TemplateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PdfSourceOverlayInstrumentedTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()

  @Test fun pdfSourcePngExportHasOpaqueWhiteCornersAndDrawsStrokeAboveImage() = runBlocking {
    val notebooks = NotebookRepository(app)
    val tasks = ExportTaskRepository(app)
    val root = File(app.cacheDir, "export-pdf-png-${UUID.randomUUID()}")
    val engine = ExportEngine(
      notebooks,
      tasks,
      PageRenderer(File(app.filesDir, "documents"), TemplateStore.get(app)),
      root,
    )
    val sourcePdf = File(app.cacheDir, "pdf-transparent-${UUID.randomUUID()}.pdf")
    writeSourcePdf(sourcePdf, fillWhite = false)
    notebooks.workingNotebookId()
    val pdfAssets = PdfAssetStore(app)
    val committedPdf = pdfAssets.commit(pdfAssets.stageFile(sourcePdf, "source.pdf"))
    val imageAssets = ImageAssetStore(app)
    var committedImage: ImportedImage? = null
    val pdfNotebookId = notebooks.createPdfNotebook(
      PdfImportCommit(
        title = "PDF PNG overlay ${UUID.randomUUID()}",
        assetPath = committedPdf.relativePath,
        displayName = committedPdf.displayName,
        byteLength = committedPdf.byteLength,
        sha256 = committedPdf.sha256,
        pages = listOf(
          PdfImportedPage(
            width = LOGICAL_WIDTH,
            height = LOGICAL_HEIGHT,
            source = PdfPageSource(
              sourcePageIndex = 0,
              bounds = Bounds(0f, 0f, 300f, 400f),
              pdfToPage = Transform2D(4f / 3f, 0f, 0f, 4f / 3f, 0f, 0f),
            ),
          ),
        ),
      ),
    )
    var taskId: UUID? = null
    try {
      val blueBitmap = solidBitmap(80, 80, Color.BLUE)
      committedImage = try {
        imageAssets.commit(imageAssets.importPng(blueBitmap))
      } finally {
        blueBitmap.recycle()
      }

      val notebook = requireNotNull(notebooks.loadNotebook(pdfNotebookId))
      val pageId = notebook.pageOrder.single()
      val page = requireNotNull(notebooks.loadPage(pageId))
      val imageMeta = requireNotNull(committedImage)
      page.addObject(
        ImageObject(
          id = UUID.randomUUID(),
          transform = Transform2D.translate(140f, 180f),
          zIndex = page.nextZIndex(),
          assetPath = imageMeta.relativePath,
          mimeType = imageMeta.mimeType,
          pixelWidth = imageMeta.pixelWidth,
          pixelHeight = imageMeta.pixelHeight,
          exifOrientation = imageMeta.exifOrientation,
        ),
      )
      page.addObject(
        StrokeObject(
          id = UUID.randomUUID(),
          transform = Transform2D.IDENTITY,
          zIndex = page.nextZIndex(),
          createdAt = 2L,
          updatedAt = 2L,
          stroke = Stroke(
            points = listOf(InkPoint(120f, 220f, 1f, 1L), InkPoint(240f, 220f, 1f, 2L)),
            style = PenStyle(baseWidth = 24f, color = Color.BLACK, penType = PenType.Marker),
          ),
        ),
      )
      notebook.attachPage(page)
      notebooks.persist(DocumentChange.fullPage(notebook, page, "test:pdf-png-zorder"))

      val pngTask = tasks.createTask(
        pdfNotebookId,
        ExportScope.SINGLE_PAGE,
        ExportFormat.PNG,
        listOf(pageId),
        ExportRenderMode.VECTOR,
      )
      taskId = pngTask.id

      val result = engine.generate(pngTask.id) as ExportResult.Success
      val exported = requireNotNull(BitmapFactory.decodeFile(result.artifact.file.absolutePath))
      try {
        val corners = listOf(
          0 to 0,
          (exported.width - 1) to 0,
          0 to (exported.height - 1),
          (exported.width - 1) to (exported.height - 1),
        )
        corners.forEach { (x, y) ->
          val pixel = exported[x, y]
          assertEquals("corner ($x,$y) must be opaque", 255, Color.alpha(pixel))
          assertEquals("corner ($x,$y) must be white", Color.WHITE, pixel)
        }

        val overlapX = (180f / LOGICAL_WIDTH * exported.width).toInt()
        val overlapY = (220f / LOGICAL_HEIGHT * exported.height).toInt()
        val overlapPixel = exported[overlapX, overlapY]
        assertTrue(
          "stroke drawn after image must render on top of image",
          Color.red(overlapPixel) < 40 && Color.green(overlapPixel) < 40 && Color.blue(overlapPixel) < 40,
        )

        val imageOnlyX = (160f / LOGICAL_WIDTH * exported.width).toInt()
        val imageOnlyY = (195f / LOGICAL_HEIGHT * exported.height).toInt()
        val imagePixel = exported[imageOnlyX, imageOnlyY]
        assertTrue(
          "image underneath stroke must remain visible away from stroke",
          Color.blue(imagePixel) > 200 && Color.red(imagePixel) < 40,
        )
      } finally {
        exported.recycle()
      }
    } finally {
      taskId?.let { id -> runCatching { engine.deleteTask(id) } }
      committedImage?.let(imageAssets::discard)
      notebooks.deleteNotebook(pdfNotebookId)
      pdfAssets.discard(committedPdf)
      sourcePdf.delete()
      root.deleteRecursively()
      tasks.close()
      notebooks.close()
    }
  }

  @Test fun pdfSourceVectorPdfPreservesTextImageAndInkAnnotations() = runBlocking {
    val notebooks = NotebookRepository(app)
    val tasks = ExportTaskRepository(app)
    val root = File(app.cacheDir, "export-pdf-vector-overlay-${UUID.randomUUID()}")
    val engine = ExportEngine(
      notebooks,
      tasks,
      PageRenderer(File(app.filesDir, "documents"), TemplateStore.get(app)),
      root,
    )
    val sourcePdf = File(app.cacheDir, "pdf-vector-src-${UUID.randomUUID()}.pdf")
    writeSourcePdf(sourcePdf, fillWhite = true)
    notebooks.workingNotebookId()
    val pdfAssets = PdfAssetStore(app)
    val committedPdf = pdfAssets.commit(pdfAssets.stageFile(sourcePdf, "source.pdf"))
    val imageAssets = ImageAssetStore(app)
    var committedImage: ImportedImage? = null
    val pdfNotebookId = notebooks.createPdfNotebook(
      PdfImportCommit(
        title = "PDF vector overlay ${UUID.randomUUID()}",
        assetPath = committedPdf.relativePath,
        displayName = committedPdf.displayName,
        byteLength = committedPdf.byteLength,
        sha256 = committedPdf.sha256,
        pages = listOf(
          PdfImportedPage(
            width = LOGICAL_WIDTH,
            height = LOGICAL_HEIGHT,
            source = PdfPageSource(
              sourcePageIndex = 0,
              bounds = Bounds(0f, 0f, 300f, 400f),
              pdfToPage = Transform2D(4f / 3f, 0f, 0f, 4f / 3f, 0f, 0f),
            ),
          ),
        ),
      ),
    )
    var taskId: UUID? = null
    try {
      val blackBitmap = solidBitmap(60, 60, Color.BLACK)
      committedImage = try {
        imageAssets.commit(imageAssets.importPng(blackBitmap))
      } finally {
        blackBitmap.recycle()
      }

      val notebook = requireNotNull(notebooks.loadNotebook(pdfNotebookId))
      val pageId = notebook.pageOrder.single()
      val page = requireNotNull(notebooks.loadPage(pageId))
      page.addObject(
        TextObject(
          id = UUID.randomUUID(),
          transform = Transform2D.translate(60f, 140f),
          zIndex = page.nextZIndex(),
          text = "HvOverlayMarker",
          fontFamily = TextFontFamily.SANS_SERIF,
          fontSize = 28f,
          localBounds = Bounds(0f, 0f, 260f, 40f),
        ),
      )
      val imageMeta = requireNotNull(committedImage)
      page.addObject(
        ImageObject(
          id = UUID.randomUUID(),
          transform = Transform2D.translate(180f, 260f),
          zIndex = page.nextZIndex(),
          assetPath = imageMeta.relativePath,
          mimeType = imageMeta.mimeType,
          pixelWidth = imageMeta.pixelWidth,
          pixelHeight = imageMeta.pixelHeight,
          exifOrientation = imageMeta.exifOrientation,
        ),
      )
      page.addObject(
        StrokeObject(
          id = UUID.randomUUID(),
          transform = Transform2D.IDENTITY,
          zIndex = page.nextZIndex(),
          createdAt = 3L,
          updatedAt = 3L,
          stroke = Stroke(
            points = listOf(InkPoint(60f, 420f, 1f, 1L), InkPoint(340f, 420f, 1f, 2L)),
            style = PenStyle(baseWidth = 14f, color = Color.BLACK, penType = PenType.Marker),
          ),
        ),
      )
      notebook.attachPage(page)
      notebooks.persist(DocumentChange.fullPage(notebook, page, "test:pdf-vector-overlay"))

      val vectorTask = tasks.createTask(
        pdfNotebookId,
        ExportScope.ALL_PAGES,
        ExportFormat.PDF,
        emptyList(),
        ExportRenderMode.VECTOR,
      )
      taskId = vectorTask.id

      val result = engine.generate(vectorTask.id) as ExportResult.Success
      val outputPdf = result.artifact.file

      openPdf(outputPdf) { document ->
        assertEquals(1, document.countPages())
        val pdfPage = document.loadPage(0) as PDFPage
        try {
          assertEquals(300f, pdfPage.bounds.x1 - pdfPage.bounds.x0, 1f)
          assertEquals(400f, pdfPage.bounds.y1 - pdfPage.bounds.y0, 1f)
          val annotations = pdfPage.annotations.orEmpty()
          try {
            assertEquals(1, annotations.size)
            assertEquals(PDFAnnotation.TYPE_INK, annotations[0].type)
            assertTrue(annotations[0].hasInkList())
          } finally {
            annotations.forEach(PDFAnnotation::destroy)
          }
        } finally {
          pdfPage.destroy()
        }
      }

      val muPdf = MuPdfEngine()
      val extractedText = muPdf.extractText(outputPdf, 0, Bounds(0f, 0f, 1f, 1f))
      assertTrue(
        "Vector PDF must preserve TextObject text, got '$extractedText'",
        extractedText.contains("HvOverlayMarker"),
      )

      val rendered = muPdf.renderPage(outputPdf, 0, 400)
      try {
        assertTrue(
          "Source PDF content must survive overlay compositing",
          hasDarkPixelInRegion(rendered, 30, 30, 85, 85),
        )
        assertTrue(
          "Vector PDF must preserve ImageObject pixels",
          hasDarkPixelInRegion(rendered, 190, 270, 230, 310),
        )
      } finally {
        rendered.recycle()
      }
    } finally {
      taskId?.let { id -> runCatching { engine.deleteTask(id) } }
      committedImage?.let(imageAssets::discard)
      notebooks.deleteNotebook(pdfNotebookId)
      pdfAssets.discard(committedPdf)
      sourcePdf.delete()
      root.deleteRecursively()
      tasks.close()
      notebooks.close()
    }
  }

  private fun hasDarkPixelInRegion(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Boolean {
    val minX = left.coerceIn(0, bitmap.width - 1)
    val minY = top.coerceIn(0, bitmap.height - 1)
    val maxX = right.coerceIn(minX + 1, bitmap.width)
    val maxY = bottom.coerceIn(minY + 1, bitmap.height)
    for (y in minY until maxY) {
      for (x in minX until maxX) {
        val pixel = bitmap[x, y]
        if (Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) {
          return true
        }
      }
    }
    return false
  }

  private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
    createBitmap(width, height).also { bmp ->
      Canvas(bmp).drawColor(color)
    }

  private fun writeSourcePdf(file: File, fillWhite: Boolean) {
    val document = PdfDocument()
    try {
      val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
      if (fillWhite) page.canvas.drawColor(Color.WHITE)
      page.canvas.drawRect(
        20f,
        20f,
        70f,
        70f,
        Paint().apply { color = Color.BLACK },
      )
      document.finishPage(page)
      FileOutputStream(file).use(document::writeTo)
    } finally {
      document.close()
    }
  }

  private companion object {
    const val LOGICAL_WIDTH = 400f
    const val LOGICAL_HEIGHT = 533.3333f
  }
}
