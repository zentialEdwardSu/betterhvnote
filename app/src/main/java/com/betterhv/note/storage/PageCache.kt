package com.betterhv.note.storage

import com.betterhv.note.doc.Page
import java.util.LinkedHashMap
import java.util.UUID

/** Small LRU Scene cache; Phase 6 keeps current/previous/next pages resident. */
class PageCache(private val capacity: Int = 3) {
    init { require(capacity > 0) }

    private val pages = LinkedHashMap<UUID, Page>(capacity, 0.75f, true)

    @Synchronized fun get(id: UUID): Page? = pages[id]

    @Synchronized fun put(page: Page): Page? {
        pages[page.id] = page
        if (pages.size <= capacity) return null
        val eldest = pages.entries.first()
        pages.remove(eldest.key)
        return eldest.value
    }

    @Synchronized fun retain(ids: Set<UUID>): List<Page> {
        val removed = pages.filterKeys { it !in ids }.values.toList()
        removed.forEach { pages.remove(it.id) }
        return removed
    }

    @Synchronized fun values(): List<Page> = pages.values.toList()

    @Synchronized fun clear() {
        pages.clear()
    }
}
