package com.betterhv.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PageManagerModelTest {
    @Test
    fun opensAtChunkContainingCurrentPageAndShowsTwelve() {
        val model = PageManagerModel().openAt(25)
        assertEquals(2, model.chunkIndex)
        assertEquals((24..35).toList(), model.visibleRange(40).toList())
    }

    @Test
    fun chunkPagingClampsAtBothBoundaries() {
        var model = PageManagerModel().openAt(0)
        var result = model.changeChunk(-1, 30)
        assertFalse(result.second)
        model = result.first
        result = model.changeChunk(1, 30)
        assertTrue(result.second)
        assertEquals(1, result.first.chunkIndex)
        model = PageManagerModel(chunkIndex = 2)
        assertFalse(model.changeChunk(1, 30).second)
    }

    @Test
    fun deleteRequiresSamePageWithinThreeSeconds() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val armed = PageManagerModel().deleteClick(first, 1_000L)
        assertEquals(DeleteDecision.ARMED, armed.second)
        assertEquals(DeleteDecision.ARMED, armed.first.deleteClick(second, 2_000L).second)
        assertEquals(DeleteDecision.ARMED, armed.first.deleteClick(first, 4_001L).second)
        assertEquals(DeleteDecision.CONFIRMED, armed.first.deleteClick(first, 3_999L).second)
    }

    @Test
    fun structureChangeMovesPanelToSelectedPageChunkAndCancelsDelete() {
        val pending = PageManagerModel(pendingDeleteId = UUID.randomUUID(), pendingDeleteAt = 10L)
        val updated = pending.afterStructureChange(selectedIndex = 13, pageCount = 20)
        assertEquals(1, updated.chunkIndex)
        assertEquals(null, updated.pendingDeleteId)
    }
}
