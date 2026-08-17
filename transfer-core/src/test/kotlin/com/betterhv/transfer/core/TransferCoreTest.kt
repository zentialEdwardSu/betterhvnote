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

    private fun item(kind: ContentKind, position: Long) = QueueItem(
        UUID.randomUUID(), null, kind, if (kind == ContentKind.IMAGE) "image/jpeg" else "text/plain",
        1, ByteArray(32) { position.toByte() }, position, position
    )
}
