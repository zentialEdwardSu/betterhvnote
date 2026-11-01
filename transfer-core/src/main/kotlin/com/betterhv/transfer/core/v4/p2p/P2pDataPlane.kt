package com.betterhv.transfer.core.v4.p2p

import com.betterhv.transfer.proto.v4.ErrorCode
import com.betterhv.transfer.proto.v4.Frame
import com.betterhv.transfer.proto.v4.StreamReset
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class GroupLostException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Shared P2P socket setup: fixed client→group-owner direction, `TCP_NODELAY`, 1 MiB kernel buffers,
 * 64 KiB application chunks and 1 MiB credit. A socket failure surfaces as [GroupLostException],
 * which callers translate into `StreamReset(GROUP_LOST)` and can resume from the last offset.
 */
object P2pDataPlane {
  const val CHUNK_BYTES = 64 * 1024
  const val CREDIT_BYTES = 1024 * 1024
  const val KERNEL_BUFFER_BYTES = 1024 * 1024
  const val CONNECT_TIMEOUT_MILLIS = 5_000
  const val ACCEPT_TIMEOUT_MILLIS = 5_000

  fun configure(socket: Socket) {
    socket.tcpNoDelay = true
    socket.sendBufferSize = KERNEL_BUFFER_BYTES
    socket.receiveBufferSize = KERNEL_BUFFER_BYTES
  }

  fun connect(host: String, port: Int, timeoutMillis: Int = CONNECT_TIMEOUT_MILLIS): Socket {
    val socket = Socket()
    try {
      configure(socket)
      socket.connect(InetSocketAddress(host, port), timeoutMillis)
      return socket
    } catch (error: IOException) {
      runCatching { socket.close() }
      throw GroupLostException("P2P connect to $host:$port failed", error)
    }
  }

  fun accept(server: ServerSocket, timeoutMillis: Int = ACCEPT_TIMEOUT_MILLIS): Socket? {
    server.soTimeout = timeoutMillis
    return try {
      server.accept()?.also(::configure)
    } catch (timeout: java.net.SocketTimeoutException) {
      null
    }
  }

  /** Copies exactly [contentBytes] using 64 KiB chunks; the reader never blocks on a huge buffer. */
  fun send(input: InputStream, output: OutputStream, contentBytes: Long): Long {
    try {
      val buffer = ByteArray(CHUNK_BYTES)
      var remaining = contentBytes
      var sent = 0L
      while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) throw GroupLostException("Payload ended after $sent of $contentBytes bytes")
        output.write(buffer, 0, read)
        sent += read
        remaining -= read
      }
      output.flush()
      return sent
    } catch (error: IOException) {
      throw GroupLostException("P2P send failed after ${contentBytes} bytes", error)
    }
  }

  fun receive(input: InputStream, output: OutputStream, contentBytes: Long): Long {
    try {
      val buffer = ByteArray(CHUNK_BYTES)
      var remaining = contentBytes
      var received = 0L
      while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) throw GroupLostException("Stream ended after $received of $contentBytes bytes")
        output.write(buffer, 0, read)
        received += read
        remaining -= read
      }
      return received
    } catch (error: IOException) {
      throw GroupLostException("P2P receive failed after ${contentBytes} bytes", error)
    }
  }

  fun groupLostFrame(reason: String = "group-lost"): Frame = Frame.newBuilder()
    .setStreamReset(
      StreamReset.newBuilder()
        .setCode(ErrorCode.ERROR_CONNECTION_TIMEOUT)
        .setReason(reason)
        .setRetryable(true),
    )
    .build()
}
