package com.betterhv.transfer.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BleTransportFramesTest {
    @Test fun largeMessageFragmentsAndReassembles() {
        val source = ByteArray(1_001) { (it * 31).toByte() }
        val frames = BleTransportFrameCodec.fragment(source, messageId = 42, maxFrameBytes = 64)
        assertEquals(true, frames.all { it.size <= 64 })
        val receiver = BleTransportReassembler()
        var result: ByteArray? = null
        frames.forEach { result = receiver.add(it) ?: result }
        assertContentEquals(source, result)
    }

    @Test fun checksumAndSequenceAreValidated() {
        val frames = BleTransportFrameCodec.fragment(ByteArray(200) { it.toByte() }, 7, 64)
        val corrupt = frames.last().clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val receiver = BleTransportReassembler()
        frames.dropLast(1).forEach(receiver::add)
        assertFailsWith<IllegalArgumentException> { receiver.add(corrupt) }
    }

    @Test fun newFirstFrameReplacesAbandonedPartialMessage() {
        val receiver = BleTransportReassembler()
        receiver.add(BleTransportFrameCodec.fragment(ByteArray(200), 1, 64).first())
        val replacement = byteArrayOf(4, 5, 6)

        assertContentEquals(replacement, receiver.add(BleTransportFrameCodec.fragment(replacement, 2, 64).single()))
    }

    @Test fun legacyAttPayloadFragmentsAndReassembles() {
        val source = ByteArray(257) { (it * 13).toByte() }
        val frames = BleTransportFrameCodec.fragment(source, messageId = 99, maxFrameBytes = 20)
        val receiver = BleTransportReassembler()
        var result: ByteArray? = null

        frames.forEach { frame ->
            assertEquals(true, frame.size <= 20)
            result = receiver.add(frame) ?: result
        }

        assertContentEquals(source, result)
    }
}
