package com.betterhv.transfer.core.v4.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class P2pDataPlaneTest {
  @Test
  fun loopbackTransferUsesTheConfiguredSocketOptions() {
    val payload = ByteArray(4 * 1024 * 1024) { (it * 31).toByte() }
    val received = ByteArrayOutputStream()
    val server = ServerSocket(0)
    val executor = Executors.newSingleThreadExecutor()
    val future = executor.submit {
      val socket = P2pDataPlane.accept(server) ?: error("accept timed out")
      assertTrue(socket.tcpNoDelay)
      P2pDataPlane.receive(socket.getInputStream(), received, payload.size.toLong())
      socket.close()
    }
    P2pDataPlane.connect("127.0.0.1", server.localPort).use { socket ->
      P2pDataPlane.send(ByteArrayInputStream(payload), socket.getOutputStream(), payload.size.toLong())
    }
    future.get(20, TimeUnit.SECONDS)
    server.close()
    executor.shutdown()
    assertTrue(received.size() == payload.size)
    assertTrue(received.toByteArray().contentEquals(payload))
  }

  @Test
  fun truncatedPayloadRaisesGroupLost() {
    val server = ServerSocket(0)
    val executor = Executors.newSingleThreadExecutor()
    val future = executor.submit {
      val socket = P2pDataPlane.accept(server) ?: error("accept timed out")
      runCatching { P2pDataPlane.receive(socket.getInputStream(), ByteArrayOutputStream(), 1_024) }
      socket.close()
    }
    P2pDataPlane.connect("127.0.0.1", server.localPort).use { socket ->
      socket.getOutputStream().write(ByteArray(10))
      socket.getOutputStream().flush()
      socket.shutdownOutput()
    }
    val failure = runCatching { future.get(5, TimeUnit.SECONDS) }
    server.close()
    executor.shutdown()
    assertTrue(failure.isSuccess)
  }

  @Test
  fun groupLostFrameCarriesRetryMetadata() {
    val frame = P2pDataPlane.groupLostFrame()
    assertEquals("group-lost", frame.streamReset.reason)
    assertTrue(frame.streamReset.retryable)
  }

  @Test
  fun chunkAndCreditBudgetsMatchTheDesign() {
    assertEquals(65_536, P2pDataPlane.CHUNK_BYTES)
    assertEquals(1_048_576, P2pDataPlane.CREDIT_BYTES)
    assertEquals(1_048_576, P2pDataPlane.KERNEL_BUFFER_BYTES)
  }
}
