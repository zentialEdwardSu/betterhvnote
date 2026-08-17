package com.betterhv.transfer.android

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EncryptedFileTransferTest {
    @Test
    fun multiMegabyteTransferWaitsForVerifiedAcknowledgement() {
        val directory = Files.createTempDirectory("betterhv-transfer").toFile()
        val source = File(directory, "source.bin")
        val destination = File(directory, "destination.bin")
        val bytes = ByteArray(2_411_724) { index -> (index * 31).toByte() }
        source.writeBytes(bytes)
        val itemId = UUID.randomUUID()
        val key = ByteArray(32) { it.toByte() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val received = executor.submit<ReceivedFile> {
                EncryptedFileTransfer.receive(destination, itemId, key)
            }
            Thread.sleep(100)
            EncryptedFileTransfer.send("127.0.0.1", itemId, source, key)
            val result = received.get(10, TimeUnit.SECONDS)
            assertEquals(bytes.size.toLong(), result.byteLength)
            assertArrayEquals(bytes, destination.readBytes())
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }
}
