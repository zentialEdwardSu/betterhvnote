package com.betterhv.note

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Observable ring-buffer of platform events for the top-left overlay.
 * Phase 1 uses this to confirm each migrated platform call/callback fires.
 */
object EventLog {
    private const val MAX = 200
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val lines = mutableStateListOf<String>()

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        android.util.Log.d("BetterHvNote", line)
        synchronized(lines) {
            lines.add(line)
            while (lines.size > MAX) lines.removeAt(0)
        }
    }

    fun clear() = synchronized(lines) { lines.clear() }
}
