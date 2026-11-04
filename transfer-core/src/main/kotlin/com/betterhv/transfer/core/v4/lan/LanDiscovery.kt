package com.betterhv.transfer.core.v4.lan

import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.proto.v4.Discovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** A NoteLink host that answered a LAN discovery probe. */
data class LanPeer(val host: String, val tcpPort: Int, val discovery: Discovery) {
  val identityHashHex: String get() = discovery.identityHash.toByteArray().joinToString("") { "%02x".format(it) }
  val pairing: Boolean get() = discovery.flags and LanDiscoveryCodec.FLAG_PAIRING != 0
}

/**
 * UDP datagram: `"NLD4" | u8 type | Discovery`. Discovery is only a hint; every connection it
 * leads to is authenticated by the pairing or session handshake.
 */
object LanDiscoveryCodec {
  const val PORT = 39818
  const val TYPE_PROBE = 1
  const val TYPE_ANNOUNCE = 2
  const val FLAG_PAIRING = 1
  const val FLAG_HAS_ITEMS = 2
  const val MAX_DATAGRAM = 512
  private val MAGIC = byteArrayOf(0x4e, 0x4c, 0x44, 0x34)

  fun encode(type: Int, discovery: Discovery): ByteArray = MAGIC + byteArrayOf(type.toByte()) + discovery.toByteArray()

  fun decode(bytes: ByteArray, length: Int = bytes.size): Pair<Int, Discovery>? {
    if (length < MAGIC.size + 1 || length > MAX_DATAGRAM) return null
    for (index in MAGIC.indices) if (bytes[index] != MAGIC[index]) return null
    val type = bytes[MAGIC.size].toInt() and 0xff
    val body = bytes.copyOfRange(MAGIC.size + 1, length)
    val discovery = runCatching { Discovery.parseFrom(body) }.getOrNull() ?: return null
    if (discovery.major != 4) return null
    return type to discovery
  }

  fun identityHash(deviceId: String): ByteArray = TransferCrypto.sha256(deviceId.encodeToByteArray()).copyOf(5)

  /** 255.255.255.255 plus every IPv4 interface's directed broadcast (some APs drop the former). */
  fun broadcastTargets(): List<InetAddress> = buildList {
    add(InetAddress.getByName("255.255.255.255"))
    runCatching {
      NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.interfaceAddresses }
        .filter { it.address is Inet4Address }
        .mapNotNull { it.broadcast }
        .forEach(::add)
    }
  }.distinct()
}

/**
 * Host side: answers probes on UDP [port] with [announcement]. Replies go straight to the prober,
 * so only the host needs the well-known port (and, on Windows, the inbound firewall rule).
 */
class LanAnnouncer(
  private val scope: CoroutineScope,
  private val announcement: () -> Discovery,
  private val port: Int = LanDiscoveryCodec.PORT,
  private val log: (String) -> Unit = {},
) : AutoCloseable {
  private var socket: DatagramSocket? = null
  private var job: Job? = null
  private var windowStart = 0L
  private var windowReplies = 0

  /** Binds the port; throws when another process holds it so the caller can report it. */
  @Synchronized
  fun start() {
    if (socket != null) return
    // Not inside apply {}: there `port` would resolve to DatagramSocket.getPort() (-1 when unconnected).
    val address = InetSocketAddress(port)
    val bound = DatagramSocket(null).apply {
      reuseAddress = true
      broadcast = true
      bind(address)
    }
    socket = bound
    job = scope.launch(Dispatchers.IO) {
      val buffer = ByteArray(LanDiscoveryCodec.MAX_DATAGRAM)
      while (isActive) {
        val packet = DatagramPacket(buffer, buffer.size)
        try {
          bound.receive(packet)
        } catch (_: IOException) {
          break
        }
        val (type, probe) = LanDiscoveryCodec.decode(packet.data, packet.length) ?: continue
        if (type != LanDiscoveryCodec.TYPE_PROBE || !allowReply()) continue
        val reply = runCatching { announcement() }.getOrNull() ?: continue
        val bytes = LanDiscoveryCodec.encode(
          LanDiscoveryCodec.TYPE_ANNOUNCE,
          reply.toBuilder().setProbeNonce(probe.probeNonce).build(),
        )
        runCatching { bound.send(DatagramPacket(bytes, bytes.size, packet.socketAddress)) }
          .onFailure { log("discovery reply to ${packet.socketAddress} failed: ${it.message}") }
      }
    }
  }

  /** At most 50 replies per second, so the announcer cannot be used to flood the LAN. */
  private fun allowReply(): Boolean {
    val now = System.currentTimeMillis()
    if (now - windowStart >= 1_000) {
      windowStart = now
      windowReplies = 0
    }
    return ++windowReplies <= MAX_REPLIES_PER_SECOND
  }

  @Synchronized
  override fun close() {
    runCatching { socket?.close() }
    socket = null
    job?.cancel()
    job = null
  }

  private companion object {
    const val MAX_REPLIES_PER_SECOND = 50
  }
}

