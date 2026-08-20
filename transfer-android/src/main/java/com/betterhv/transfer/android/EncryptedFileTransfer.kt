package com.betterhv.transfer.android

import com.betterhv.transfer.core.NetworkEndpoint
import com.betterhv.transfer.core.SharedFileTransfer
import java.io.File
import java.net.ServerSocket
import java.util.UUID

data class ReceivedFile(val file: File, val byteLength: Long, val sha256: ByteArray)

/** Android compatibility facade over the v2 shared encrypted file channel. */
object EncryptedFileTransfer {
    fun probe(ownerAddress: String, operationId: UUID, key: ByteArray) =
        SharedFileTransfer.probe(NetworkEndpoint(ownerAddress), operationId, key)

    fun send(
        ownerAddress: String,
        itemId: UUID,
        source: File,
        key: ByteArray,
        offset: Long = 0L,
        progress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        require(offset in 0..source.length())
        SharedFileTransfer.send(NetworkEndpoint(ownerAddress), itemId, source, key, progress)
    }

    fun receive(
        destination: File,
        expectedItemId: UUID,
        key: ByteArray,
        probeKey: ByteArray? = null,
        progress: (Long, Long) -> Unit = { _, _ -> },
        onListening: (ServerSocket) -> Unit = {}
    ): ReceivedFile = SharedFileTransfer.receive(
        destination, expectedItemId, key, probeKey, progress, onListening
    ).let { ReceivedFile(it.file, it.byteLength, it.sha256) }
}
