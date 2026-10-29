package com.betterhv.note.export

import java.util.UUID

enum class ExportRunResult { SUCCESS, FAILED, CANCELLED }

/**
 * One generation run's timings and counters.
 *
 * Stored as a small key/value file next to the export artifacts so a slow export
 * can still be diagnosed after the app was restarted. This is intentionally not
 * a database table: the report is overwritten per task and is best-effort.
 */
data class ExportRunReport(
  val taskId: UUID,
  val mode: ExportRenderMode,
  val result: ExportRunResult,
  val pageCount: Int,
  val cacheHits: Int,
  val cacheMisses: Int,
  val rasterScale: Float,
  val startedAt: Long,
  val finishedAt: Long,
  val renderMs: Long,
  val mergeGraftMs: Long,
  val mergeSaveMs: Long,
  val verifyMs: Long,
  val finalizeMs: Long,
  val outputBytes: Long,
  val failedStage: ExportProgress.Stage? = null,
  val error: String? = null,
) {
  val totalMs: Long get() = (finishedAt - startedAt).coerceAtLeast(0)
}

/** Line-oriented, framework-free codec; malformed input decodes to null. */
object ExportRunReportCodec {
  private const val VERSION = 1

  fun encode(report: ExportRunReport): String = buildString {
    append("v=").append(VERSION).append('\n')
    append("task=").append(report.taskId).append('\n')
    append("mode=").append(report.mode.name).append('\n')
    append("result=").append(report.result.name).append('\n')
    append("pages=").append(report.pageCount).append('\n')
    append("hits=").append(report.cacheHits).append('\n')
    append("misses=").append(report.cacheMisses).append('\n')
    append("scale=").append(report.rasterScale).append('\n')
    append("started=").append(report.startedAt).append('\n')
    append("finished=").append(report.finishedAt).append('\n')
    append("render=").append(report.renderMs).append('\n')
    append("graft=").append(report.mergeGraftMs).append('\n')
    append("save=").append(report.mergeSaveMs).append('\n')
    append("verify=").append(report.verifyMs).append('\n')
    append("finalize=").append(report.finalizeMs).append('\n')
    append("bytes=").append(report.outputBytes).append('\n')
    append("stage=").append(report.failedStage?.name.orEmpty()).append('\n')
    append("error=").append(report.error.sanitized()).append('\n')
  }

  fun decode(text: String): ExportRunReport? {
    val values = HashMap<String, String>()
    text.lineSequence().forEach { line ->
      if (line.isEmpty()) return@forEach
      val separator = line.indexOf('=')
      if (separator <= 0) return@forEach
      values[line.substring(0, separator)] = line.substring(separator + 1)
    }
    if (values["v"] != VERSION.toString()) return null
    return runCatching {
      val report = ExportRunReport(
        taskId = UUID.fromString(required(values, "task")),
        mode = ExportRenderMode.valueOf(required(values, "mode")),
        result = ExportRunResult.valueOf(required(values, "result")),
        pageCount = count(values, "pages"),
        cacheHits = count(values, "hits"),
        cacheMisses = count(values, "misses"),
        rasterScale = required(values, "scale").toFloat().also {
          require(it.isFinite() && it > 0f) { "Invalid raster scale" }
        },
        startedAt = number(values, "started"),
        finishedAt = number(values, "finished"),
        renderMs = number(values, "render"),
        mergeGraftMs = number(values, "graft"),
        mergeSaveMs = number(values, "save"),
        verifyMs = number(values, "verify"),
        finalizeMs = number(values, "finalize"),
        outputBytes = number(values, "bytes"),
        failedStage = values["stage"].orEmpty().takeIf(String::isNotEmpty)?.let {
          ExportProgress.Stage.valueOf(it)
        },
        error = values["error"].orEmpty().takeIf(String::isNotEmpty),
      )
      require(report.finishedAt >= report.startedAt) { "Invalid run interval" }
      require(report.cacheHits + report.cacheMisses <= report.pageCount) { "Invalid cache counters" }
      report
    }.getOrNull()
  }

  private fun required(values: Map<String, String>, key: String): String =
    requireNotNull(values[key]) { "Missing $key" }

  private fun count(values: Map<String, String>, key: String): Int =
    number(values, key).also { require(it <= Int.MAX_VALUE) }.toInt() // durations can exceed Int for bytes

  private fun number(values: Map<String, String>, key: String): Long =
    required(values, key).toLong().also { require(it >= 0L) { "Negative $key" } }

  private fun String?.sanitized(): String =
    orEmpty().replace('\n', ' ').replace('\r', ' ').take(MAX_ERROR_LENGTH)

  private const val MAX_ERROR_LENGTH = 500
}
