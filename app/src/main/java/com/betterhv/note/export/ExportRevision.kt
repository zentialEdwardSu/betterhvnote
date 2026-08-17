package com.betterhv.note.export

import java.security.MessageDigest
import java.util.UUID

object ExportRevision {
    fun fingerprint(
        format: ExportFormat,
        sources: List<ExportPageSource>,
        rendererVersion: Int
    ): String {
        val value = buildString {
            append(rendererVersion).append('|').append(format.name).append('|')
            sources.forEach { append(it.id).append(':').append(it.contentRevision).append(';') }
        }
        return MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun state(
        sourceMissing: Boolean,
        artifactExists: Boolean,
        storedFingerprint: String?,
        currentFingerprint: String?,
        hasError: Boolean
    ): ExportTaskState = when {
        sourceMissing -> ExportTaskState.SOURCE_MISSING
        hasError && !artifactExists -> ExportTaskState.FAILED
        !artifactExists || storedFingerprint == null -> ExportTaskState.NEVER_GENERATED
        storedFingerprint != currentFingerprint -> ExportTaskState.OUTDATED
        else -> ExportTaskState.CURRENT
    }

    fun stalePageCount(
        sources: List<ExportPageSource>,
        cachedRevisions: Map<UUID, Long>
    ): Int = sources.count { cachedRevisions[it.id] != it.contentRevision }
}
