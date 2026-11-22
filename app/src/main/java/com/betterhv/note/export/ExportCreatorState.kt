package com.betterhv.note.export

import java.util.UUID

internal object ExportCreatorState {
  fun formatForScopeChange(currentFormat: ExportFormat, newScope: ExportScope): ExportFormat =
    if (newScope == ExportScope.SINGLE_PAGE) currentFormat else ExportFormat.PDF

  fun resolveNotebookId(currentId: UUID?, initialId: UUID, availableIds: List<UUID>): UUID? = when {
    currentId != null && currentId in availableIds -> currentId
    initialId in availableIds -> initialId
    else -> availableIds.firstOrNull()
  }
}
