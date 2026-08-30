package com.betterhv.note.sender.desktop

import java.time.Instant

internal object DesktopLog {
    @Synchronized
    fun info(stage: String, detail: String = "") {
        val suffix = detail.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
        System.err.println("${Instant.now()} [NoteLink] [${Thread.currentThread().name}] $stage$suffix")
        System.err.flush()
    }

    fun error(stage: String, error: Throwable) {
        info(stage, "failed=${error.javaClass.simpleName} message=${error.message.orEmpty()}")
    }
}
