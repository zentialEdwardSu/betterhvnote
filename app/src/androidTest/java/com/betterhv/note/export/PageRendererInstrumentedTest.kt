package com.betterhv.note.export

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.betterhv.note.doc.Page
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.PenType
import com.betterhv.note.ink.Stroke
import com.betterhv.note.storage.PageSnapshot
import com.betterhv.note.template.TemplateManifestCodec
import com.betterhv.note.template.TemplateStore
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageRendererInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val templateStore = TemplateStore.get(context)
    private val renderer = PageRenderer(File(context.filesDir, "documents"), templateStore)
    private val writer = PdfInkAnnotationWriter()

    @Test fun packagedTemplatesHaveValidManifestAndSvgAssetsRender() {
        val manifest = context.assets.open("templates/manifest.json")
            .bufferedReader().use { TemplateManifestCodec.parse(it.readText()) }
        assertTrue(manifest.errors.joinToString(), manifest.errors.isEmpty())
        assertEquals(
            setOf("builtin.single-lines", "builtin.dotted"),
            manifest.entries.mapTo(mutableSetOf()) { it.id }
        )
        manifest.entries.forEach { entry ->
            assertTrue(entry.file.endsWith(".svg"))
            context.assets.open("templates/${entry.file}").use { input ->
                assertTrue(input.read() >= 0)
            }
        }
        val blank = requireNotNull(templateStore.resolve("builtin.blank"))
        assertTrue(blank.assetFile == null)
        assertTrue(blank.isCompatible(1860f, 2414f))

        listOf("builtin.single-lines", "builtin.dotted").forEach { templateId ->
            val bitmap = renderer.renderToBitmap(
                PageSnapshot.capture(
                    Page(width = 1860f, height = 2414f, templateId = templateId)
                ),
                930,
                1207
            )
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("$templateId should contain visible marks", pixels.any { it != Color.WHITE })
                assertTrue("$templateId must not render black blocks", pixels.none { pixel ->
                    Color.red(pixel) < 96 && Color.green(pixel) < 96 && Color.blue(pixel) < 96
                })
            } finally {
                bitmap.recycle()
            }
        }
    }

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
        val file = tempFile("base")
        try {
            renderer.renderSinglePagePdfBase(PageSnapshot.capture(Page(width = 800f, height = 1280f)), file)
            openPdf(file) { document ->
                assertEquals(1, document.countPages())
                val page = document.loadPage(0) as PDFPage
                try {
                    val size = page.bounds
                    assertEquals(600f, size.x1 - size.x0, 1f)
                    assertEquals(960f, size.y1 - size.y0, 1f)
                    assertTrue(page.annotations.orEmpty().isEmpty())
                } finally {
                    page.destroy()
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test fun everyStrokeBecomesOneEditableVectorInkAnnotation() {
        val base = tempFile("ink-base")
        val annotated = tempFile("ink-annotated")
        try {
            val page = Page(width = 800f, height = 1280f)
            PenType.entries.forEachIndexed { index, type ->
                page.addObject(stroke(type, index, 0x80112233.toInt()))
            }
            val snapshot = PageSnapshot.capture(page)
            renderer.renderSinglePagePdfBase(snapshot, base)
            writer.addInkAnnotations(base, snapshot, annotated)

            openPdf(base) { document ->
                val pdfPage = document.loadPage(0) as PDFPage
                try {
                    assertTrue("base page must not contain duplicate ink", pdfPage.annotations.orEmpty().isEmpty())
                } finally {
                    pdfPage.destroy()
                }
            }
            openPdf(annotated) { document ->
                val pdfPage = document.loadPage(0) as PDFPage
                try {
                    val annotations = pdfPage.annotations.orEmpty()
                    try {
                        assertEquals(3, annotations.size)
                        annotations.forEach { annotation ->
                            assertEquals(PDFAnnotation.TYPE_INK, annotation.type)
                            assertTrue(annotation.hasInkList())
                            assertEquals(1, annotation.inkListCount)
                            assertTrue(annotation.getInkListStrokeCount(0) >= 2)
                            assertTrue(annotation.flags and PDFAnnotation.IS_PRINT != 0)
                            assertTrue(annotation.name.isNotBlank())

                            val objectDictionary = annotation.`object`
                            try {
                                assertEquals("Ink", objectDictionary.get("Subtype").asName())
                                val normalAppearance = objectDictionary.get("AP").get("N")
                                try {
                                    assertTrue(normalAppearance.isStream)
                                    val stream = String(normalAppearance.readStream(), Charsets.US_ASCII)
                                    assertTrue(stream.contains(" m"))
                                    assertFalse("ink appearance must stay vector", stream.contains(" Do"))
                                } finally {
                                    normalAppearance.destroy()
                                }
                            } finally {
                                objectDictionary.destroy()
                            }
                        }
                    } finally {
                        annotations.forEach(PDFAnnotation::destroy)
                    }
                } finally {
                    pdfPage.destroy()
                }
            }
        } finally {
            base.delete()
            annotated.delete()
        }
    }

    @Test fun mupdfGraftPreservesPageOrderSizeAndAnnotations() {
        val firstBase = tempFile("first-base")
        val secondBase = tempFile("second-base")
        val first = tempFile("first")
        val second = tempFile("second")
        val merged = tempFile("merged")
        try {
            val page1 = pageWithStroke(640f, 960f, PenType.NormalPen, 0)
            val page2 = pageWithStroke(800f, 1280f, PenType.Marker, 1)
            val snapshot1 = PageSnapshot.capture(page1)
            val snapshot2 = PageSnapshot.capture(page2)
            renderer.renderSinglePagePdfBase(snapshot1, firstBase)
            renderer.renderSinglePagePdfBase(snapshot2, secondBase)
            writer.addInkAnnotations(firstBase, snapshot1, first)
            writer.addInkAnnotations(secondBase, snapshot2, second)
            writer.mergePages(listOf(first, second), merged)

            openPdf(merged) { document ->
                assertEquals(2, document.countPages())
                listOf(0 to (480f to 720f), 1 to (600f to 960f)).forEach { (index, expected) ->
                    val page = document.loadPage(index) as PDFPage
                    try {
                        assertEquals(expected.first, page.bounds.let { it.x1 - it.x0 }, 1f)
                        assertEquals(expected.second, page.bounds.let { it.y1 - it.y0 }, 1f)
                        val annotations = page.annotations.orEmpty()
                        try { assertEquals(1, annotations.size) } finally {
                            annotations.forEach(PDFAnnotation::destroy)
                        }
                    } finally {
                        page.destroy()
                    }
                }
            }
        } finally {
            listOf(firstBase, secondBase, first, second, merged).forEach(File::delete)
        }
    }

    /** Leaves a deterministic PDF in external app files for pypdf/qpdf/Poppler inspection. */
    @Test fun writeIndependentValidationFixture() {
        val directory = requireNotNull(context.getExternalFilesDir("pdf-validation"))
        val base = File(directory, "ink-annotation-validation-base.pdf")
        val output = File(directory, "ink-annotation-validation.pdf")
        base.delete()
        output.delete()
        try {
            val page = Page(width = 800f, height = 1280f)
            PenType.entries.forEachIndexed { index, type ->
                page.addObject(stroke(type, index, 0x80112233.toInt()))
            }
            val snapshot = PageSnapshot.capture(page)
            renderer.renderSinglePagePdfBase(snapshot, base)
            writer.addInkAnnotations(base, snapshot, output)
            assertTrue(output.length() > 0)
        } finally {
            base.delete()
        }
    }

    @Test fun transformedThickAndSinglePointInkStayInsideAnnotationBounds() {
        val base = tempFile("bounds-base")
        val output = tempFile("bounds-output")
        try {
            val thick = stroke(PenType.NormalPen, 0, 0x40123456).copy(
                transform = Transform2D.translate(220f, 180f)
                    .times(Transform2D.rotateAbout(0f, 0f, 0.6f))
                    .times(Transform2D.scaleAbout(0f, 0f, 2.5f)),
                stroke = stroke(PenType.NormalPen, 0, 0x40123456).stroke.copy(
                    style = PenStyle(baseWidth = 48f, color = 0x40123456, penType = PenType.NormalPen)
                )
            )
            val single = StrokeObject(
                id = UUID.randomUUID(),
                transform = Transform2D.translate(300f, 500f).times(Transform2D.scaleAbout(0f, 0f, 1.8f)),
                zIndex = 1,
                stroke = Stroke(
                    listOf(InkPoint(25f, 30f, 1f, 1L)),
                    PenStyle(baseWidth = 64f, color = 0x80ABCDEF.toInt(), penType = PenType.Marker)
                )
            )
            val page = Page(width = 800f, height = 1280f).apply {
                addObject(thick)
                addObject(single)
            }
            val snapshot = PageSnapshot.capture(page)
            renderer.renderSinglePagePdfBase(snapshot, base)
            writer.addInkAnnotations(base, snapshot, output)

            openPdf(output) { document ->
                val pdfPage = document.loadPage(0) as PDFPage
                try {
                    val annotations = pdfPage.annotations.orEmpty()
                    try {
                        assertEquals(2, annotations.size)
                        annotations.forEach { annotation ->
                            val bounds = annotation.bounds
                            assertTrue(bounds.x1 > bounds.x0)
                            assertTrue(bounds.y1 > bounds.y0)
                            val source = listOf(thick, single).single { it.id.toString() == annotation.name }
                            val visual = requireNotNull(PdfInkAppearance.from(source)).visualBounds
                            assertTrue(bounds.x0 <= visual.left)
                            assertTrue(bounds.y0 <= visual.top)
                            assertTrue(bounds.x1 >= visual.right)
                            assertTrue(bounds.y1 >= visual.bottom)
                            annotation.inkList.flatten().forEach { point ->
                                assertTrue(bounds.contains(point.x, point.y))
                            }
                        }
                        assertEquals(2, annotations[1].getInkListStrokeCount(0))
                    } finally {
                        annotations.forEach(PDFAnnotation::destroy)
                    }
                } finally {
                    pdfPage.destroy()
                }
            }
        } finally {
            base.delete()
            output.delete()
        }
    }

    private fun pageWithStroke(width: Float, height: Float, type: PenType, index: Int) =
        Page(width = width, height = height).apply { addObject(stroke(type, index, Color.BLACK)) }

    private fun stroke(type: PenType, index: Int, color: Int) = StrokeObject(
        id = UUID.randomUUID(),
        zIndex = index,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_001_000L,
        stroke = Stroke(
            points = listOf(
                InkPoint(50f, 80f + index * 40f, 0.2f, 1L),
                InkPoint(180f, 110f + index * 40f, 0.8f, 2L),
                InkPoint(320f, 90f + index * 40f, 1f, 3L)
            ),
            style = PenStyle(baseWidth = 16f, color = color, penType = type)
        )
    )

    private fun tempFile(label: String) = File(context.cacheDir, "$label-${System.nanoTime()}.pdf")

    private inline fun openPdf(file: File, block: (PDFDocument) -> Unit) {
        val document = Document.openDocument(file.absolutePath) as PDFDocument
        try { block(document) } finally { document.destroy() }
    }
}
