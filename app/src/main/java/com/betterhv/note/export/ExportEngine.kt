package com.betterhv.note.export

import android.graphics.Bitmap
import com.betterhv.note.storage.NotebookRepository
import com.betterhv.note.storage.PageSnapshot
import com.betterhv.note.doc.PageKind
import com.betterhv.note.pdf.MuPdfEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
    private val pdfWriter: PdfInkAnnotationWriter = PdfInkAnnotationWriter()
) {
    private val muPdf = MuPdfEngine()
    suspend fun generate(
        taskId: UUID,
        onProgress: (ExportProgress) -> Unit = {}
    ): ExportResult = withContext(Dispatchers.IO) {
        val task = taskRepository.loadTask(taskId)
            ?: return@withContext ExportResult.Failure("导出任务不存在")
        try {
            onProgress(ExportProgress(0, 1, null, ExportProgress.Stage.PREPARING))
            val sources = taskRepository.resolveSources(task)
            if (sources.isEmpty()) return@withContext ExportResult.Failure("笔记本没有可导出的页面")
            if (task.scope != ExportScope.ALL_PAGES && sources.size != task.pageIds.size) {
                return@withContext ExportResult.Failure("导出任务包含已删除的页面")
            }
            taskRepository.currentArtifact(task, sources)?.let { return@withContext ExportResult.Success(it) }

            exportRoot.mkdirs()
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
                    target.length(), sha256(target), fingerprint, now
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
            val message = t.message ?: t.javaClass.simpleName
            taskRepository.markError(taskId, message)
            ExportResult.Failure(message)
        }
    }

    fun deleteTask(taskId: UUID) {
        taskRepository.deleteTask(taskId).forEach(File::delete)
        File(exportRoot, "pages/$taskId").deleteRecursively()
    }

    fun pruneOrphanFiles() {
        val retained = taskRepository.referencedInternalFiles()
        if (!exportRoot.isDirectory) return
        exportRoot.walkBottomUp().forEach { file ->
            if (file.isFile && file.absolutePath !in retained) file.delete()
            if (file.isDirectory && file != exportRoot && file.list().isNullOrEmpty()) file.delete()
        }
    }

    private suspend fun generatePng(
        notebookId: UUID,
        source: ExportPageSource,
        output: File,
        onProgress: (ExportProgress) -> Unit
    ) {
        coroutineContext.ensureActive()
        onProgress(ExportProgress(0, 1, source.id))
        val page = notebookRepository.loadPage(source.id) ?: error("页面已删除")
        val snapshot = PageSnapshot.capture(page)
        val bitmap = if (page.kind == PageKind.PDF_SOURCE) {
            val record = notebookRepository.pdfDocument(notebookId) ?: error("PDF 资源记录缺失")
            val file = notebookRepository.resolveDocumentAsset(record.assetPath) ?: error("PDF 资源文件缺失")
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
        } else renderer.renderToBitmap(
            snapshot, (snapshot.metadata.width * 2f).toInt(), (snapshot.metadata.height * 2f).toInt()
        )
        try {
            FileOutputStream(output).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG 编码失败" }
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
        onProgress: (ExportProgress) -> Unit
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
                val page = notebookRepository.loadPage(source.id) ?: error("页面已删除")
                val finalPage = File(pageDir, ExportTaskRepository.pageCacheFileName(source))
                val basePage = File(pageDir, "${source.id}-r${source.contentRevision}-base.tmp.pdf")
                val tempPage = File(pageDir, "${finalPage.name}.tmp")
                try {
                    val snapshot = PageSnapshot.capture(page)
                    if (page.kind == PageKind.PDF_SOURCE) {
                        val record = notebookRepository.pdfDocument(task.notebookId)
                            ?: error("PDF 资源记录缺失")
                        val original = notebookRepository.resolveDocumentAsset(record.assetPath)
                            ?: error("PDF 资源文件缺失")
                        pdfWriter.copySourcePage(
                            original,
                            requireNotNull(page.pdfSource).sourcePageIndex,
                            basePage
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
                source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
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
