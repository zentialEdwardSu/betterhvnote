package com.betterhv.transfer.android

import com.betterhv.transfer.core.MessageType
import com.betterhv.transfer.core.ProtocolCodec
import com.betterhv.transfer.core.ProtocolFrame
import com.betterhv.transfer.core.ReplayWindow
import com.betterhv.transfer.core.SecureFrameChannel
import com.betterhv.transfer.core.TransferCrypto
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

data class ReceivedFile(val file: File, val byteLength: Long, val sha256: ByteArray)

object EncryptedFileTransfer {
    private const val CHUNK_SIZE = 256 * 1024

    fun send(
        ownerAddress: String,
        itemId: UUID,
        source: File,
        key: ByteArray,
        offset: Long = 0L,
        progress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        require(offset in 0..source.length())
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ownerAddress, BleConstants.SOCKET_PORT), 20_000)
            socket.soTimeout = 30_000
            val channel = SecureFrameChannel(key, SEND_PREFIX, RECEIVE_PREFIX)
            val header = DataOutputStream(socket.getOutputStream())
            ProtocolCodec.write(header, channel.seal(MessageType.HELLO, itemId, longBytes(source.length())))
            val request = ProtocolCodec.read(DataInputStream(socket.getInputStream()))
            val requestedOffset = bytesLong(channel.open(request)).coerceIn(0L, source.length())
            FileInputStream(source).use { input ->
                input.channel.position(requestedOffset)
                var sent = requestedOffset
                val buffer = ByteArray(CHUNK_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    ProtocolCodec.write(header, channel.seal(MessageType.CHUNK, itemId, buffer.copyOf(count)))
                    sent += count
                    progress(sent, source.length())
                }
            }
            val sourceHash = sha256(source)
            ProtocolCodec.write(header, channel.seal(MessageType.EOF, itemId, sourceHash))
            val acknowledgement = ProtocolCodec.read(DataInputStream(socket.getInputStream()))
            require(acknowledgement.requestId == itemId && acknowledgement.type == MessageType.ACCEPT) {
                "Missing transfer acknowledgement"
            }
            require(channel.open(acknowledgement).contentEquals(sourceHash)) {
                "Transfer acknowledgement hash mismatch"
            }
        }
    }

    fun receive(
        destination: File,
        expectedItemId: UUID,
        key: ByteArray,
        progress: (Long, Long) -> Unit = { _, _ -> }
    ): ReceivedFile {
        destination.parentFile?.mkdirs()
        ServerSocket(BleConstants.SOCKET_PORT).use { server ->
            server.soTimeout = 30_000
            server.accept().use { socket ->
                socket.soTimeout = 30_000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                val channel = SecureFrameChannel(key, RECEIVE_PREFIX, SEND_PREFIX)
                val hello = ProtocolCodec.read(input)
                require(hello.requestId == expectedItemId && hello.type == MessageType.HELLO)
                val total = bytesLong(channel.open(hello))
                val offset = destination.takeIf(File::isFile)?.length()?.coerceAtMost(total) ?: 0L
                ProtocolCodec.write(output, channel.seal(MessageType.GET, expectedItemId, longBytes(offset)))
                FileOutputStream(destination, offset > 0).use { file ->
                    var received = offset
                    while (true) {
                        val frame = ProtocolCodec.read(input)
                        require(frame.requestId == expectedItemId)
                        when (frame.type) {
                            MessageType.CHUNK -> {
                                val bytes = channel.open(frame)
                                file.write(bytes); received += bytes.size
                                require(received <= total) { "Received more bytes than declared" }
                                progress(received, total)
                            }
                            MessageType.EOF -> {
                                val expectedHash = channel.open(frame)
                                file.fd.sync()
                                require(received == total) { "Incomplete transfer: $received/$total" }
                                val actualHash = sha256(destination)
                                require(actualHash.contentEquals(expectedHash)) { "Transferred file hash mismatch" }
                                ProtocolCodec.write(
                                    output,
                                    channel.seal(MessageType.ACCEPT, expectedItemId, actualHash)
                                )
                                return ReceivedFile(destination, received, actualHash)
                            }
                            else -> error("Unexpected frame ${frame.type}")
                        }
                    }
                }
            }
        }
    }

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").run {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                update(buffer, 0, count)
            }
        }
        digest()
    }

    private fun longBytes(value: Long) = java.nio.ByteBuffer.allocate(8).putLong(value).array()
    private fun bytesLong(value: ByteArray) = java.nio.ByteBuffer.wrap(value).long
    private const val SEND_PREFIX = 0x53454E44
    private const val RECEIVE_PREFIX = 0x52454356
}
