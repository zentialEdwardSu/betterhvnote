package com.betterhv.transfer.windows

import com.betterhv.transfer.core.MessageType
import com.betterhv.transfer.core.ProtocolCodec
import com.betterhv.transfer.core.SecureFrameChannel
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

data class WindowsReceivedFile(val file: File, val byteLength: Long, val sha256: ByteArray)

/** Shared file protocol implementation on the JVM; the native layer owns only the Wi-Fi Direct group. */
object WindowsFileTransfer {
    const val PORT = 39817
    private const val CHUNK_SIZE = 512 * 1024
    private const val SOCKET_BUFFER_BYTES = 1024 * 1024
    private const val SEND_PREFIX = 0x53454E44
    private const val RECEIVE_PREFIX = 0x52454356

    fun send(
        receiverAddress: String,
        itemId: UUID,
        source: File,
        key: ByteArray,
        progress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        Socket().use { socket ->
            socket.sendBufferSize = SOCKET_BUFFER_BYTES
            socket.receiveBufferSize = SOCKET_BUFFER_BYTES
            socket.connect(InetSocketAddress(receiverAddress, PORT), 20_000)
            socket.soTimeout = 30_000
            val channel = SecureFrameChannel(key, SEND_PREFIX, RECEIVE_PREFIX)
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            ProtocolCodec.write(output, channel.seal(MessageType.HELLO, itemId, longBytes(source.length())))
            val requested = ProtocolCodec.read(input)
            require(requested.type == MessageType.GET && requested.requestId == itemId)
            val offset = bytesLong(channel.open(requested)).coerceIn(0, source.length())
            FileInputStream(source).use { file ->
                file.channel.position(offset)
                var sent = offset
                val buffer = ByteArray(CHUNK_SIZE)
                while (true) {
                    val count = file.read(buffer)
                    if (count < 0) break
                    ProtocolCodec.write(output, channel.seal(MessageType.CHUNK, itemId, buffer.copyOf(count)))
                    sent += count
                    progress(sent, source.length())
                }
            }
            val hash = sha256(source)
            ProtocolCodec.write(output, channel.seal(MessageType.EOF, itemId, hash))
            val acknowledgement = ProtocolCodec.read(input)
            require(acknowledgement.type == MessageType.ACCEPT && acknowledgement.requestId == itemId)
            require(channel.open(acknowledgement).contentEquals(hash)) { "Transfer acknowledgement hash mismatch" }
        }
    }

    fun receive(
        destination: File,
        expectedItemId: UUID,
        key: ByteArray,
        progress: (Long, Long) -> Unit = { _, _ -> },
        onListening: (ServerSocket) -> Unit = {}
    ): WindowsReceivedFile {
        destination.parentFile?.mkdirs()
        ServerSocket(PORT).use { server ->
            server.reuseAddress = true
            server.receiveBufferSize = SOCKET_BUFFER_BYTES
            server.soTimeout = 30_000
            onListening(server)
            server.accept().use { socket ->
                socket.sendBufferSize = SOCKET_BUFFER_BYTES
                socket.receiveBufferSize = SOCKET_BUFFER_BYTES
                socket.soTimeout = 30_000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                val channel = SecureFrameChannel(key, RECEIVE_PREFIX, SEND_PREFIX)
                val hello = ProtocolCodec.read(input)
                require(hello.type == MessageType.HELLO && hello.requestId == expectedItemId)
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
                                file.write(bytes)
                                received += bytes.size
                                require(received <= total) { "Received more bytes than declared" }
                                progress(received, total)
                            }
                            MessageType.EOF -> {
                                val expectedHash = channel.open(frame)
                                file.fd.sync()
                                require(received == total) { "Incomplete transfer: $received/$total" }
                                val actualHash = sha256(destination)
                                require(actualHash.contentEquals(expectedHash)) { "Transferred file hash mismatch" }
                                ProtocolCodec.write(output, channel.seal(MessageType.ACCEPT, expectedItemId, actualHash))
                                return WindowsReceivedFile(destination, received, actualHash)
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
}
