package com.betterhv.note.storage

import com.betterhv.note.ink.InkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointBlobCodecTest {
    @Test
    fun roundTripPreservesEveryInkPointField() {
        val points = listOf(
            InkPoint(100.25f, -2.5f, 0.1f, 1_000L, 0.2f, -0.3f, 1.4f),
            InkPoint(101.0f, -1.75f, 0.7f, 1_007L, 0.4f, -0.1f, 1.8f),
            InkPoint(98.5f, 4.25f, 1.0f, 1_100L, 0f, 0f, 0f)
        )

        assertEquals(points, PointBlobCodec.decode(PointBlobCodec.encode(points)))
    }

    @Test
    fun emptyStrokeRoundTrips() {
        assertTrue(PointBlobCodec.decode(PointBlobCodec.encode(emptyList())).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownBlob() {
        PointBlobCodec.decode(byteArrayOf(1, 2, 3, 4))
    }
}
