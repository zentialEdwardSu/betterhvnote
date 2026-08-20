package com.betterhv.transfer.windows

import com.sun.jna.Pointer
import com.betterhv.transfer.core.BleCommand
import com.betterhv.transfer.core.BleQueueProtocol
import com.betterhv.transfer.core.BleResponse
import com.betterhv.transfer.core.BleTransportFrameCodec
import com.betterhv.transfer.core.BleTransportReassembler
import com.betterhv.transfer.core.NoteLinkAdvertisementCodec
import com.betterhv.transfer.core.NoteLinkIdentityCodec
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsNativeContractTest {
    @Test fun lifecycleIsIdempotentAndReplacesRunningResources() {
        val library = FakeLibrary()
        val api = JnaWindowsNativeApi.forTesting(library)

        api.startBle("client", "NoteLink", 1, 2)
        api.startBle("client", "NoteLink", 3, 4)
        assertEquals("192.168.1.25", api.lanInfo().ipv4)
        api.close()
        api.close()

        assertEquals(1, library.initializeCalls)
        assertEquals(2, library.bleStarts)
        assertEquals(2, library.bleStops)
        assertEquals(1, library.shutdownCalls)
    }

    @Test fun desktopGattAdvertisementUsesLegacySizedServiceData() {
        val library = FakeLibrary()
        val api = JnaWindowsNativeApi.forTesting(library)

        api.startBle("desktop-device", "A desktop name that stays in GATT", 2, 3)

        val advertisement = library.lastAdvertisement
        assertEquals(8, advertisement.size)
        val decoded = NoteLinkAdvertisementCodec.decode(advertisement)
        assertEquals(2, decoded.imageCount)
        assertEquals(3, decoded.textCount)
        assertEquals("NoteLink", decoded.deviceName)
    }

    @Test fun queueCountUpdateRefreshesReadableIdentityWithoutRestartingGatt() {
        val library = FakeLibrary()
        val api = JnaWindowsNativeApi.forTesting(library)

        api.startBle("desktop-device", "Office PC", 0, 0)
        api.updateBleCounts(1, 2)

        val identity = NoteLinkIdentityCodec.decode(library.lastIdentity)
        assertEquals(1, identity.imageCount)
        assertEquals(2, identity.textCount)
        assertEquals("Office PC", identity.deviceName)
        assertEquals(1, library.bleStarts)
        assertEquals(0, library.bleStops)
    }

    @Test fun pollsNativeEventQueueAndTimesOutCleanly() {
        val library = FakeLibrary().apply { commands += byteArrayOf(1, 2, 3, 4) }
        val api = JnaWindowsNativeApi.forTesting(library)
        api.startBle("client", "NoteLink", 0, 0)

        assertContentEquals(byteArrayOf(1, 2, 3, 4), api.pollBleCommand(5))
        assertNull(api.pollBleCommand(5))
        api.respondBle(byteArrayOf(9, 8))
        assertEquals(1, library.responses.size)
        assertContentEquals(byteArrayOf(9, 8), library.responses.single())
    }

    @Test fun fragmentsAndReassemblesLargeControlMessages() {
        val command = ByteArray(701) { (it * 17).toByte() }
        val response = ByteArray(913) { (it * 29).toByte() }
        val library = FakeLibrary().apply {
            commands.addAll(BleTransportFrameCodec.fragment(command, messageId = 81, maxFrameBytes = 64))
        }
        val api = JnaWindowsNativeApi.forTesting(library)
        api.startBle("client", "NoteLink", 0, 0)

        assertContentEquals(command, api.pollBleCommand(1_000))
        api.respondBle(response)
        assertTrue(library.responses.size > 1)
        assertContentEquals(response, reassemble(library.responses))
    }

    @Test fun ignoresOrphanedTailFramesAfterGattRestart() {
        val abandoned = BleTransportFrameCodec.fragment(ByteArray(400) { 7 }, messageId = 80, maxFrameBytes = 64)
        val command = ByteArray(240) { (it * 13).toByte() }
        val library = FakeLibrary().apply {
            commands.addAll(abandoned.drop(1))
            commands.addAll(BleTransportFrameCodec.fragment(command, messageId = 81, maxFrameBytes = 64))
        }
        val api = JnaWindowsNativeApi.forTesting(library)
        api.startBle("client", "NoteLink", 0, 0)

        assertContentEquals(command, api.pollBleCommand(1_000))
    }

    @Test fun dataProtectionUsesSizeQueryAndRoundTrips() {
        val api = JnaWindowsNativeApi.forTesting(FakeLibrary())
        val source = byteArrayOf(0, 1, 2, 127, -1)
        val protected = api.protect(source)
        assertTrue(!protected.contentEquals(source))
        assertContentEquals(source, api.unprotect(protected))
    }

    @Test fun protocolGoldenBytesUseVersionTwo() {
        assertEquals(BleCommand.Counts, BleQueueProtocol.decodeCommand(byteArrayOf(2, 1)))
        assertContentEquals(
            byteArrayOf(2, 1, 0, 2, 0, 3),
            BleQueueProtocol.encode(BleResponse.Counts(2, 3))
        )
    }

    @Test fun secureEnvelopeRejectsReplay() {
        val key = ByteArray(32) { it.toByte() }
        val replay = WindowsReplayCache()
        val envelope = WindowsSecureEnvelope.seal(key, byteArrayOf(1, 2, 3), now = 1_000)
        assertContentEquals(byteArrayOf(1, 2, 3), WindowsSecureEnvelope.open(key, envelope, replay, now = 1_000))
        assertFailsWith<IllegalArgumentException> { WindowsSecureEnvelope.open(key, envelope, replay, now = 1_000) }
    }

    private fun reassemble(frames: Iterable<ByteArray>): ByteArray? {
        val receiver = BleTransportReassembler()
        var complete: ByteArray? = null
        frames.forEach { complete = receiver.add(it) ?: complete }
        return complete
    }
}

