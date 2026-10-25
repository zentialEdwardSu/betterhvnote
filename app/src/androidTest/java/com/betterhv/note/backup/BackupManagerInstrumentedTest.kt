package com.betterhv.note.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.createBitmap
import com.betterhv.note.doc.ImageObject
import com.betterhv.note.doc.TextObject
import com.betterhv.note.doc.PdfAnchor
import com.betterhv.note.doc.PdfAnchorKind
import com.betterhv.note.doc.PdfPageSource
import com.betterhv.note.doc.Transform2D
import com.betterhv.note.ink.Bounds
import com.betterhv.note.storage.DocumentChange
import com.betterhv.note.storage.PdfImportCommit
import com.betterhv.note.storage.PdfImportedPage
import java.security.MessageDigest
import androidx.core.content.edit
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.storage.SQLiteStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupManagerInstrumentedTest {
  private val target = InstrumentationRegistry.getInstrumentation().targetContext
  private val prefix = "backup-test-${UUID.randomUUID()}"
  private val root = File(target.cacheDir, prefix)
  private val context = object : ContextWrapper(target) {
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = root
    override fun getCacheDir(): File = File(root, "cache").also { it.mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
      target.getSharedPreferences("$prefix-$name", mode)
  }
  private val manager get() = BackupManager(context)
  private val ignoreProgress: (Int, Int) -> Unit = { _, _ -> }

  @Before fun setup() {
    root.mkdirs()
    NotebookRepository(context).use { it.openOrCreate(); it.createBlankNotebook("Original", 100f, 200f) }
    context.getSharedPreferences("app_settings", 0).edit(commit = true) { putString("language", "en") }
  }

  @After fun cleanup() {
    listOf("app_settings", "pen_settings", "pairing-test").forEach { target.deleteSharedPreferences("$prefix-$it") }
    root.deleteRecursively()
  }

  private fun names(): Set<String> = SQLiteStore(context, File(root, "documents/default.inknote")).use { store ->
    store.readableDatabase.rawQuery("SELECT title FROM notebooks", null).use { cursor ->
      buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
  }

  @Test fun exportRestoreAndRollbackPreserveDataAndSettingsWithoutChangingPairing() {
    val archive = File(root, "test.zip")
    manager.export(Uri.fromFile(archive), ignoreProgress)
    NotebookRepository(context).use { it.createBlankNotebook("After backup", 100f, 200f) }
    context.getSharedPreferences("app_settings", 0).edit(commit = true) { putString("language", "zh") }
    context.getSharedPreferences("pairing-test", 0).edit(commit = true) { putString("key", "local-only") }
    val candidate = manager.prepare(Uri.fromFile(archive), ignoreProgress)
    manager.restore(candidate, ignoreProgress)
    assertTrue("Original" in names())
    assertFalse("After backup" in names())
    assertEquals("en", context.getSharedPreferences("app_settings", 0).getString("language", null))
    assertEquals("local-only", context.getSharedPreferences("pairing-test", 0).getString("key", null))
    assertTrue(manager.hasRollback())
    manager.restore(manager.prepareRollback(), ignoreProgress)
    assertTrue("After backup" in names())
    assertEquals("zh", context.getSharedPreferences("app_settings", 0).getString("language", null))
  }

  @Test fun pendingRestoreJournalRollsBackBeforeDatabaseIsOpened() {
    val archive = File(root, "test.zip")
    manager.export(Uri.fromFile(archive), ignoreProgress)
    NotebookRepository(context).use { it.createBlankNotebook("Recover me", 100f, 200f) }
    manager.restore(manager.prepare(Uri.fromFile(archive), ignoreProgress), ignoreProgress)
    File(root, "backups/restore.pending").writeText("restore-v1")
    // Simulate interruption after the live directory was only partly replaced.
    File(root, "documents/default.inknote").writeText("incomplete")
    assertTrue(manager.recoverInterruptedRestore())
    assertTrue("Recover me" in names())
    assertFalse(manager.recoverInterruptedRestore())
  }

  @Test fun truncatedBackupLeavesLiveDataUntouched() {
    val archive = File(root, "test.zip")
    manager.export(Uri.fromFile(archive), ignoreProgress)
    archive.writeBytes(archive.readBytes().copyOf(32))
    val before = names()
    assertThrows(Exception::class.java) { manager.prepare(Uri.fromFile(archive), ignoreProgress) }
    assertEquals(before, names())
    assertFalse(File(root, "backups/import-staging").exists())
  }

  @Test fun mediaTemplatesAndCrossPageAnchorsSurviveRestoreAndSourceDeletion() {
    val image = File(root, "documents/assets/test.png").also { it.parentFile!!.mkdirs() }
    val bitmap = createBitmap(40, 30)
    image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    val pdf = File(root, "documents/pdfs/test.pdf").also { it.parentFile!!.mkdirs() }
    val document = PdfDocument()
    try {
      repeat(2) { document.finishPage(document.startPage(PdfDocument.PageInfo.Builder(100, 200, it + 1).create())) }
      pdf.outputStream().use(document::writeTo)
    } finally { document.close() }
    val template = File(root, "templates/cache/custom.test-fingerprint.png").also { it.parentFile!!.mkdirs() }
    image.copyTo(template)
    File(root, "templates/last-manifest.json").writeText("""
      {"schemaVersion":1,"templates":[{"id":"custom.test","name":"Test","description":"Offline",
      "file":"test.png","width":40,"height":30}]}
    """.trimIndent())
    val imageBytes = image.readBytes()
    val pdfBytes = pdf.readBytes()
    val hash = MessageDigest.getInstance("SHA-256").digest(pdfBytes).joinToString("") { "%02x".format(it) }
    var notebookId: UUID? = null
    var sourceId: UUID? = null
    var targetId: UUID? = null
    val textId = UUID.randomUUID()
    NotebookRepository(context).use { repository ->
      repository.createBlankNotebook("Custom paper", 40f, 30f, "custom.test")
      val source = PdfPageSource(0, Bounds(0f, 0f, 100f, 200f), Transform2D.IDENTITY)
      notebookId = repository.createPdfNotebook(PdfImportCommit(title = "Two pages", assetPath = "pdfs/test.pdf", displayName = "test.pdf",
        byteLength = pdf.length(), sha256 = hash,
        pages = List(2) { PdfImportedPage(100f, 200f, source.copy(sourcePageIndex = it)) }))
      val notebook = repository.loadNotebook(notebookId!!)!!
      sourceId = notebook.pageOrder[1]
      targetId = repository.createLinkedNotePage(notebook.pageOrder.first(), 100f, 200f)
      repository.createLinkedNotePage(sourceId!!, 100f, 200f)
      val updated = repository.loadNotebook(notebookId!!)!!
      val page = repository.loadPage(targetId!!)!!
      page.addObject(TextObject(textId, text = "Cross page\nexcerpt", localBounds = Bounds(0f, 0f, 90f, 30f)))
      page.addObject(ImageObject(UUID.randomUUID(), assetPath = "assets/test.png", mimeType = "image/png",
        pixelWidth = 40, pixelHeight = 30))
      updated.attachPage(page); updated.refreshPageMetadata(page)
      repository.persist(DocumentChange.fullPage(updated, page, "test:backup-media"))
      repository.savePdfAnchor(PdfAnchor(sourcePageId = sourceId!!, notePageId = targetId!!,
        noteObjectId = textId, kind = PdfAnchorKind.REGION_TEXT,
        normalizedBounds = Bounds(0.1f, 0.1f, 0.5f, 0.5f), selectedText = "excerpt", ordinal = 1))
    }
    val archive = File(root, "media.zip")
    manager.export(Uri.fromFile(archive), ignoreProgress)
    manager.restore(manager.prepare(Uri.fromFile(archive), ignoreProgress), ignoreProgress)
    assertTrue(imageBytes.contentEquals(image.readBytes()))
    assertTrue(pdfBytes.contentEquals(pdf.readBytes()))
    assertTrue(imageBytes.contentEquals(template.readBytes()))
    NotebookRepository(context).use { repository ->
      assertEquals(sourceId, repository.pdfAnchorForNoteObject(textId)!!.sourcePageId)
      assertEquals(4, repository.loadNotebook(notebookId!!)!!.pageOrder.size)
      assertTrue(repository.loadPage(targetId!!)!!.getObject(textId) is TextObject)
    }
    // Deleting an excerpt's source removes the backlink, but retains content on another parent's insert.
    SQLiteStore(context, File(root, "documents/default.inknote")).use {
      it.writableDatabase.delete("pages", "id=?", arrayOf(sourceId.toString()))
    }
    NotebookRepository(context).use { repository ->
      assertEquals(null, repository.pdfAnchorForNoteObject(textId))
      assertTrue(repository.loadPage(targetId!!)!!.getObject(textId) is TextObject)
    }
    manager.restore(manager.prepareRollback(), ignoreProgress)
    NotebookRepository(context).use { repository ->
      assertEquals(sourceId, repository.pdfAnchorForNoteObject(textId)!!.sourcePageId)
    }
  }

  @Test fun missingReferencedMediaAndNewerVersionsAreRejectedBeforeReplacement() {
    val archive = File(root, "invalid.zip")
    manager.export(Uri.fromFile(archive), ignoreProgress)
    val staging = File(root, "invalid-staging")
    val manifest = archive.inputStream().use { BackupArchive.read(it, staging) }
    val newer = manifest.copy(schemaVersion = 9)
    archive.outputStream().use { BackupArchive.write(staging, newer, it) }
    val before = names()
    assertThrows(IllegalArgumentException::class.java) { manager.prepare(Uri.fromFile(archive), ignoreProgress) }
    assertEquals(before, names())
    NotebookRepository(context).use { repository ->
      val notebook = repository.openOrCreate()
      val page = notebook.pageAt(0)!!
      page.addObject(ImageObject(UUID.randomUUID(), assetPath = "assets/missing.png", mimeType = "image/png",
        pixelWidth = 10, pixelHeight = 10))
      notebook.refreshPageMetadata(page)
      repository.persist(DocumentChange.fullPage(notebook, page, "test:missing-media"))
    }
    assertThrows(IllegalArgumentException::class.java) { manager.export(Uri.fromFile(archive), ignoreProgress) }
    assertFalse(File(root, "backups/export-staging").exists())
  }

  @Test fun v7DatabaseMigratesToV8InStaging() {
    val archive = File(root, "v7.zip")
    val staging = File(root, "v7-staging")
    manager.export(Uri.fromFile(archive), ignoreProgress)
    val manifest = archive.inputStream().use { BackupArchive.read(it, staging) }
    SQLiteStore(context, File(staging, "documents/default.inknote")).use { it.writableDatabase.version = 7 }
    val v7 = BackupArchive.manifest(staging, 7, manifest.notebookCount)
    archive.outputStream().use { BackupArchive.write(staging, v7, it) }
    val candidate = manager.prepare(Uri.fromFile(archive), ignoreProgress)
    SQLiteStore(context, File(candidate.directory, "documents/default.inknote")).use {
      assertEquals(8, it.readableDatabase.version)
    }
    manager.discard(candidate)
  }
}
