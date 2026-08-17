package com.betterhv.note.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ThumbnailRevisionGateTest {
    @Test
    fun staleGenerationCannotReplaceNewerRequest() {
        val id = UUID.randomUUID()
        val gate = ThumbnailRevisionGate()
        val old = ThumbnailKey(id, 4L)
        val current = ThumbnailKey(id, 5L)
        assertTrue(gate.expect(old))
        assertTrue(gate.expect(current))
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(current))
        assertFalse(gate.expect(old))
        assertTrue(gate.isCurrent(current))
    }
}