private class FakeLibrary : NoteLinkNativeLibrary {
    var initializeCalls = 0
    var bleStarts = 0
    var bleStops = 0
    var shutdownCalls = 0
    val commands = ArrayDeque<ByteArray>()
    val responses = ArrayDeque<ByteArray>()
    var lastAdvertisement = ByteArray(0)
    var lastIdentity = ByteArray(0)

    override fun nl_initialize() = 0.also { initializeCalls++ }
    override fun nl_capabilities() = 7
    override fun nl_ble_start(identity: Pointer?, identityLength: Int, advertisement: Pointer?, advertisementLength: Int) =
        0.also {
            bleStarts++
            lastIdentity = identity?.getByteArray(0, identityLength) ?: ByteArray(0)
            lastAdvertisement = advertisement?.getByteArray(0, advertisementLength) ?: ByteArray(0)
        }
    override fun nl_ble_update(
        identity: Pointer?,
        identityLength: Int,
        advertisement: Pointer?,
        advertisementLength: Int
    ) = 0.also {
        lastIdentity = identity?.getByteArray(0, identityLength) ?: ByteArray(0)
        lastAdvertisement = advertisement?.getByteArray(0, advertisementLength) ?: ByteArray(0)
    }
    override fun nl_ble_poll(output: Pointer?, capacity: Int, timeoutMillis: Int): Int {
        val value = commands.pollFirst() ?: return 0
        if (output == null || capacity < value.size) return -value.size
        output.write(0, value, 0, value.size)
        return value.size
    }
    override fun nl_ble_respond(value: Pointer?, length: Int): Int {
        responses += value?.getByteArray(0, length) ?: ByteArray(0)
        return 0
    }
    override fun nl_ble_stop() { bleStops++ }
    override fun nl_lan_info(ipv4: Pointer?, ipv4Capacity: Int, ssid: Pointer?, ssidCapacity: Int): Int {
        val address = "192.168.1.25\u0000".encodeToByteArray()
        val network = "Office WiFi".encodeToByteArray()
        ipv4?.write(0, address, 0, address.size)
        ssid?.write(0, network, 0, network.size)
        return network.size
    }
    override fun nl_protect(input: Pointer?, inputLength: Int, output: Pointer?, outputCapacity: Int) = transform(input, inputLength, output, outputCapacity)
    override fun nl_unprotect(input: Pointer?, inputLength: Int, output: Pointer?, outputCapacity: Int) = transform(input, inputLength, output, outputCapacity)
    private fun transform(input: Pointer?, length: Int, output: Pointer?, capacity: Int): Int {
        if (output == null || capacity < length) return if (length == 0) 0 else -length
        val value = input?.getByteArray(0, length) ?: ByteArray(0)
        value.indices.forEach { value[it] = (value[it].toInt() xor 0x5a).toByte() }
        output.write(0, value, 0, value.size)
        return value.size
    }
    override fun nl_last_error(output: Pointer?, capacity: Int) = 0
    override fun nl_shutdown() { shutdownCalls++ }
}
