package com.betterhv.note.export

import android.graphics.Bitmap
import com.betterhv.note.doc.PageKind
import com.betterhv.note.pdf.MuPdfEngine
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.storage.PageSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

class ExportEngine(
  private val notebookRepository: NotebookRepository,
  private val taskRepository: ExportTaskRepository,
  private val renderer: PageRenderer,
  private val exportRoot: File,
  private val pdfWriter: PdfInkAnnotationWriter = PdfInkAnnotationWriter(),
) {
  private val muPdf = MuPdfEngine()
  private val fileOperations = Mutex()
  suspend fun generate(taskId: UUID, onProgress: (ExportProgress) -> Unit = {}): ExportResult =
    fileOperations.withLock { generateLocked(taskId, onProgress) }

  private suspend fun generateLocked(taskId: UUID, reportProgress: (ExportProgress) -> Unit): ExportResult =
    withContext(Dispatchers.IO) {
      var latestProgress = ExportProgress(0, 1, null, ExportProgress.Stage.PREPARING)
      val onProgress: (ExportProgress) -> Unit = { latestProgress = it; reportProgress(it) }
      try {
        val task = taskRepository.loadTask(taskId) ?: error("Export task does not exist")
        onProgress(ExportProgress(0, 1, null, ExportProgress.Stage.PREPARING))
        val sources = taskRepository.resolveSources(task)
        check(sources.isNotEmpty()) { "Notebook has no pages to export" }
        check(task.scope == ExportScope.ALL_PAGES || sources.size == task.pageIds.size) {
          "Export task contains deleted pages"
        }
        taskRepository.currentArtifact(task, sources)?.let { return@withContext ExportResult.Success(it) }

        check(exportRoot.isDirectory || exportRoot.mkdirs()) { "Could not create export directory: $exportRoot" }
        val fingerprint = taskRepository.fingerprint(task, sources)
        val artifactId = UUID.randomUUID()
        val artifactDir = File(exportRoot, "artifacts").also(File::mkdirs)
        val target = File(artifactDir, "$artifactId.${task.format.extension}")
        val temp = File(artifactDir, "$artifactId.tmp")
        try {
          when (task.format) {
            ExportFormat.PNG -> generatePng(task.notebookId, sources.single(), temp, onProgress)
            ExportFormat.PDF -> generatePdf(task, sources, temp, onProgress)
          }
          coroutineContext.ensureActive()
          atomicMove(temp, target)
          val now = System.currentTimeMillis()
          val artifact = ExportArtifact(
            artifactId, task.id, target, taskRepository.displayName(task), task.format.mimeType,
            target.length(), sha256(target), fingerprint, now,
          )
          taskRepository.recordArtifact(artifact)?.delete()
          taskRepository.removeObsoleteCaches(task.id, sources.mapTo(LinkedHashSet(), ExportPageSource::id))
            .forEach(File::delete)
          ExportResult.Success(artifact)
        } finally {
          temp.delete()
        }
      } catch (_: CancellationException) {
        ExportResult.Cancelled
      } catch (t: Throwable) {
        val diagnostic = ExportDiagnostic.capture(
          taskId, latestProgress, t, File(exportRoot.parentFile, "export-diagnostics"),
        )
        runCatching { taskRepository.markError(taskId, diagnostic.summary) }
        ExportResult.Failure(diagnostic.summary, diagnostic)
      }
    }

  suspend fun deleteTask(taskId: UUID) = fileOperations.withLock {
    taskRepository.deleteTask(taskId).forEach(File::delete)
    File(exportRoot, "pages/$taskId").deleteRecursively()
  }

  suspend fun pruneOrphanFiles() = fileOperations.withLock {
    val retained = taskRepository.referencedInternalFiles()
    if (!exportRoot.isDirectory) return@withLock
    exportRoot.walkBottomUp().forEach { file ->
      if (file.isFile && file.absolutePath !in retained) file.delete()
      if (file.isDirectory && file != exportRoot && file.list().isNullOrEmpty()) file.delete()
    }
  }

  private suspend fun generatePng(
    notebookId: UUID,
    source: ExportPageSource,
    output: File,
    onProgress: (ExportProgress) -> Unit,
  ) {
    coroutineContext.ensureActive()
    onProgress(ExportProgress(0, 1, source.id))
    val page = notebookRepository.loadPage(source.id) ?: error("Page has been deleted")
    val snapshot = PageSnapshot.capture(page)
    val bitmap = if (page.kind == PageKind.PDF_SOURCE) {
      val record = notebookRepository.pdfDocument(notebookId) ?: error("PDF asset record is missing")
      val file = notebookRepository.resolveDocumentAsset(record.assetPath) ?: error("PDF asset file is missing")
      val base = File(output.parentFile, "${output.name}.base.tmp.pdf")
      val annotated = File(output.parentFile, "${output.name}.annotated.tmp.pdf")
      try {
        pdfWriter.copySourcePage(file, requireNotNull(page.pdfSource).sourcePageIndex, base)
        pdfWriter.addInkAnnotations(base, snapshot, annotated)
        muPdf.renderPage(annotated, 0, (snapshot.metadata.width * 2f).toInt())
      } finally {
        base.delete()
        annotated.delete()
      }
    } else {
      renderer.renderToBitmap(
        snapshot,
        (snapshot.metadata.width * 2f).toInt(),
        (snapshot.metadata.height * 2f).toInt(),
      )
    }
    try {
      FileOutputStream(output).use { stream ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG encoding failed" }
        stream.fd.sync()
      }
    } finally {
      bitmap.recycle()
    }
    onProgress(ExportProgress(1, 1, source.id))
  }

  private suspend fun generatePdf(
    task: ExportTask,
    sources: List<ExportPageSource>,
    output: File,
    onProgress: (ExportProgress) -> Unit,
  ) {
    val pageDir = File(exportRoot, "pages/${task.id}").also(File::mkdirs)
    val pageFiles = ArrayList<File>(sources.size)
    sources.forEachIndexed { index, source ->
      coroutineContext.ensureActive()
      onProgress(ExportProgress(index, sources.size, source.id))
      val cached = taskRepository.cachedPage(task.id, source)
      if (cached != null) {
        pageFiles += cached
      } else {
        val page = notebookRepository.loadPage(source.id) ?: error("Page has been deleted")
        val finalPage = File(pageDir, ExportTaskRepository.pageCacheFileName(source))
        val basePage = File(pageDir, "${source.id}-r${source.contentRevision}-base.tmp.pdf")
        val tempPage = File(pageDir, "${finalPage.name}.tmp")
        try {
          val snapshot = PageSnapshot.capture(page)
          if (page.kind == PageKind.PDF_SOURCE) {
            val record = notebookRepository.pdfDocument(task.notebookId)
              ?: error("PDF asset record is missing")
            val original = notebookRepository.resolveDocumentAsset(record.assetPath)
              ?: error("PDF asset file is missing")
            pdfWriter.copySourcePage(
              original,
              requireNotNull(page.pdfSource).sourcePageIndex,
              basePage,
            )
          } else {
            renderer.renderSinglePagePdfBase(snapshot, basePage)
          }
          coroutineContext.ensureActive()
          pdfWriter.addInkAnnotations(basePage, snapshot, tempPage)
          coroutineContext.ensureActive()
          atomicMove(tempPage, finalPage)
          taskRepository.recordCachedPage(task.id, source, finalPage)?.delete()
          pageFiles += finalPage
        } finally {
          basePage.delete()
          tempPage.delete()
        }
      }
    }
    coroutineContext.ensureActive()
    onProgress(ExportProgress(sources.size, sources.size, null, ExportProgress.Stage.ASSEMBLING))
    pdfWriter.mergePages(pageFiles, output)
  }

  private fun atomicMove(source: File, target: File) {
    target.parentFile?.mkdirs()
    runCatching {
      Files.move(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    }.getOrElse {
      Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").run {
    FileInputStream(file).use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        update(buffer, 0, count)
      }
    }
    digest()
  }
}
