package com.betterhv.transfer.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.AEADBadTagException

data class SharedReceivedFile(val file: File, val byteLength: Long, val sha256: ByteArray)

class TransferChannelException(val failure: TransferFailure, cause: Throwable? = null) :
    IllegalStateException(failure.message, cause)

object SharedFileTransfer {
    const val PORT = 39817
    const val CHUNK_SIZE = 512 * 1024
    private const val SOCKET_BUFFER_BYTES = 1024 * 1024
    private const val CONNECT_TIMEOUT_MILLIS = 20_000
    private const val IO_TIMEOUT_MILLIS = 30_000
    private const val SEND_PREFIX = 0x53454E44
    private const val RECEIVE_PREFIX = 0x52454356
    private val PROBE_PAYLOAD = "notelink-probe-v2".encodeToByteArray()

    fun probe(endpoint: NetworkEndpoint, operationId: UUID, key: ByteArray) = classified(TransferMode.LAN) {
        connect(endpoint).use { socket ->
            val channel = SecureFrameChannel(key, SEND_PREFIX, RECEIVE_PREFIX)
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            ProtocolCodec.write(output, channel.seal(MessageType.PROBE, operationId, PROBE_PAYLOAD))
            val response = ProtocolCodec.read(input)
            require(response.type == MessageType.PROBE_ACK && response.requestId == operationId) {
                "Invalid LAN probe response"
            }
            require(channel.open(response).contentEquals(PROBE_PAYLOAD)) { "LAN probe authentication failed" }
        }
    }

