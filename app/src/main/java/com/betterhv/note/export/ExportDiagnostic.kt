package com.betterhv.note.export

import android.util.Log
import com.betterhv.note.EventLog
import java.io.File
import java.util.UUID

data class ExportDiagnostic(
  val id: String,
  val taskId: UUID,
  val stage: ExportProgress.Stage,
  val pageId: UUID?,
  val reason: String,
  val details: String,
) {
  val summary: String get() = "$stage: $reason [$id]"

  companion object {
    fun capture(taskId: UUID, progress: ExportProgress?, error: Throwable, directory: File): ExportDiagnostic {
      val id = UUID.randomUUID().toString()
      val causes = generateSequence(error) { it.cause }.take(12).toList()
      val reason = causes.joinToString(" → ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
      val stage = progress?.stage ?: ExportProgress.Stage.PREPARING
      val details = "Diagnostic: $id\nTask: $taskId\nStage: $stage\nPage: ${progress?.pageId ?: "-"}\n" +
        error.stackTraceToString()
      Log.e("BetterHvNote.Export", details)
      EventLog.log("Export", "$stage task=$taskId page=${progress?.pageId} $reason [$id]")
      runCatching {
        directory.mkdirs()
        File(directory, "$id.log").writeText(details)
        directory.listFiles()?.filter { it.extension == "log" }?.sortedByDescending { it.lastModified() }
          ?.drop(30)?.forEach { it.delete() }
      }.onFailure { Log.e("BetterHvNote.Export", "Could not persist diagnostic $id", it) }
      return ExportDiagnostic(id, taskId, stage, progress?.pageId, reason, details)
    }
  }
}
