package com.betterhv.note.storage

import com.betterhv.note.ink.InkPoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Versioned, compressed delta encoding for InkPoint arrays (spec §51-53).
 * Coordinates and timestamps after the first sample are stored as deltas;
 * pressure/tilt remain absolute so decoding does not accumulate their error.
 */
object PointBlobCodec {
    private const val MAGIC = 0x494E4B50 // INKP
    private const val VERSION = 1
    private const val MAX_POINTS = 10_000_000

    fun encode(points: List<InkPoint>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DeflaterOutputStream(bytes).use { compressed ->
            DataOutputStream(compressed).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(points.size)
                var previousX = 0f
                var previousY = 0f
                var previousTime = 0L
                points.forEachIndexed { index, point ->
                    out.writeFloat(if (index == 0) point.x else point.x - previousX)
                    out.writeFloat(if (index == 0) point.y else point.y - previousY)
                    out.writeLong(if (index == 0) point.timestamp else point.timestamp - previousTime)
                    out.writeFloat(point.pressure)
                    out.writeFloat(point.tiltX)
                    out.writeFloat(point.tiltY)
                    out.writeFloat(point.azimuth)
                    previousX = point.x
                    previousY = point.y
                    previousTime = point.timestamp
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(blob: ByteArray): List<InkPoint> = try {
        DataInputStream(InflaterInputStream(ByteArrayInputStream(blob))).use { input ->
            require(input.readInt() == MAGIC) { "Invalid ink point blob" }
            val version = input.readInt()
            require(version == VERSION) { "Unsupported ink point blob version $version" }
            val count = input.readInt()
            require(count in 0..MAX_POINTS) { "Invalid ink point count $count" }
            val result = ArrayList<InkPoint>(count)
            var previousX = 0f
            var previousY = 0f
            var previousTime = 0L
            repeat(count) { index ->
                val storedX = input.readFloat()
                val storedY = input.readFloat()
                val storedTime = input.readLong()
                val x = if (index == 0) storedX else previousX + storedX
                val y = if (index == 0) storedY else previousY + storedY
                val timestamp = if (index == 0) storedTime else previousTime + storedTime
                result += InkPoint(
                    x = x,
                    y = y,
                    timestamp = timestamp,
                    pressure = input.readFloat(),
                    tiltX = input.readFloat(),
                    tiltY = input.readFloat(),
                    azimuth = input.readFloat()
                )
                previousX = x
                previousY = y
                previousTime = timestamp
            }
            result
        }
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("Corrupt ink point blob", e)
    }
}
