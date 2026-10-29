package com.betterhv.note.export

import android.app.Application
import android.content.ContentUris
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.PenType
import com.betterhv.note.ink.Stroke
import com.betterhv.note.pdf.MuPdfEngine
import com.betterhv.note.pdf.PdfAssetStore
import com.betterhv.note.storage.DocumentChange
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.storage.PdfImportCommit
import com.betterhv.note.storage.PdfImportedPage
import com.betterhv.note.template.TemplateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExportFastModeInstrumentedTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()

  @Test fun fastModeWritesOneRasterImagePerPageWhileVectorKeepsEditableInk() = runBlocking {
    val notebooks = NotebookRepository(app)
    val tasks = ExportTaskRepository(app)
    val root = File(app.cacheDir, "export-fast-${UUID.randomUUID()}")
    val engine = ExportEngine(
      notebooks,
      tasks,
      PageRenderer(File(app.filesDir, "documents"), TemplateStore.get(app)),
      root,
    )
    val seeded = seedInkNotebook(notebooks, "Fast mode ${UUID.randomUUID()}", pageCount = 2, strokesPerPage = 5)
    val vector = tasks.createTask(seeded.id, ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList(), ExportRenderMode.VECTOR)
    val fast = tasks.createTask(seeded.id, ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList(), ExportRenderMode.FAST)
    try {
      val vectorResult = engine.generate(vector.id) as ExportResult.Success
      val fastResult = engine.generate(fast.id) as ExportResult.Success

      openPdf(vectorResult.artifact.file) { document ->
        assertEquals(2, document.countPages())
        repeat(2) { index ->
          val page = document.loadPage(index) as PDFPage
          try {
            val annotations = page.annotations.orEmpty()
            try {
              assertEquals("vector pages keep one annotation per stroke", 5, annotations.size)
              annotations.forEach { annotation ->
                assertEquals(PDFAnnotation.TYPE_INK, annotation.type)
                assertTrue(annotation.hasInkList())
              }
            } finally {
              annotations.forEach(PDFAnnotation::destroy)
            }
          } finally {
            page.destroy()
          }
        }
      }

      openPdf(fastResult.artifact.file) { document ->
        assertEquals(2, document.countPages())
        repeat(2) { index ->
          val page = document.loadPage(index) as PDFPage
          try {
            assertEquals(450f, page.bounds.x1 - page.bounds.x0, 1f)
            assertEquals(600f, page.bounds.y1 - page.bounds.y0, 1f)
            assertTrue("raster pages carry no per-stroke annotations", page.annotations.orEmpty().isEmpty())
          } finally {
            page.destroy()
          }
        }
      }

      val rendered = MuPdfEngine().renderPage(fastResult.artifact.file, 0, 300)
      try {
        val pixels = IntArray(rendered.width * rendered.height)
        rendered.getPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
        assertTrue("fast page must contain visible ink", pixels.any { pixel -> Color.red(pixel) < 200 })
      } finally {
        rendered.recycle()
      }

      val report = requireNotNull(tasks.runReport(fast.id))
      assertEquals(ExportRenderMode.FAST, report.mode)
      assertEquals(2, report.cacheMisses)
      assertTrue(report.rasterScale > 1.9f)
      assertEquals(2, report.pageCount)
      assertEquals(ExportRenderMode.VECTOR, requireNotNull(tasks.runReport(vector.id)).mode)
      assertTrue(fastResult.artifact.byteLength > 0)
      assertTrue(vectorResult.artifact.byteLength > 0)
      verifyDownloadsPublication(fastResult.artifact)
    } finally {
      runCatching { engine.deleteTask(vector.id) }
      runCatching { engine.deleteTask(fast.id) }
      notebooks.deleteNotebook(seeded.id)
      root.deleteRecursively()
      tasks.close()
      notebooks.close()
    }
  }

  @Test fun fastModeRendersPdfSourceOnceAndOverlaysInk() = runBlocking {
    val notebooks = NotebookRepository(app)
    val tasks = ExportTaskRepository(app)
    val root = File(app.cacheDir, "export-fast-pdf-${UUID.randomUUID()}")
    val engine = ExportEngine(
      notebooks,
      tasks,
      PageRenderer(File(app.filesDir, "documents"), TemplateStore.get(app)),
      root,
    )
    val sourcePdf = File(app.cacheDir, "pdf-source-${UUID.randomUUID()}.pdf")
    writeSourcePdf(sourcePdf)
    notebooks.workingNotebookId() // keep the imported notebook deletable in cleanup
    val pdfAssets = PdfAssetStore(app)
    val committed = pdfAssets.commit(pdfAssets.stageFile(sourcePdf, "source.pdf"))
    val pdfNotebookId = notebooks.createPdfNotebook(
      PdfImportCommit(
        title = "PDF source ${UUID.randomUUID()}",
        assetPath = committed.relativePath,
        displayName = committed.displayName,
        byteLength = committed.byteLength,
        sha256 = committed.sha256,
        pages = listOf(
          PdfImportedPage(
            width = 400f,
            height = 533.3333f,
            source = com.betterhv.note.doc.PdfPageSource(
              sourcePageIndex = 0,
              bounds = Bounds(0f, 0f, 300f, 400f),
              pdfToPage = Transform2D(4f / 3f, 0f, 0f, 4f / 3f, 0f, 0f),
            ),
          ),
        ),
      ),
    )
    val pageId = requireNotNull(notebooks.loadNotebook(pdfNotebookId)).pageOrder.single()
    val page = requireNotNull(notebooks.loadPage(pageId))
    page.addObject(
      StrokeObject(
        id = UUID.randomUUID(),
        transform = Transform2D.IDENTITY,
        zIndex = 0,
        createdAt = 1L,
        updatedAt = 1L,
        stroke = Stroke(
          points = listOf(InkPoint(50f, 450f, 1f, 1L), InkPoint(350f, 450f, 1f, 2L)),
          style = PenStyle(baseWidth = 14f, color = Color.BLACK, penType = PenType.Marker),
        ),
      ),
    )
    val notebook = requireNotNull(notebooks.loadNotebook(pdfNotebookId))
    notebook.attachPage(page)
    notebooks.persist(DocumentChange.fullPage(notebook, page, "test:pdf-source-ink"))

    val vector = tasks.createTask(
      pdfNotebookId, ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList(), ExportRenderMode.VECTOR,
    )
    val fast = tasks.createTask(
      pdfNotebookId, ExportScope.ALL_PAGES, ExportFormat.PDF, emptyList(), ExportRenderMode.FAST,
    )
    try {
      val vectorResult = engine.generate(vector.id) as ExportResult.Success
      val fastResult = engine.generate(fast.id) as ExportResult.Success

      openPdf(vectorResult.artifact.file) { document ->
        val vectorPage = document.loadPage(0) as PDFPage
        try {
          assertEquals(300f, vectorPage.bounds.x1 - vectorPage.bounds.x0, 1f)
          assertEquals(400f, vectorPage.bounds.y1 - vectorPage.bounds.y0, 1f)
          assertEquals(1, vectorPage.annotations.orEmpty().size)
        } finally {
          vectorPage.destroy()
        }
      }

      openPdf(fastResult.artifact.file) { document ->
        val fastPage = document.loadPage(0) as PDFPage
        try {
          assertEquals(300f, fastPage.bounds.x1 - fastPage.bounds.x0, 1f)
          assertEquals(400f, fastPage.bounds.y1 - fastPage.bounds.y0, 1f)
          assertTrue(fastPage.annotations.orEmpty().isEmpty())
        } finally {
          fastPage.destroy()
        }
      }

      val rendered = MuPdfEngine().renderPage(fastResult.artifact.file, 0, 300)
      try {
        val pixels = IntArray(rendered.width * rendered.height)
        rendered.getPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
        fun darkIn(top: Int, bottom: Int): Boolean {
          for (y in top until bottom) {
            for (x in 0 until rendered.width) {
              if (Color.red(pixels[y * rendered.width + x]) < 100) return true
            }
          }
          return false
        }
        assertTrue("PDF source background must survive rasterization", darkIn(0, 60))
        assertTrue("ink must be composited onto the PDF source bitmap", darkIn(300, 380))
      } finally {
        rendered.recycle()
      }
    } finally {
      runCatching { engine.deleteTask(vector.id) }
      runCatching { engine.deleteTask(fast.id) }
      notebooks.deleteNotebook(pdfNotebookId)
      pdfAssets.discard(committed)
      sourcePdf.delete()
      root.deleteRecursively()
      tasks.close()
      notebooks.close()
    }
  }

  private fun writeSourcePdf(file: File) {
    val document = PdfDocument()
    try {
      val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
      page.canvas.drawColor(Color.WHITE)
      page.canvas.drawRect(
        20f, 20f, 70f, 70f,
        Paint().apply { color = Color.BLACK },
      )
      document.finishPage(page)
      FileOutputStream(file).use(document::writeTo)
    } finally {
      document.close()
    }
  }

  /** Fast artifacts go through the same MediaStore publication path as vector ones. */
  private fun verifyDownloadsPublication(artifact: ExportArtifact) {
    val downloads = ExportDownloads(app)
    val displayName = downloads.save(artifact)
    assertEquals(artifact.displayName, displayName)
    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/BetterHvNote/"
    try {
      val uri = app.contentResolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
        arrayOf(displayName, relativePath),
        null,
      )?.use { cursor ->
        if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
      }
      assertTrue("fast artifact must be published to Downloads", uri != null)
      val published = app.contentResolver.openInputStream(requireNotNull(uri))!!.use { it.readBytes() }
      assertArrayEquals(artifact.file.readBytes(), published)
    } finally {
      app.contentResolver.delete(
        collection,
        "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
        arrayOf(displayName, relativePath),
      )
    }
  }
}
