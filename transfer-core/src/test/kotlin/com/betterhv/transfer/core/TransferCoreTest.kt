package com.betterhv.transfer.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferCoreTest {
    @Test fun `typed queue skips an earlier different kind`() {
        var now = 1000L
        val store = InMemoryQueueStore()
        val text = item(ContentKind.TEXT, 0)
        val image = item(ContentKind.IMAGE, 1)
        store.insert(text); store.insert(image)
        val coordinator = QueueCoordinator(store) { now }

        assertEquals(image.id, coordinator.leaseNext(ContentKind.IMAGE, "note")!!.id)
        assertEquals(text.id, store.next(ContentKind.TEXT, "note", now)!!.id)
        coordinator.release(image.id)
        assertEquals(image.id, store.next(ContentKind.IMAGE, "note", now)!!.id)
        now += TransferLimits.LEASE_TIMEOUT_MILLIS + 1
        assertEquals(0, store.releaseExpired(now))
    }

    @Test fun `expired lease returns to pending`() {
        var now = 0L
        val store = InMemoryQueueStore().also { it.insert(item(ContentKind.IMAGE, 0)) }
        val coordinator = QueueCoordinator(store) { now }
        val leased = coordinator.leaseNext(ContentKind.IMAGE, "note")!!
        now = TransferLimits.LEASE_TIMEOUT_MILLIS + 1
        assertEquals(1, store.releaseExpired(now))
        assertEquals(QueueState.PENDING, store.find(leased.id)!!.state)
    }

    @Test fun `commit is idempotent at store boundary`() {
        val store = InMemoryQueueStore()
        val value = item(ContentKind.TEXT, 0)
        store.insert(value)
        val coordinator = QueueCoordinator(store)
        assertEquals(true, coordinator.commit(value.id))
        assertEquals(false, coordinator.commit(value.id))
        assertNull(store.find(value.id))
    }

    @Test fun `leasing an unassigned item binds it to the first device`() {
        val store = InMemoryQueueStore()
        val value = item(ContentKind.TEXT, 0)
        store.insert(value)
        val coordinator = QueueCoordinator(store)

        val leased = requireNotNull(coordinator.leaseNext(ContentKind.TEXT, "note-a"))

        assertEquals("note-a", leased.destinationDeviceId)
        assertEquals("note-a", store.find(value.id)?.destinationDeviceId)
        coordinator.release(value.id)
        assertNull(coordinator.leaseNext(ContentKind.TEXT, "note-b"))
    }

    @Test fun `pairing derives the same secret and verification code`() {
        val a = TransferCrypto.generatePairingKeyPair()
        val b = TransferCrypto.generatePairingKeyPair()
        val ab = TransferCrypto.sharedSecret(a, b.public.encoded)
        val ba = TransferCrypto.sharedSecret(b, a.public.encoded)
        assertArrayEquals(ab, ba)
        assertEquals(
            TransferCrypto.verificationCode(ab, "pair".encodeToByteArray()),
            TransferCrypto.verificationCode(ba, "pair".encodeToByteArray())
        )
    }

    @Test fun `secure frames reject tampering and replay`() {
        val key = TransferCrypto.randomBytes(32)
        val sender = SecureFrameChannel(key, 1, 2)
        val receiver = SecureFrameChannel(key, 2, 1)
        val frame = sender.seal(MessageType.HEARTBEAT, UUID.randomUUID(), "hello".encodeToByteArray())
        assertArrayEquals("hello".encodeToByteArray(), receiver.open(frame))
        runCatching { receiver.open(frame) }.onSuccess { error("Replay should fail") }

        val next = sender.seal(MessageType.HEARTBEAT, UUID.randomUUID(), "next".encodeToByteArray())
        val corrupt = next.copy(payload = next.payload.clone().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        try {
            SecureFrameChannel(key, 2, 1).open(corrupt)
            error("Tampering should fail")
        } catch (_: AEADBadTagException) { }
    }

    @Test fun `protocol frame round trips`() {
        val frame = ProtocolFrame(MessageType.GET, UUID.randomUUID(), 42, byteArrayOf(1, 2, 3))
        val bytes = ByteArrayOutputStream().also { ProtocolCodec.write(it, frame) }.toByteArray()
        val decoded = ProtocolCodec.read(ByteArrayInputStream(bytes))
        assertEquals(frame.type, decoded.type)
        assertEquals(frame.requestId, decoded.requestId)
        assertEquals(frame.counter, decoded.counter)
        assertArrayEquals(frame.payload, decoded.payload)
    }

    @Test fun `BLE queue protocol golden bytes are owned by transfer core`() {
        val capabilities = DeviceCapabilities(
            modes = TransferModes.LAN,
            lanEndpoint = NetworkEndpoint("192.168.1.20"),
            ssidFingerprint = ByteArray(8) { it.toByte() }
        )
        assertArrayEquals(
            byteArrayOf(3, 1, 0, 2, 0, 3, 0, 4),
            BleQueueProtocol.encode(BleResponse.Counts(2, 3, 4))
        )
        val command = BleCommand.Capabilities(capabilities)
        assertEquals(command, BleQueueProtocol.decodeCommand(BleQueueProtocol.encode(command)))
        assertEquals(
            BleResponse.Counts(2, 3, 4),
            BleQueueProtocol.decodeResponse(byteArrayOf(3, 1, 0, 2, 0, 3, 0, 4))
        )
        org.junit.Assert.assertThrows(UnsupportedBleProtocolException::class.java) {
            BleQueueProtocol.decodeCommand(byteArrayOf(1, 11))
        }
    }

    @Test fun `ssid normalization fingerprint and session keys are stable`() {
        val key = ByteArray(32) { it.toByte() }
        assertArrayEquals(
            TransferNetworkSecurity.ssidFingerprint(key, "\"Cafe WiFi\""),
            TransferNetworkSecurity.ssidFingerprint(key, "Cafe WiFi")
        )
        assertNull(TransferNetworkSecurity.ssidFingerprint(key, "<unknown ssid>"))
        val id = UUID.randomUUID()
        val nonce = ByteArray(16) { 7 }
        val probe = TransferNetworkSecurity.sessionKey(key, id, nonce, "probe")
        val file = TransferNetworkSecurity.sessionKey(key, id, nonce, "file")
        org.junit.Assert.assertFalse(probe.contentEquals(file))
    }

    @Test fun `route selector requires matching ssid and orders lan before wifi direct`() {
        val wifi = HighBandwidthEndpoint("12:34:56:78:9a:bc", "192.168.49.1", "DIRECT-BH", "password")
        val local = DeviceCapabilities(
            modes = TransferModes.ALL, lanEndpoint = NetworkEndpoint("192.168.1.20"),
            ssidFingerprint = ByteArray(8) { 1 }
        )
        val remote = DeviceCapabilities(
            modes = TransferModes.ALL, lanEndpoint = NetworkEndpoint("192.168.1.21"),
            ssidFingerprint = ByteArray(8) { 1 }, wifiDirectEndpoint = wifi
        )
        assertEquals(
            listOf(TransferMode.LAN, TransferMode.WIFI_DIRECT),
            TransferRouteSelector.candidates(local, CapabilityNegotiation(remote, SsidMatch.MATCH))
        )
        assertEquals(
            listOf(TransferMode.WIFI_DIRECT),
            TransferRouteSelector.candidates(local, CapabilityNegotiation(remote, SsidMatch.UNKNOWN))
        )
    }

    @Test fun `endpoint and capability validation reject unsafe values`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { NetworkEndpoint("0.0.0.0").validated() }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { NetworkEndpoint("224.0.0.1").validated() }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { NetworkEndpoint("192.168.1.255").validated() }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TransferModes.requireValid(4) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { NetworkEndpoint("192.168.1.2", 0) }
    }

    @Test fun `progress meter reports speed average and eta`() {
        var now = 1_000L
        val meter = TransferProgressMeter(now) { now }
        now += 1_000L
        val sample = meter.sample(2_000L, 10_000L)
        assertEquals(2_000L, sample.bytesPerSecond)
        assertEquals(2_000L, sample.averageBytesPerSecond)
        assertEquals(4_000L, sample.etaMillis)
    }

    @Test fun `event log is structured bounded and replayable`() {
        var now = 10L
        val log = TransferEventLog(capacity = 2) { now++ }
        val id = UUID.randomUUID()
        log.record(TransferEvent.PhaseChanged(id, TransferPhase.IDLE, TransferPhase.DISCOVERING))
        log.record(TransferEvent.CapabilityNegotiated(TransferModes.ALL, TransferModes.LAN, SsidMatch.MATCH))
        log.record(TransferEvent.TransportSelected(id, TransferMode.LAN, NetworkEndpoint("192.168.1.8")))

        assertEquals(2, log.entries.value.size)
        assertEquals("capabilities", log.entries.value.first().category)
        assertEquals(TransferLogLevel.INFO, log.entries.value.last().level)
        org.junit.Assert.assertTrue(log.entries.value.last().detail.contains("192.168.1.8:39817"))
        assertEquals("LAN+WIFI_DIRECT", TransferModes.describe(TransferModes.ALL))
    }

    @Test fun `only in-flight phases are active`() {
        TransferPhase.entries.forEach { phase ->
            assertEquals(
                phase !in setOf(TransferPhase.IDLE, TransferPhase.COMPLETE, TransferPhase.FAILED),
                phase.isActiveTransferPhase
            )
        }
    }

    private fun item(kind: ContentKind, position: Long) = QueueItem(
        UUID.randomUUID(), null, kind, if (kind == ContentKind.IMAGE) "image/jpeg" else "text/plain",
        1, ByteArray(32) { position.toByte() }, position, position
    )
}
