package com.betterhv.note.storage

import com.betterhv.note.doc.Notebook
import java.util.UUID

enum class StartupBehavior { WORKING_COPY, LAST_OPENED }

data class NotebookSummary(
    val id: UUID,
    val title: String,
    val pageCount: Int,
    val cover: Notebook.PageMetadata?,
    val updatedAt: Long,
    val isWorkingCopy: Boolean,
    val isCurrent: Boolean
)

data class NotebookTransferResult(
    val targetNotebookId: UUID,
    val movedPageIds: List<UUID>,
    val sourceReplacementPageId: UUID?
)

data class NotebookDeletionResult(
    val deletedNotebookId: UUID,
    val deletedPageIds: List<UUID>,
    val activeNotebookId: UUID
)

/** Keeps arbitrary UI selection in authoritative source-page order. */
internal fun orderedSelectedPages(
    sourceOrder: List<UUID>,
    selectedIds: Set<UUID>
): List<UUID> = sourceOrder.filter(selectedIds::contains)

internal fun resolveStartupNotebookId(
    behavior: StartupBehavior,
    workingNotebookId: UUID,
    activeNotebookId: UUID?,
    notebookExists: (UUID) -> Boolean
): UUID = when {
    behavior == StartupBehavior.LAST_OPENED && activeNotebookId != null &&
        notebookExists(activeNotebookId) -> activeNotebookId
    else -> workingNotebookId
}
