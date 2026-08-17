package com.betterhv.note.export

import com.betterhv.transfer.core.TransferLimits
import java.util.UUID

internal object ExportFileNames {
    fun displayName(notebookTitle: String?, taskId: UUID, format: ExportFormat): String {
        val shortHash = taskId.toString().substringBefore('-').lowercase()
        val suffix = "_${shortHash}.${format.extension}"
        val availableTitleBytes = TransferLimits.MAX_EXPORT_NAME_BYTES - suffix.encodeToByteArray().size
        return sanitize(notebookTitle).takeUtf8(availableTitleBytes) + suffix
    }

    internal fun sanitize(value: String?): String = value.orEmpty().trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trimEnd('.', ' ')
        .ifBlank { "notebook" }

    private fun String.takeUtf8(maxBytes: Int): String {
        val result = StringBuilder()
        var index = 0
        var byteCount = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            val chunk = String(Character.toChars(codePoint))
            val chunkBytes = chunk.encodeToByteArray().size
            if (byteCount + chunkBytes > maxBytes) break
            result.append(chunk)
            byteCount += chunkBytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }
}
