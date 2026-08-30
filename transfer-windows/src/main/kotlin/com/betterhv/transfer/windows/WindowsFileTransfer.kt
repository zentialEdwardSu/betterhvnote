package com.betterhv.transfer.windows

import com.betterhv.transfer.core.NetworkEndpoint
import com.betterhv.transfer.core.SharedFileTransfer
import java.io.File
import java.net.ServerSocket
import java.util.UUID

data class WindowsReceivedFile(val file: File, val byteLength: Long, val sha256: ByteArray)

/** Windows compatibility facade over the platform-neutral v2 file channel. */
object WindowsFileTransfer {
    const val PORT = SharedFileTransfer.PORT

    fun probe(receiverAddress: String, operationId: UUID, key: ByteArray) =
        SharedFileTransfer.probe(NetworkEndpoint(receiverAddress), operationId, key)

    fun send(
        receiverAddress: String,
        itemId: UUID,
        source: File,
        key: ByteArray,
        progress: (Long, Long) -> Unit = { _, _ -> }
    ) = SharedFileTransfer.send(NetworkEndpoint(receiverAddress), itemId, source, key, progress)

    fun receive(
        destination: File,
        expectedItemId: UUID,
        key: ByteArray,
        probeKey: ByteArray? = null,
        progress: (Long, Long) -> Unit = { _, _ -> },
        onListening: (ServerSocket) -> Unit = {}
    ): WindowsReceivedFile = SharedFileTransfer.receive(
        destination,
        expectedItemId,
        key,
        probeKey,
        progress,
        onListening
    ).let { WindowsReceivedFile(it.file, it.byteLength, it.sha256) }
}
