package com.betterhv.transfer.windows

import com.sun.jna.Pointer
import com.betterhv.transfer.core.BleCommand
import com.betterhv.transfer.core.BleQueueProtocol
import com.betterhv.transfer.core.BleResponse
import com.betterhv.transfer.core.BleTransportFrameCodec
import com.betterhv.transfer.core.BleTransportReassembler
import com.betterhv.transfer.core.NoteLinkAdvertisementCodec
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
        api.startWifiDirect("DIRECT-BH-1", "password-one")
        api.startWifiDirect("DIRECT-BH-2", "password-two")
        api.close()
        api.close()

        assertEquals(1, library.initializeCalls)
        assertEquals(2, library.bleStarts)
        assertEquals(2, library.bleStops)
        assertEquals(2, library.wifiStarts)
        assertEquals(2, library.wifiStops)
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

    @Test fun dataProtectionUsesSizeQueryAndRoundTrips() {
        val api = JnaWindowsNativeApi.forTesting(FakeLibrary())
        val source = byteArrayOf(0, 1, 2, 127, -1)
        val protected = api.protect(source)
        assertTrue(!protected.contentEquals(source))
        assertContentEquals(source, api.unprotect(protected))
    }

    @Test fun protocolGoldenBytesRemainVersionOne() {
        assertEquals(BleCommand.Counts, BleQueueProtocol.decodeCommand(byteArrayOf(1, 1)))
        assertContentEquals(
            byteArrayOf(1, 1, 0, 2, 0, 3),
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
    var wifiStarts = 0
    var wifiStops = 0
    var shutdownCalls = 0
    val commands = ArrayDeque<ByteArray>()
    val responses = ArrayDeque<ByteArray>()
    var lastAdvertisement = ByteArray(0)

    override fun nl_initialize() = 0.also { initializeCalls++ }
    override fun nl_capabilities() = 7
    override fun nl_ble_start(identity: Pointer?, identityLength: Int, advertisement: Pointer?, advertisementLength: Int) =
        0.also {
            bleStarts++
            lastAdvertisement = advertisement?.getByteArray(0, advertisementLength) ?: ByteArray(0)
        }
    override fun nl_ble_update(advertisement: Pointer?, advertisementLength: Int) = 0.also {
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
    override fun nl_wifi_start(networkName: String, passphrase: String, ownerIp: Pointer?, ownerIpCapacity: Int): Int {
        wifiStarts++
        val bytes = "192.168.137.1\u0000".encodeToByteArray()
        ownerIp?.write(0, bytes, 0, bytes.size)
        return 0
    }
    override fun nl_wifi_stop() { wifiStops++ }
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
