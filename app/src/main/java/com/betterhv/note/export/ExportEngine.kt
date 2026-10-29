package com.betterhv.note.export

import android.graphics.Bitmap
import com.betterhv.note.EventLog
import com.betterhv.note.doc.PageKind
import com.betterhv.note.pdf.MuPdfEngine
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.storage.PageSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

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

  private suspend fun generateLocked(
    taskId: UUID,
    reportProgress: (ExportProgress) -> Unit,
  ): ExportResult = withContext(Dispatchers.IO) {
    val sink = ExportProgressSink(reportProgress)
    val context = coroutineContext
    val checkActive: () -> Unit = { context.ensureActive() }
    val startedAt = System.currentTimeMillis()
    val timings = Timings()
    var hits = 0
    var misses = 0
    var rasterScale = 1f
    var task: ExportTask? = null
    var sources: List<ExportPageSource> = emptyList()
    var outputBytes = 0L
    var temp: File? = null
    var target: File? = null
    var completed = false

    fun report(
      result: ExportRunResult,
      error: String? = null,
      failedStage: ExportProgress.Stage? = null,
    ) = ExportRunReport(
      taskId = taskId,
      mode = task?.renderMode ?: ExportRenderMode.VECTOR,
      result = result,
      pageCount = sources.size,
      cacheHits = hits,
      cacheMisses = misses,
      rasterScale = rasterScale,
      startedAt = startedAt,
      finishedAt = System.currentTimeMillis(),
      renderMs = timings.renderMs,
      mergeGraftMs = timings.mergeGraftMs,
      mergeSaveMs = timings.mergeSaveMs,
      verifyMs = timings.verifyMs,
      finalizeMs = timings.finalizeMs,
      outputBytes = outputBytes,
      failedStage = failedStage,
      error = error,
    )

    val ticker = launch(Dispatchers.Default) {
      while (isActive) {
        delay(TICKER_INTERVAL_MS)
        sink.tick()
      }
    }
    try {
      sink.begin(ExportProgress.Stage.PREPARING)
      val loaded = taskRepository.loadTask(taskId) ?: error("Export task does not exist")
      task = loaded
      sources = taskRepository.resolveSources(loaded)
      check(sources.isNotEmpty()) { "Notebook has no pages to export" }
      check(loaded.scope == ExportScope.ALL_PAGES || sources.size == loaded.pageIds.size) {
        "Export task contains deleted pages"
      }
      taskRepository.currentArtifact(loaded, sources)?.let { return@withContext ExportResult.Success(it) }

      check(exportRoot.isDirectory || exportRoot.mkdirs()) { "Could not create export directory: $exportRoot" }
      val fingerprint = taskRepository.fingerprint(loaded, sources)
      val artifactId = UUID.randomUUID()
      val artifactDir = File(exportRoot, "artifacts").also(File::mkdirs)
      val targetFile = File(artifactDir, "$artifactId.${loaded.format.extension}")
      val tempFile = File(artifactDir, "$artifactId.tmp")
      target = targetFile
      temp = tempFile
      when (loaded.format) {
        ExportFormat.PNG -> {
          val pngStart = System.nanoTime()
          generatePng(loaded.notebookId, sources.first(), tempFile, sink, checkActive)
          timings.renderMs += elapsedMs(pngStart)
        }

        ExportFormat.PDF -> {
          val counters = generatePdf(loaded, sources, tempFile, sink, checkActive, timings)
          hits = counters.hits
          misses = counters.misses
          rasterScale = counters.rasterScale
        }
      }
      checkActive()
      sink.begin(ExportProgress.Stage.VERIFYING, 1, 0)
      val verifyStart = System.nanoTime()
      val sha256 = sha256(tempFile, checkActive)
      timings.verifyMs += elapsedMs(verifyStart)
      checkActive()
      sink.begin(ExportProgress.Stage.PUBLISHING, 1, 1)
      val finalizeStart = System.nanoTime()
      atomicMove(tempFile, targetFile)
      val now = System.currentTimeMillis()
      val artifact = ExportArtifact(
        artifactId, loaded.id, targetFile, taskRepository.displayName(loaded), loaded.format.mimeType,
        targetFile.length(), sha256, fingerprint, now,
      )
      outputBytes = artifact.byteLength
      taskRepository.recordArtifact(artifact)?.delete()
      taskRepository.removeObsoleteCaches(
        loaded.id,
        sources.mapTo(LinkedHashSet(), ExportPageSource::id),
      ).forEach(File::delete)
      timings.finalizeMs += elapsedMs(finalizeStart)
      saveReport(report(ExportRunResult.SUCCESS))
      completed = true
      ExportResult.Success(artifact)
    } catch (cancelled: CancellationException) {
      if (task != null) saveReport(report(ExportRunResult.CANCELLED, failedStage = sink.last.stage))
      ExportResult.Cancelled
    } catch (t: Throwable) {
      val diagnostic = ExportDiagnostic.capture(
        taskId, sink.last, t, File(exportRoot.parentFile, "export-diagnostics"),
      )
      runCatching { taskRepository.markError(taskId, diagnostic.summary) }
      if (task != null) {
        saveReport(
          report(ExportRunResult.FAILED, diagnostic.summary, failedStage = sink.last.stage),
        )
      }
      ExportResult.Failure(diagnostic.summary, diagnostic)
    } finally {
      ticker.cancel()
      temp?.delete()
      if (!completed) target?.delete()
    }
  }

  suspend fun deleteTask(taskId: UUID) = fileOperations.withLock {
    taskRepository.deleteTask(taskId).forEach(File::delete)
  }

  suspend fun pruneOrphanFiles() = fileOperations.withLock {
    val retained = taskRepository.referencedInternalFiles()
    if (!exportRoot.isDirectory) return@withLock
    exportRoot.walkBottomUp().forEach { file ->
      if (file.isFile && file.absolutePath !in retained) file.delete()
      if (file.isDirectory && file != exportRoot && file.list().isNullOrEmpty()) file.delete()
    }
  }

  private fun generatePng(
    notebookId: UUID,
    source: ExportPageSource,
    output: File,
    sink: ExportProgressSink,
    checkActive: () -> Unit,
  ) {
    sink.begin(ExportProgress.Stage.RENDERING, 1, 0, source.id)
    val page = notebookRepository.loadPage(source.id) ?: error("Page has been deleted")
    val snapshot = PageSnapshot.capture(page)
    val width = (snapshot.metadata.width * 2f).roundToInt().coerceAtLeast(1)
    val height = (snapshot.metadata.height * 2f).roundToInt().coerceAtLeast(1)
    val bitmap = if (page.kind == PageKind.PDF_SOURCE) {
      val record = notebookRepository.pdfDocument(notebookId) ?: error("PDF asset record is missing")
      val file = notebookRepository.resolveDocumentAsset(record.assetPath) ?: error("PDF asset file is missing")
      // PNG output never needs editable annotations: composite the source page
      // and the current ink in one bitmap instead of writing a temporary PDF.
      val background = ensureMutable(
        muPdf.renderPage(file, requireNotNull(page.pdfSource).sourcePageIndex, width),
      )
      try {
        renderer.renderObjectsOnto(background, snapshot, checkActive)
        background
      } catch (t: Throwable) {
        background.recycle()
        throw t
      }
    } else {
      renderer.renderToBitmap(snapshot, width, height, checkActive = checkActive)
    }
    try {
      FileOutputStream(output).use { stream ->
        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) { "PNG encoding failed" }
        stream.fd.sync()
      }
    } finally {
      bitmap.recycle()
    }
    sink.begin(ExportProgress.Stage.RENDERING, 1, 1, source.id)
  }

  private suspend fun generatePdf(
    task: ExportTask,
    sources: List<ExportPageSource>,
    output: File,
    sink: ExportProgressSink,
    checkActive: () -> Unit,
    timings: Timings,
  ): PageCounters {
    val pageDir = File(exportRoot, "pages").also(File::mkdirs)
    val pageFiles = ArrayList<File>(sources.size)
    var hits = 0
    var misses = 0
    var rasterScale = 1f
    sink.begin(ExportProgress.Stage.RENDERING, sources.size, 0, sources.first().id)
    sources.forEachIndexed { index, source ->
      checkActive()
      sink.update(ExportProgress.Stage.RENDERING, index, sources.size, source.id, force = true)
      val cached = taskRepository.cachedPage(source, task.renderMode)
      if (cached != null) {
        // Record a reference for this task too: the file may have been created
        // by another task, and deleting that task must not orphan this one.
        taskRepository.recordCachedPage(task.id, source, cached)?.delete()
        pageFiles += cached
        hits++
      } else {
        val page = notebookRepository.loadPage(source.id) ?: error("Page has been deleted")
        val snapshot = PageSnapshot.capture(page)
        val finalPage = File(
          pageDir,
          ExportPageCacheKey.fileName(source, task.renderMode, ExportTaskRepository.RENDERER_VERSION),
        )
        val tempPage = File(pageDir, "${finalPage.name}.${UUID.randomUUID()}.tmp")
        val basePage = File(pageDir, "${finalPage.name}.${UUID.randomUUID()}.base.tmp")
        val renderStart = System.nanoTime()
        try {
          when {
            task.renderMode == ExportRenderMode.FAST -> {
              rasterScale = max(rasterScale, writeFastPage(task, page, snapshot, tempPage, checkActive))
            }

            page.kind == PageKind.PDF_SOURCE -> {
              val record = notebookRepository.pdfDocument(task.notebookId)
                ?: error("PDF asset record is missing")
              val original = notebookRepository.resolveDocumentAsset(record.assetPath)
                ?: error("PDF asset file is missing")
              pdfWriter.writeAnnotatedSourcePage(
                original,
                requireNotNull(page.pdfSource).sourcePageIndex,
                snapshot,
                tempPage,
                checkActive,
              )
            }

            else -> {
              renderer.renderSinglePagePdfBase(snapshot, basePage, checkActive)
              pdfWriter.addInkAnnotations(basePage, snapshot, tempPage, checkActive)
            }
          }
          timings.renderMs += elapsedMs(renderStart)
          checkActive()
          atomicMove(tempPage, finalPage)
          taskRepository.recordCachedPage(task.id, source, finalPage)?.delete()
          misses++
          pageFiles += finalPage
        } finally {
          basePage.delete()
          tempPage.delete()
        }
      }
      sink.update(ExportProgress.Stage.RENDERING, index + 1, sources.size, source.id, force = true)
    }
    checkActive()
    if (sources.size == 1) {
      // A one-page artifact is the page cache itself; merging would only copy it
      // through another full PDF save.
      sink.begin(ExportProgress.Stage.PUBLISHING, 1, 0, sources.first().id)
      copyFile(pageFiles.first(), output, checkActive)
      sink.update(ExportProgress.Stage.PUBLISHING, 1, 1, sources.first().id, force = true)
    } else {
      sink.begin(ExportProgress.Stage.ASSEMBLING, sources.size, 0)
      val merge = pdfWriter.mergePages(
        pageFiles,
        output,
        onPage = { processed, total ->
          sink.update(ExportProgress.Stage.ASSEMBLING, processed, total, force = true)
        },
        onSaveStart = { sink.begin(ExportProgress.Stage.WRITING, sources.size, sources.size) },
        checkActive = checkActive,
      )
      timings.mergeGraftMs += merge.graftMs
      timings.mergeSaveMs += merge.saveMs
    }
    checkActive()
    return PageCounters(hits, misses, rasterScale)
  }

  private fun writeFastPage(
    task: ExportTask,
    page: com.betterhv.note.doc.Page,
    snapshot: PageSnapshot,
    output: File,
    checkActive: () -> Unit,
  ): Float {
    val (widthPx, heightPx) = ExportRaster.pixelSize(page.width, page.height)
    val widthPoints = (page.width * PDF_POINTS_PER_LOGICAL_PIXEL).roundToInt().coerceAtLeast(1)
    val heightPoints = (page.height * PDF_POINTS_PER_LOGICAL_PIXEL).roundToInt().coerceAtLeast(1)
    if (page.kind == PageKind.PDF_SOURCE) {
      val record = notebookRepository.pdfDocument(task.notebookId) ?: error("PDF asset record is missing")
      val original = notebookRepository.resolveDocumentAsset(record.assetPath)
        ?: error("PDF asset file is missing")
      val bitmap = ensureMutable(
        muPdf.renderPage(
          original,
          requireNotNull(page.pdfSource).sourcePageIndex,
          widthPx,
        ),
      )
      try {
        renderer.renderObjectsOnto(bitmap, snapshot, checkActive)
        PdfPageImageWriter.write(bitmap, widthPoints, heightPoints, output)
      } finally {
        bitmap.recycle()
      }
    } else {
      val bitmap = renderer.renderToBitmap(snapshot, widthPx, heightPx, checkActive = checkActive)
      try {
        PdfPageImageWriter.write(bitmap, widthPoints, heightPoints, output)
      } finally {
        bitmap.recycle()
      }
    }
    return widthPx / page.width
  }

  /** MuPDF returns an immutable pixmap-backed bitmap; Canvas needs a mutable one. */
  private fun ensureMutable(bitmap: Bitmap): Bitmap {
    if (bitmap.isMutable) return bitmap
    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
      ?: error("Could not copy PDF page bitmap for ink overlay")
    bitmap.recycle()
    return copy
  }

  private fun saveReport(report: ExportRunReport) {
    runCatching { taskRepository.saveRunReport(report) }
      .onFailure { EventLog.log("Export", "report write failed: ${it.javaClass.simpleName}: ${it.message}") }
    EventLog.log(
      "Export",
      "task=${report.taskId} mode=${report.mode} result=${report.result} pages=${report.pageCount} " +
        "hits=${report.cacheHits} misses=${report.cacheMisses} render=${report.renderMs} " +
        "graft=${report.mergeGraftMs} save=${report.mergeSaveMs} verify=${report.verifyMs} " +
        "finalize=${report.finalizeMs} bytes=${report.outputBytes} total=${report.totalMs}",
    )
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

  private fun copyFile(source: File, target: File, checkActive: () -> Unit) {
    target.parentFile?.mkdirs()
    FileInputStream(source).use { input ->
      FileOutputStream(target).use { output ->
        val buffer = ByteArray(COPY_BUFFER)
        while (true) {
          checkActive()
          val count = input.read(buffer)
          if (count < 0) break
          output.write(buffer, 0, count)
        }
        output.fd.sync()
      }
    }
  }

  private fun sha256(file: File, checkActive: () -> Unit): ByteArray =
    MessageDigest.getInstance("SHA-256").run {
      FileInputStream(file).use { input ->
        val buffer = ByteArray(COPY_BUFFER)
        while (true) {
          checkActive()
          val count = input.read(buffer)
          if (count < 0) break
          update(buffer, 0, count)
        }
      }
      digest()
    }

  private fun elapsedMs(startNanos: Long): Long =
    (System.nanoTime() - startNanos) / 1_000_000

  private data class PageCounters(val hits: Int, val misses: Int, val rasterScale: Float)

  private class Timings {
    var renderMs = 0L
    var mergeGraftMs = 0L
    var mergeSaveMs = 0L
    var verifyMs = 0L
    var finalizeMs = 0L
  }

  private companion object {
    const val COPY_BUFFER = 64 * 1024
    const val TICKER_INTERVAL_MS = 400L
    const val PDF_POINTS_PER_LOGICAL_PIXEL = 72f / 96f
  }
}
