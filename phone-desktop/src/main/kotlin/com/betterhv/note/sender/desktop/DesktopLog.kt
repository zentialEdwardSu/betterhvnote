package com.betterhv.note.sender.desktop

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

internal object DesktopLog {
  private const val MAX_LOG_BYTES = 512 * 1024L

  private val logFile: File by lazy {
    val local = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
      ?: File(System.getProperty("user.home"), "AppData/Local").path
    File(local, "BetterHv/NoteLink/notelink.log")
  }

  @Synchronized
  fun info(stage: String, detail: String = "") {
    val suffix = detail.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
    val line = "${Instant.now()} [NoteLink] [${Thread.currentThread().name}] $stage$suffix"
    System.err.println(line)
    System.err.flush()
    appendLine(line)
  }

  fun error(stage: String, error: Throwable) {
    val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd()
    info(stage, "failed=${error.javaClass.simpleName} message=${error.message.orEmpty()}\n$stack")
  }

  private fun appendLine(line: String) {
    runCatching {
      val file = logFile
      file.parentFile?.mkdirs()
      if (file.isFile && file.length() > MAX_LOG_BYTES) {
        val backup = File(file.parentFile, "notelink.log.1")
        backup.delete()
        file.renameTo(backup)
      }
      file.appendText("$line\n")
    }
  }
}
