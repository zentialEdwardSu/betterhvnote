package com.betterhv.transfer.android

import com.betterhv.transfer.core.TransferCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BleSecureEnvelopeTest {
    @Test fun envelopeRoundTripsAndRejectsReplay() {
        val key = TransferCrypto.randomBytes(32)
        val cache = BleReplayCache()
        val envelope = BleSecureEnvelope.seal(key, "command".encodeToByteArray(), now = 10_000L)
        assertArrayEquals(
            "command".encodeToByteArray(),
            BleSecureEnvelope.open(key, envelope, cache, now = 10_001L)
        )
        runCatching { BleSecureEnvelope.open(key, envelope, cache, now = 10_002L) }
            .onSuccess { error("Replay should fail") }
    }

    @Test fun expiredEnvelopeIsRejected() {
        val key = TransferCrypto.randomBytes(32)
        val envelope = BleSecureEnvelope.seal(key, byteArrayOf(1), now = 0L)
        runCatching { BleSecureEnvelope.open(key, envelope, BleReplayCache(), now = 120_001L) }
            .onSuccess { error("Expired envelope should fail") }
    }
}
