package com.betterhv.transfer.core.v4.lan

import com.betterhv.transfer.proto.v4.Discovery
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class LanDiscoveryTest {
  @Test
  fun probeWaitsUntilDeadlineAfterFinalProbeWithoutBusySpin() = runBlocking {
    val unusedPort = DatagramSocket(0).use { it.localPort }
    val started = System.nanoTime()
    val peers = LanDiscovery.probe(
      timeoutMillis = 450,
      port = unusedPort,
      targets = listOf(InetAddress.getLoopbackAddress()),
    )
    val elapsedMillis = (System.nanoTime() - started) / 1_000_000
    assertTrue(peers.isEmpty())
    assertTrue("Expected probe to wait until ~450 ms, took $elapsedMillis ms", elapsedMillis >= 400)
    assertTrue("Expected probe to finish promptly after 450 ms, took $elapsedMillis ms", elapsedMillis < 2_500)
  }

  @Test
  fun probeReceivesLateReplyDuringPostProbeWaitWindow() = runBlocking {
    val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    try {
      val responderJob = launch(Dispatchers.IO) {
        val buf = ByteArray(LanDiscoveryCodec.MAX_DATAGRAM)
        val packet = DatagramPacket(buf, buf.size)
        socket.receive(packet)
        val (_, probe) = requireNotNull(LanDiscoveryCodec.decode(packet.data, packet.length))
        // Reply after all 3 probe repeats (at 0, 120, 240 ms) have already been sent.
        Thread.sleep(320)
        val reply = Discovery.newBuilder()
          .setMajor(4)
          .setIdentityHash(ByteString.copyFrom(LanDiscoveryCodec.identityHash("late-host")))
          .setTcpPort(39817)
          .setDeviceName("LateHost")
          .setProbeNonce(probe.probeNonce)
          .build()
        val encoded = LanDiscoveryCodec.encode(LanDiscoveryCodec.TYPE_ANNOUNCE, reply)
        socket.send(DatagramPacket(encoded, encoded.size, packet.socketAddress))
      }

      val peers = LanDiscovery.probe(
        timeoutMillis = 700,
        port = socket.localPort,
        targets = listOf(InetAddress.getLoopbackAddress()),
        until = { true },
      )
      responderJob.join()
      assertEquals(1, peers.size)
      assertEquals("LateHost", peers.single().discovery.deviceName)
    } finally {
      socket.close()
    }
  }
}
