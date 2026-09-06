package com.betterhv.note

import java.util.UUID

enum class DeleteDecision { ARMED, CONFIRMED }

/** Pure state transitions for the 12-thumbnail manager, kept JVM-testable. */
data class PageManagerModel(
  val chunkIndex: Int = 0,
  val pendingDeleteId: UUID? = null,
  val pendingDeleteAt: Long = 0L,
) {
  fun openAt(pageIndex: Int): PageManagerModel =
    copy(chunkIndex = pageIndex.coerceAtLeast(0) / CHUNK_SIZE).cancelDelete()

  fun visibleRange(pageCount: Int): IntRange {
    if (pageCount <= 0) return IntRange.EMPTY
    val clampedChunk = chunkIndex.coerceIn(0, lastChunk(pageCount))
    val start = clampedChunk * CHUNK_SIZE
    return start..minOf(start + CHUNK_SIZE - 1, pageCount - 1)
  }

  fun changeChunk(delta: Int, pageCount: Int): Pair<PageManagerModel, Boolean> {
    val target = (chunkIndex + delta).coerceIn(0, lastChunk(pageCount))
    return copy(chunkIndex = target).cancelDelete() to (target != chunkIndex)
  }

  fun afterStructureChange(selectedIndex: Int, pageCount: Int): PageManagerModel {
    val target = if (pageCount == 0) {
      0
    } else {
      (selectedIndex.coerceIn(0, pageCount - 1) / CHUNK_SIZE).coerceAtMost(lastChunk(pageCount))
    }
    return copy(chunkIndex = target).cancelDelete()
  }

  fun deleteClick(pageId: UUID, now: Long): Pair<PageManagerModel, DeleteDecision> {
    val confirmed = pendingDeleteId == pageId && now - pendingDeleteAt in 0..DELETE_CONFIRM_MS
    return if (confirmed) {
      cancelDelete() to DeleteDecision.CONFIRMED
    } else {
      copy(pendingDeleteId = pageId, pendingDeleteAt = now) to DeleteDecision.ARMED
    }
  }

  fun cancelDelete(): PageManagerModel = copy(pendingDeleteId = null, pendingDeleteAt = 0L)

  companion object {
    const val CHUNK_SIZE = 12
    const val DELETE_CONFIRM_MS = 3_000L
    const val EDGE_DWELL_MS = 500L

    private fun lastChunk(pageCount: Int): Int = ((pageCount - 1).coerceAtLeast(0)) / CHUNK_SIZE
  }
}
