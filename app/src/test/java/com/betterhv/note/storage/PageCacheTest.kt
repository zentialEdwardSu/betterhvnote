package com.betterhv.note.storage

import com.betterhv.note.doc.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PageCacheTest {
    @Test
    fun evictsLeastRecentlyUsedScene() {
        val cache = PageCache(3)
        val first = Page()
        val second = Page()
        val third = Page()
        val fourth = Page()
        cache.put(first)
        cache.put(second)
        cache.put(third)
        assertSame(first, cache.get(first.id)) // second is now least recent.

        assertSame(second, cache.put(fourth))
        assertNull(cache.get(second.id))
        assertEquals(setOf(first.id, third.id, fourth.id), cache.values().map { it.id }.toSet())
    }

    @Test
    fun retainDropsPagesOutsideNavigationWindow() {
        val cache = PageCache(3)
        val pages = List(3) { Page() }
        pages.forEach(cache::put)

        assertEquals(listOf(pages[0]), cache.retain(setOf(pages[1].id, pages[2].id)))
    }
}