/** Client side: broadcasts a probe and collects announces. */
object LanDiscovery {
  /**
   * Probes the LAN for up to [timeoutMillis] and returns every distinct host that answered.
   * Returns early once [until] accepts a peer. The probe is repeated a few times because Wi-Fi
   * power save commonly drops the first broadcast.
   */
  suspend fun probe(
    timeoutMillis: Long,
    port: Int = LanDiscoveryCodec.PORT,
    targets: List<InetAddress> = LanDiscoveryCodec.broadcastTargets(),
    until: (LanPeer) -> Boolean = { false },
  ): List<LanPeer> = withContext(Dispatchers.IO) {
    val found = LinkedHashMap<String, LanPeer>()
    val nonce = TransferCrypto.randomBytes(8)
    DatagramSocket().use { socket ->
      socket.broadcast = true
      val probe = LanDiscoveryCodec.encode(
        LanDiscoveryCodec.TYPE_PROBE,
        Discovery.newBuilder().setMajor(4).setProbeNonce(com.google.protobuf.ByteString.copyFrom(nonce)).build(),
      )
      val deadline = System.currentTimeMillis() + timeoutMillis
      val buffer = ByteArray(LanDiscoveryCodec.MAX_DATAGRAM)
      var nextProbe = 0L
      var probesSent = 0
      while (isActive) {
        val now = System.currentTimeMillis()
        if (now >= deadline) break
        if (probesSent < PROBE_REPEATS && now >= nextProbe) {
          targets.forEach { target -> runCatching { socket.send(DatagramPacket(probe, probe.size, target, port)) } }
          probesSent++
          nextProbe = now + PROBE_SPACING_MILLIS
        }
        socket.soTimeout = minOf(deadline - now, maxOf(1L, nextProbe - now), PROBE_SPACING_MILLIS)
          .toInt().coerceAtLeast(1)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
          socket.receive(packet)
        } catch (_: SocketTimeoutException) {
          continue
        }
        val (type, discovery) = LanDiscoveryCodec.decode(packet.data, packet.length) ?: continue
        if (type != LanDiscoveryCodec.TYPE_ANNOUNCE ||
          !discovery.probeNonce.toByteArray().contentEquals(nonce)) continue
        val host = packet.address.hostAddress ?: continue
        val peer = LanPeer(
          host, discovery.tcpPort.takeIf { it in 1..65_535 } ?: TcpAttachListener.DEFAULT_PORT, discovery
        )
        found["${peer.identityHashHex}@$host"] = peer
        if (until(peer)) break
      }
    }
    found.values.toList()
  }

  /** Re-probes every [intervalMillis] until [until] accepts a peer or [timeoutMillis] passes. */
  suspend fun find(
    timeoutMillis: Long,
    intervalMillis: Long = 1_000L,
    port: Int = LanDiscoveryCodec.PORT,
    targets: List<InetAddress> = LanDiscoveryCodec.broadcastTargets(),
    until: (LanPeer) -> Boolean,
  ): LanPeer? {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
      val window = minOf(intervalMillis, deadline - System.currentTimeMillis()).coerceAtLeast(1)
      probe(window, port, targets, until).firstOrNull(until)?.let { return it }
      delay(1)
    }
    return null
  }

  private const val PROBE_REPEATS = 3
  private const val PROBE_SPACING_MILLIS = 120L
}