    fun send(
        endpoint: NetworkEndpoint,
        itemId: UUID,
        source: File,
        key: ByteArray,
        progress: (Long, Long) -> Unit = { _, _ -> }
    ) = classified(null) {
        require(source.isFile) { "Transfer source does not exist" }
        connect(endpoint).use { socket ->
            val channel = SecureFrameChannel(key, SEND_PREFIX, RECEIVE_PREFIX)
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            ProtocolCodec.write(output, channel.seal(MessageType.HELLO, itemId, longBytes(source.length())))
            val requested = ProtocolCodec.read(input)
            require(requested.type == MessageType.GET && requested.requestId == itemId)
            val offset = bytesLong(channel.open(requested)).coerceIn(0, source.length())
            progress(offset, source.length())
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

    /**
     * Listens once for a transfer while allowing any number of authenticated probes first.
     * Keeping the same listener open removes the race between a successful probe and file connect.
     */
    fun receive(
        destination: File,
        expectedItemId: UUID,
        fileKey: ByteArray,
        probeKey: ByteArray? = null,
        progress: (Long, Long) -> Unit = { _, _ -> },
        onListening: (ServerSocket) -> Unit = {}
    ): SharedReceivedFile = classified(null) {
        destination.parentFile?.mkdirs()
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.receiveBufferSize = SOCKET_BUFFER_BYTES
            server.bind(InetSocketAddress(PORT))
            server.soTimeout = IO_TIMEOUT_MILLIS
            onListening(server)
            while (true) {
                server.accept().use { socket ->
                    configure(socket)
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    val first = ProtocolCodec.read(input)
                    if (first.type == MessageType.PROBE) {
                        val key = requireNotNull(probeKey) { "LAN probe is not enabled for this receiver" }
                        val channel = SecureFrameChannel(key, RECEIVE_PREFIX, SEND_PREFIX)
                        val payload = channel.open(first)
                        require(first.requestId == expectedItemId && payload.contentEquals(PROBE_PAYLOAD)) {
                            "Invalid LAN probe"
                        }
                        ProtocolCodec.write(output, channel.seal(MessageType.PROBE_ACK, expectedItemId, payload))
                    } else {
                        return@classified receiveFile(socket, input, output, first, destination, expectedItemId, fileKey, progress)
                    }
                }
            }
            error("unreachable")
        }
    }

    private fun receiveFile(
        socket: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        hello: ProtocolFrame,
        destination: File,
        expectedItemId: UUID,
        key: ByteArray,
        progress: (Long, Long) -> Unit
    ): SharedReceivedFile {
        val channel = SecureFrameChannel(key, RECEIVE_PREFIX, SEND_PREFIX)
        require(hello.type == MessageType.HELLO && hello.requestId == expectedItemId)
        val total = bytesLong(channel.open(hello))
        require(total >= 0) { "Invalid transfer size" }
        val offset = destination.takeIf(File::isFile)?.length()?.coerceAtMost(total) ?: 0L
        ProtocolCodec.write(output, channel.seal(MessageType.GET, expectedItemId, longBytes(offset)))
        progress(offset, total)
        FileOutputStream(destination, offset > 0).use { file ->
            var received = offset
            while (!socket.isClosed) {
                val frame = ProtocolCodec.read(input)
                require(frame.requestId == expectedItemId)
                when (frame.type) {
                    MessageType.CHUNK -> {
                        val bytes = channel.open(frame)
                        received += bytes.size
                        require(received <= total) { "Received more bytes than declared" }
                        file.write(bytes)
                        progress(received, total)
                    }
                    MessageType.EOF -> {
                        val expectedHash = channel.open(frame)
                        file.fd.sync()
                        require(received == total) { "Incomplete transfer: $received/$total" }
                        val actualHash = sha256(destination)
                        require(actualHash.contentEquals(expectedHash)) { "Transferred file hash mismatch" }
                        ProtocolCodec.write(output, channel.seal(MessageType.ACCEPT, expectedItemId, actualHash))
                        return SharedReceivedFile(destination, received, actualHash)
                    }
                    else -> error("Unexpected frame ${frame.type}")
                }
            }
        }
        error("Transfer socket closed")
    }

    private fun connect(endpoint: NetworkEndpoint): Socket = Socket().apply {
        try {
            val safe = endpoint.validated()
            sendBufferSize = SOCKET_BUFFER_BYTES
            receiveBufferSize = SOCKET_BUFFER_BYTES
            connect(InetSocketAddress(safe.host, safe.port), CONNECT_TIMEOUT_MILLIS)
            soTimeout = IO_TIMEOUT_MILLIS
        } catch (error: Throwable) {
            runCatching { close() }
            throw error
        }
    }

    private fun configure(socket: Socket) {
        socket.sendBufferSize = SOCKET_BUFFER_BYTES
        socket.receiveBufferSize = SOCKET_BUFFER_BYTES
        socket.soTimeout = IO_TIMEOUT_MILLIS
    }

    private inline fun <T> classified(mode: TransferMode?, block: () -> T): T = try {
        block()
    } catch (error: TransferChannelException) {
        throw error
    } catch (error: Throwable) {
        val code = when (error) {
            is SocketTimeoutException -> TransferErrorCode.CONNECTION_TIMEOUT
            is ConnectException -> TransferErrorCode.CONNECTION_REFUSED
            is NoRouteToHostException -> TransferErrorCode.UNREACHABLE
            is AEADBadTagException -> TransferErrorCode.AUTHENTICATION_FAILED
            is IllegalArgumentException -> if (error.message.orEmpty().contains("hash", true)) {
                TransferErrorCode.INTEGRITY_FAILED
            } else TransferErrorCode.AUTHENTICATION_FAILED
            else -> TransferErrorCode.INTERNAL
        }
        throw TransferChannelException(
            TransferFailure(code, error.message ?: code.name, code !in setOf(
                TransferErrorCode.AUTHENTICATION_FAILED, TransferErrorCode.INVALID_ENDPOINT
            ), mode),
            error
        )
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
    private fun bytesLong(value: ByteArray): Long {
        require(value.size == Long.SIZE_BYTES) { "Invalid long payload" }
        return java.nio.ByteBuffer.wrap(value).long
    }
}
