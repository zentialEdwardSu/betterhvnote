package com.betterhv.note.doc

import java.util.UUID

/**
 * Spec §7.1. `pageOrder` is kept separate from the page map so pages can be
 * reordered/deleted without their UUIDs changing -- page numbers are display
 * order only, never identity (spec §2.4/§24).
 *
 * V1 UI only ever exercises a single page; this exists in full so the later
 * multi-page phase (§88 Phase 6) needs no rework of the model underneath it.
 */
class Notebook(
    val id: UUID = UUID.randomUUID(),
    var title: String = "Untitled",
    val createdAt: Long = System.currentTimeMillis()
) {
    var updatedAt: Long = createdAt
        private set

    val pageOrder: MutableList<UUID> = ArrayList()
    private val pages: MutableMap<UUID, Page> = LinkedHashMap()

    fun addPage(page: Page = Page(), index: Int = pageOrder.size) {
        pages[page.id] = page
        pageOrder.add(index.coerceIn(0, pageOrder.size), page.id)
        touch()
    }

    fun removePage(id: UUID): Page? {
        val page = pages.remove(id) ?: return null
        pageOrder.remove(id)
        touch()
        return page
    }

    fun movePage(id: UUID, toIndex: Int) {
        if (!pageOrder.remove(id)) return
        pageOrder.add(toIndex.coerceIn(0, pageOrder.size), id)
        touch()
    }

    fun getPage(id: UUID): Page? = pages[id]

    fun pageAt(index: Int): Page? = pageOrder.getOrNull(index)?.let { pages[it] }

    private fun touch() {
        updatedAt = System.currentTimeMillis()
    }
}
