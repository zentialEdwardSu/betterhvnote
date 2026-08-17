package com.betterhv.note.doc

import java.util.UUID

/**
 * Spec §7.1. `pageOrder` is kept separate from the page map so pages can be
 * reordered/deleted without their UUIDs changing -- page numbers are display
 * order only, never identity (spec §2.4/§24).
 *
 * Phase 6 UI navigates this order and lazily attaches only the current and
 * adjacent Page scenes; metadata remains registered when a Scene is evicted.
 */
class Notebook(
    val id: UUID = UUID.randomUUID(),
    var title: String = "Untitled",
    val createdAt: Long = System.currentTimeMillis(),
    updatedAt: Long = createdAt
) {
    data class PageMetadata(
        val id: UUID,
        val width: Float,
        val height: Float,
        val bookmarked: Boolean,
        val contentRevision: Long,
        val createdAt: Long,
        val updatedAt: Long
    )

    var updatedAt: Long = updatedAt
        private set

    val pageOrder: MutableList<UUID> = ArrayList()
    private val pages: MutableMap<UUID, Page> = LinkedHashMap()
    private val pageMetadata: MutableMap<UUID, PageMetadata> = LinkedHashMap()

    fun addPage(page: Page = Page(), index: Int = pageOrder.size) {
        pages[page.id] = page
        pageMetadata[page.id] = page.toMetadata()
        pageOrder.add(index.coerceIn(0, pageOrder.size), page.id)
        touch()
    }

    /** Registers an unloaded page discovered from storage. */
    fun addPageMetadata(metadata: PageMetadata, index: Int = pageOrder.size) {
        pageMetadata[metadata.id] = metadata
        if (metadata.id !in pageOrder) {
            pageOrder.add(index.coerceIn(0, pageOrder.size), metadata.id)
        }
    }

    /** Attaches a lazily loaded Scene without changing page order. */
    fun attachPage(page: Page) {
        require(page.id in pageOrder) { "Page ${page.id} is not registered in this notebook" }
        pages[page.id] = page
        pageMetadata[page.id] = page.toMetadata()
    }

    fun refreshPageMetadata(page: Page) {
        if (page.id in pageOrder) pageMetadata[page.id] = page.toMetadata()
    }

    /** Evicts only the in-memory Scene; identity/order/metadata remain. */
    fun detachPage(id: UUID): Page? = pages.remove(id)

    fun removePage(id: UUID): Page? {
        val page = pages.remove(id) ?: return null
        pageOrder.remove(id)
        pageMetadata.remove(id)
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

    fun metadata(id: UUID): PageMetadata? =
        pages[id]?.toMetadata()?.also { pageMetadata[id] = it } ?: pageMetadata[id]

    fun allPageMetadata(): List<PageMetadata> = pageOrder.mapNotNull(::metadata)

    fun markChanged() = touch()

    private fun touch() {
        updatedAt = System.currentTimeMillis()
    }

    private fun Page.toMetadata() = PageMetadata(
        id, width, height, bookmarked, contentRevision, createdAt, updatedAt
    )
}
