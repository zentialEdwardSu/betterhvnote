package com.betterhv.note.export

import java.io.File
import java.util.UUID

enum class ExportScope { SINGLE_PAGE, SELECTED_PAGES, ALL_PAGES }

enum class ExportFormat(val extension: String, val mimeType: String) {
  PNG("png", "image/png"),
  PDF("pdf", "application/pdf"),
}

/** How ink is represented in a PDF artifact. */
enum class ExportRenderMode {
  /** One editable PDF Ink annotation per stroke (the pre-existing behavior). */
  VECTOR,

  /** Each page is rasterized once and embedded as a single image. */
  FAST,
}

data class ExportTask(
  val id: UUID,
  val notebookId: UUID,
  val scope: ExportScope,
  val format: ExportFormat,
  val pageIds: List<UUID>,
  val createdAt: Long,
  val lastUsedAt: Long,
  val renderMode: ExportRenderMode = ExportRenderMode.VECTOR,
)

enum class ExportTaskState { NEVER_GENERATED, CURRENT, OUTDATED, SOURCE_MISSING, FAILED }

data class ExportTaskSummary(
  val task: ExportTask,
  val notebookTitle: String,
  val pageCount: Int,
  val stalePageCount: Int,
  val state: ExportTaskState,
  val lastGeneratedAt: Long?,
  val lastError: String?,
  val lastRun: ExportRunReport? = null,
)

data class ExportPageSource(
  val id: UUID,
  val contentRevision: Long,
  val position: Int,
  val backgroundRevision: String = "",
)

data class ExportNotebookOption(val id: UUID, val title: String, val pages: List<ExportPageSource>)

data class ExportArtifact(
  val id: UUID,
  val taskId: UUID,
  val file: File,
  val displayName: String,
  val mimeType: String,
  val byteLength: Long,
  val sha256: ByteArray,
  val fingerprint: String,
  val createdAt: Long,
)

data class ExportProgress(
  val current: Int,
  val total: Int,
  val pageId: UUID?,
  val stage: Stage = Stage.RENDERING,
  /** Wall-clock time since the generation started. */
  val elapsedMs: Long = 0,
  /** Wall-clock time since the current stage started. */
  val stageElapsedMs: Long = 0,
) {
  enum class Stage {
    PREPARING,
    RENDERING,
    ASSEMBLING,

    /** Native PDF write; cannot report fractions and cannot be interrupted. */
    WRITING,
    VERIFYING,
    PUBLISHING,
    DISCOVERING,
    SENDING,
    CANCELLING,
  }
}

sealed interface ExportResult {
  data class Success(val artifact: ExportArtifact) : ExportResult
  data class Failure(val error: String, val diagnostic: ExportDiagnostic? = null) : ExportResult
  data object Cancelled : ExportResult
}
