package com.betterhv.note.storage

import com.betterhv.note.ink.InkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.DeflaterOutputStream

class PointBlobCodecTest {
  @Test
  fun roundTripPreservesEveryInkPointField() {
    val points = listOf(
      InkPoint(100.25f, -2.5f, 0.1f, 1_000L, 0.2f, -0.3f, 1.4f),
      InkPoint(101.0f, -1.75f, 0.7f, 1_007L, 0.4f, -0.1f, 1.8f),
      InkPoint(98.5f, 4.25f, 1.0f, 1_100L, 0f, 0f, 0f),
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

  @Test
  fun decodesVersionOnePointScalar() {
    val bytes = ByteArrayOutputStream()
    DeflaterOutputStream(bytes).use { compressed ->
      DataOutputStream(compressed).use { out ->
        out.writeInt(0x494E4B50)
        out.writeInt(1)
        out.writeInt(1)
        out.writeFloat(12f)
        out.writeFloat(34f)
        out.writeLong(56L)
        out.writeFloat(0.75f)
        out.writeFloat(0f)
        out.writeFloat(0f)
        out.writeFloat(0f)
      }
    }

    val decoded = PointBlobCodec.decode(bytes.toByteArray()).single()
    assertEquals(12f, decoded.x)
    assertEquals(0.75f, decoded.pressure)
  }

  @Test
  fun versionOnePayloadKeepsItsOriginalPerPointSize() {
    val encoded = PointBlobCodec.encode(
      listOf(
        InkPoint(1f, 2f, 0.25f, 10L, 0.1f, 0.2f, 0.3f),
        InkPoint(3f, 4f, 0.75f, 20L, 0.4f, 0.5f, 0.6f),
      ),
    )
    val inflated = InflaterInputStream(encoded.inputStream()).readBytes()

    // Header: magic/version/count = 12 bytes. V1 point: x/y (8), time (8),
    // scalar/tiltX/tiltY/azimuth (16) = 32 bytes, with no width field.
    assertEquals(12 + 2 * 32, inflated.size)
  }
}
