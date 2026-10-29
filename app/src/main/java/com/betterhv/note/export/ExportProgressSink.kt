package com.betterhv.note.export

import java.util.UUID

/**
 * Coalesces progress updates for the engine.
 *
 * Page/phase changes are emitted immediately; updates inside the same page are
 * throttled so a dense page cannot flood the Compose state flow. Elapsed times
 * are recomputed on every emission, including [tick] calls from the engine's
 * background ticker while a native save is running.
 */
class ExportProgressSink(
  private val report: (ExportProgress) -> Unit,
  private val clock: () -> Long = System::currentTimeMillis,
  private val minIntervalMs: Long = 200,
) {
  private val startedAt = clock()
  private var stageStartedAt = startedAt
  private var lastEmitAt = startedAt
  private var current = ExportProgress(0, 0, null, ExportProgress.Stage.PREPARING)

  val last: ExportProgress
    @Synchronized get() = current

  @Synchronized
  fun begin(
    stage: ExportProgress.Stage,
    total: Int = 0,
    currentPage: Int = 0,
    pageId: UUID? = null,
  ) {
    stageStartedAt = clock()
    emit(currentPage, total, pageId, stage, force = true)
  }

  @Synchronized
  fun update(
    stage: ExportProgress.Stage,
    currentPage: Int,
    total: Int,
    pageId: UUID? = null,
    force: Boolean = false,
  ) = emit(currentPage, total, pageId, stage, force)

  /** Refreshes only the elapsed fields; used by the ticker during native writes. */
  @Synchronized
  fun tick() {
    emit(current.current, current.total, current.pageId, current.stage, force = false)
  }

  private fun emit(
    currentPage: Int,
    total: Int,
    pageId: UUID?,
    stage: ExportProgress.Stage,
    force: Boolean,
  ) {
    val now = clock()
    val stageChanged = stage != current.stage
    val pageChanged = currentPage != current.current ||
      total != current.total ||
      pageId != current.pageId
    if (!force && !stageChanged && !pageChanged && now - lastEmitAt < minIntervalMs) return
    if (stageChanged) stageStartedAt = now
    current = ExportProgress(
      current = currentPage,
      total = total,
      pageId = pageId,
      stage = stage,
      elapsedMs = (now - startedAt).coerceAtLeast(0),
      stageElapsedMs = (now - stageStartedAt).coerceAtLeast(0),
    )
    lastEmitAt = now
    report(current)
  }
}
