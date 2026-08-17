package com.betterhv.note.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.betterhv.note.doc.StrokeObject
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.TextFontFamily
import com.betterhv.note.doc.TextObject
import com.betterhv.note.ink.Bounds
import com.betterhv.note.ink.InkPoint
import com.betterhv.note.ink.PenStyle
import com.betterhv.note.ink.Stroke
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotebookRepositoryInstrumentedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testRoot = File(targetContext.cacheDir, "notebook-repository-tests")
    private val context: Context = object : ContextWrapper(targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = testRoot
    }
    private val documents = File(context.filesDir, "documents")

    @Before
    fun cleanTestDocuments() {
        testRoot.deleteRecursively()
        check(testRoot.mkdirs() || testRoot.isDirectory)
    }

    @After
    fun removeTestDocuments() {
        testRoot.deleteRecursively()
    }

    @Test
    fun createsPersistentWorkingCopyAndRestoresLastOpenedPreference() {
        val secondId: UUID
        NotebookRepository(context).use { repository ->
            val working = repository.openOrCreate()
            assertEquals(NotebookRepository.WORKING_COPY_TITLE, working.title)
            assertEquals(working.id, repository.workingNotebookId())
            secondId = repository.createBlankNotebook("Second", 100f, 200f)
            repository.setStartupBehavior(StartupBehavior.LAST_OPENED)
        }

        NotebookRepository(context).use { reopened ->
            assertEquals(secondId, reopened.openOrCreate().id)
            assertEquals(StartupBehavior.LAST_OPENED, reopened.startupBehavior())
        }
    }

    @Test
    fun transferPreservesPageIdentityContentBookmarkAndLeavesSourceNonEmpty() {
        NotebookRepository(context).use { repository ->
            val source = repository.openOrCreate()
            val page = source.pageAt(0)!!
            page.setBookmarked(true)
            page.addObject(
                StrokeObject(
                    id = UUID.randomUUID(),
                    stroke = Stroke(
                        listOf(InkPoint(10f, 20f, 0.7f, 1L)),
                        PenStyle()
                    )
                )
            )
            source.refreshPageMetadata(page)
            repository.persist(DocumentChange.fullPage(source, page, "test:seed"))

            val transfer = repository.transferPagesToNewNotebook(
                source.id,
                setOf(page.id),
                "Moved"
            )
            val target = repository.loadNotebook(transfer.targetNotebookId)!!
            val moved = target.pageAt(0)!!
            val reloadedSource = repository.loadNotebook(source.id)!!

            assertEquals(page.id, moved.id)
            assertTrue(moved.bookmarked)
            assertEquals(1, moved.scene.size)
            assertEquals(1, reloadedSource.pageOrder.size)
            assertNotEquals(page.id, reloadedSource.pageOrder.single())
        }
    }

    @Test
    fun deletingActiveNotebookFallsBackToWorkingCopyAndWorkingCopyCannotBeDeleted() {
        NotebookRepository(context).use { repository ->
            val workingId = repository.openOrCreate().id
            val disposableId = repository.createBlankNotebook("Disposable", 100f, 200f)

            val deletion = repository.deleteNotebook(disposableId)

            assertEquals(disposableId, deletion.deletedNotebookId)
            assertEquals(workingId, deletion.activeNotebookId)
            assertTrue(repository.listNotebooks(workingId).none { it.id == disposableId })
            assertTrue(runCatching { repository.deleteNotebook(workingId) }.isFailure)
            repository.setStartupBehavior(StartupBehavior.LAST_OPENED)
        }

        NotebookRepository(context).use { reopened ->
            assertEquals(reopened.workingNotebookId(), reopened.openOrCreate().id)
        }
    }

    @Test
    fun imageAndTextRoundTripWithoutChangingExistingStroke() {
        val strokeId = UUID.randomUUID()
        val imageId = UUID.randomUUID()
        val textId = UUID.randomUUID()
        val pageId: UUID
        NotebookRepository(context).use { repository ->
            val notebook = repository.openOrCreate()
            val page = notebook.pageAt(0)!!
            pageId = page.id
            page.addObject(
                StrokeObject(strokeId, stroke = Stroke(listOf(InkPoint(1f, 2f, 1f, 1L)), PenStyle()))
            )
            page.addObject(
                ImageObject(
                    imageId, assetPath = "assets/test.png", mimeType = "image/png",
                    pixelWidth = 40, pixelHeight = 30, exifOrientation = 6
                )
            )
            page.addObject(
                TextObject(
                    textId, text = "第一行\n第二行", fontFamily = TextFontFamily.SERIF,
                    fontSize = 32f, localBounds = Bounds(0f, 0f, 100f, 70f)
                )
            )
            notebook.refreshPageMetadata(page)
            repository.persist(DocumentChange.fullPage(notebook, page, "test:rich-roundtrip"))
        }

        NotebookRepository(context).use { repository ->
            val page = repository.loadPage(pageId)!!
            assertTrue(page.getObject(strokeId) is StrokeObject)
            assertTrue(page.getObject(imageId) is ImageObject)
            assertEquals(6, (page.getObject(imageId) as ImageObject).exifOrientation)
            val text = page.getObject(textId) as TextObject
            assertEquals("第一行\n第二行", text.text)
            assertEquals(TextFontFamily.SERIF, text.fontFamily)
            assertEquals(32f, text.fontSize)
        }
    }

    @Test
    fun transferReceiptIsPersistedIdempotentlyWithObject() {
        val itemId = UUID.randomUUID()
        val objectId = UUID.randomUUID()
        NotebookRepository(context).use { repository ->
            val notebook = repository.openOrCreate()
            val page = notebook.pageAt(0)!!
            page.addObject(
                TextObject(
                    objectId, text = "from phone", localBounds = Bounds(0f, 0f, 120f, 30f)
                )
            )
            notebook.refreshPageMetadata(page)
            val change = DocumentChange.fullPage(notebook, page, "test:transfer-receipt")
            val receipt = TransferReceipt("phone", itemId, objectId)
            repository.persistWithReceipt(change, receipt)
            repository.persistWithReceipt(change, receipt)
            assertEquals(objectId, repository.findTransferReceipt("phone", itemId))
        }
    }
}
