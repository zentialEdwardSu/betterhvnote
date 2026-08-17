package com.betterhv.note.export

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.betterhv.note.doc.Page
import com.betterhv.note.storage.PageSnapshot
import com.itextpdf.text.Document
import com.itextpdf.text.pdf.PdfCopy
import com.itextpdf.text.pdf.PdfReader
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageRendererInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val renderer = PageRenderer(File(context.filesDir, "documents"))

    @Test fun emptyPagePngIsWhiteAtExportResolution() {
        val bitmap = renderer.renderToBitmap(PageSnapshot.capture(Page(width = 800f, height = 1280f)), 1600, 2560)
        try {
            assertEquals(1600, bitmap.width)
            assertEquals(2560, bitmap.height)
            assertEquals(Color.WHITE, bitmap.getPixel(800, 1280))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun singlePagePdfPreservesExpectedPageSize() {
        val file = File(context.cacheDir, "renderer-${System.nanoTime()}.pdf")
        try {
            renderer.renderSinglePagePdf(PageSnapshot.capture(Page(width = 800f, height = 1280f)), file)
            assertTrue(file.length() > 0)
            val reader = PdfReader(file.readBytes())
            try {
                assertEquals(1, reader.numberOfPages)
                val size = reader.getPageSize(1)
                assertEquals(600f, size.width, 1f)
                assertEquals(960f, size.height, 1f)
            } finally {
                reader.close()
            }
        } finally {
            file.delete()
        }
    }

    @Test fun openPdfCanMergeAndroidPdfDocumentPages() {
        val first = File(context.cacheDir, "renderer-first-${System.nanoTime()}.pdf")
        val second = File(context.cacheDir, "renderer-second-${System.nanoTime()}.pdf")
        val merged = File(context.cacheDir, "renderer-merged-${System.nanoTime()}.pdf")
        try {
            val snapshot = PageSnapshot.capture(Page(width = 800f, height = 1280f))
            renderer.renderSinglePagePdf(snapshot, first)
            renderer.renderSinglePagePdf(snapshot, second)

            FileOutputStream(merged).use { output ->
                val document = Document()
                val copy = PdfCopy(document, output)
                document.open()
                try {
                    listOf(first, second).forEach { file ->
                        val reader = PdfReader(file.readBytes())
                        try {
                            copy.addPage(copy.getImportedPage(reader, 1))
                            copy.freeReader(reader)
                        } finally {
                            reader.close()
                        }
                    }
                } finally {
                    document.close()
                }
            }

            val reader = PdfReader(merged.readBytes())
            try {
                assertEquals(2, reader.numberOfPages)
            } finally {
                reader.close()
            }
        } finally {
            first.delete()
            second.delete()
            merged.delete()
        }
    }
}
