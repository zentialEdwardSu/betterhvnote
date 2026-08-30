package com.betterhv.note.pdf

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.betterhv.note.ink.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MuPdfEngineInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun inspectsRendersAndExtractsARegionFromFixedLayoutPdf() {
        val file = File(context.cacheDir, "mupdf-engine-${System.nanoTime()}.pdf")
        try {
            val document = PdfDocument()
            try {
                repeat(2) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawText(
                        "MuPDF page ${index + 1}", 72f, 120f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 24f }
                    )
                    document.finishPage(page)
                }
                file.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }

            val engine = MuPdfEngine()
            val inspection = engine.inspect(file)
            assertEquals(2, inspection.pages.size)
            assertEquals(816f, inspection.pages[0].width, 0.5f)
            assertEquals(1056f, inspection.pages[0].height, 0.5f)

            val bitmap = engine.renderPage(file, 0, 816)
            try {
                assertEquals(816, bitmap.width)
                assertTrue(bitmap.height > bitmap.width)
            } finally {
                bitmap.recycle()
            }

            val text = engine.extractText(file, 0, Bounds(0f, 0f, 1f, 0.35f))
            assertFalse(text.isBlank())
            assertTrue(text.contains("MuPDF"))
        } finally {
            file.delete()
        }
    }
}
