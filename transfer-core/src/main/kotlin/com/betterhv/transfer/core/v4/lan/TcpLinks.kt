package com.betterhv.transfer.core.v4.lan

import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.core.v4.session.LinkCipher
import com.betterhv.transfer.core.v4.session.PairingKeyStore
import com.betterhv.transfer.core.v4.session.PeerConnection
import com.betterhv.transfer.core.v4.session.ProtocolSession
import com.betterhv.transfer.core.v4.session.RecordLink
import com.betterhv.transfer.proto.v4.SessionTicket
import com.google.protobuf.ByteString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Length-prefixed v4 records over one TCP connection (LAN or Wi-Fi Direct). A big-endian `u32`
 * length precedes every record; one record is written and flushed per [send].
 */
class TcpRecordLink(
  private val socket: Socket,
  override val label: String,
  input: DataInputStream? = null,
) : RecordLink {
  private val input = input ?: DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_BYTES))
  private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES))
  private val writeLock = Mutex()

  override val maxRecordBytes: Int = MAX_RECORD_BYTES

  val remoteAddress: String get() = socket.inetAddress?.hostAddress ?: "unknown"

  val remotePort: Int get() = socket.port

  override suspend fun send(record: ByteArray) {
    require(record.size in 1..MAX_RECORD_BYTES) { "Invalid record length ${record.size}" }
    writeLock.withLock {
      withContext(Dispatchers.IO) {
        output.writeInt(record.size)
        output.write(record)
        output.flush()
      }
    }
  }

  override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
    try {
      val length = input.readInt()
      if (length !in 1..MAX_RECORD_BYTES) throw IOException("Invalid record length $length")
      ByteArray(length).also(input::readFully)
    } catch (_: EOFException) {
      null
    } catch (error: SocketException) {
      // Our own close() surfaces as "Socket closed"; anything else (reset, abort) is a fault
      // the session should report.
      if (closed) null else throw error
    }
  }

  @Volatile private var closed = false

  override fun close() {
    closed = true
    runCatching { socket.close() }
  }

  companion object {
    /** Matches the record codec's 64 KiB message limit plus the record header. */
    const val MAX_RECORD_BYTES = 64 * 1024 + 64
    const val BUFFER_BYTES = 256 * 1024
    const val SOCKET_BUFFER_BYTES = 1024 * 1024

    fun configure(socket: Socket) {
      socket.tcpNoDelay = true
      socket.sendBufferSize = SOCKET_BUFFER_BYTES
      socket.receiveBufferSize = SOCKET_BUFFER_BYTES
      socket.keepAlive = true
    }
  }
}

/**
 * Attaches a TCP connection to an established session.
 *
 * The dialer sends a [SessionTicket] sealed under the session's ticket key with a fresh
 * connection nonce; the listener answers with a random 16-byte salt. Both salts derive the link
 * key, so a replayed attach can never produce a usable link. No second handshake is needed.
 */
object TcpAttach {
  const val SALT_BYTES = 16
  private const val TICKET_TTL_MILLIS = 10 * 60_000L

  /** Dialer side; throws on any connect or attach failure. */
  suspend fun dial(
    host: String,
    port: Int,
    label: String,
    session: ProtocolSession,
    pairingKey: ByteArray,
    localDeviceId: String,
    peerDeviceId: String,
    connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    clock: () -> Long = System::currentTimeMillis,
  ): Pair<TcpRecordLink, LinkCipher> = withContext(Dispatchers.IO) {
    val socket = Socket()
    try {
      TcpRecordLink.configure(socket)
      socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
      socket.soTimeout = ATTACH_TIMEOUT_MILLIS
      val nonce = TransferCrypto.randomBytes(SALT_BYTES)
      val ticket = SessionTicket.newBuilder()
        .setVersion(SessionTicketCodec.VERSION)
        .setSessionId(ByteString.copyFrom(SessionKeys.sessionIdBytes(session.sessionId)))
        .setListenerDeviceId(peerDeviceId)
        .setPeerDeviceId(localDeviceId)
        .setExpiresAtMs(clock() + TICKET_TTL_MILLIS)
        .setListenerPort(port)
        .setConnectionNonce(ByteString.copyFrom(nonce))
        .build()
      val output = socket.getOutputStream()
      output.write(SessionTicketCodec.seal(pairingKey, ticket))
      output.flush()
      val input = DataInputStream(BufferedInputStream(socket.getInputStream(), TcpRecordLink.BUFFER_BYTES))
      val salt = ByteArray(SALT_BYTES).also(input::readFully)
      socket.soTimeout = 0
      val link = TcpRecordLink(socket, label, input)
      link to session.linkCipher(PeerConnection.LABEL_TCP, nonce + salt, link.maxRecordBytes)
    } catch (error: Throwable) {
      runCatching { socket.close() }
      throw error
    }
  }

  /**
   * Opens a control connection: the same pairing and handshake records BLE carries, framed as
   * TCP records. It lets a Note reach a NoteLink that has no working Bluetooth.
   */
  suspend fun dialControl(
    host: String,
    port: Int,
    label: String = PeerConnection.LABEL_LAN,
    connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
  ): TcpRecordLink = withContext(Dispatchers.IO) {
    val socket = Socket()
    try {
      TcpRecordLink.configure(socket)
      socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
      socket.getOutputStream().apply {
        write(CONTROL_MAGIC)
        flush()
      }
      TcpRecordLink(socket, label)
    } catch (error: Throwable) {
      runCatching { socket.close() }
      throw error
    }
  }

  /** First bytes of a control connection; a ticket envelope starts with its version byte (1). */
  internal val CONTROL_MAGIC = byteArrayOf(0x4e, 0x4c, 0x43, 0x34)

  private const val CONNECT_TIMEOUT_MILLIS = 1_500
  internal const val ATTACH_TIMEOUT_MILLIS = 3_000
}

/**
 * Single-port listener for [TcpAttach]. Each accepted connection is authenticated before it is
 * handed to [onLink]; failures close the socket without telling the dialer which check failed.
 */
class TcpAttachListener(
  private val scope: CoroutineScope,
  pairings: PairingKeyStore,
  private val listenerDeviceId: String,
  private val session: (sessionId: Long) -> ProtocolSession?,
  private val onLink: (ProtocolSession, TcpRecordLink, LinkCipher) -> Unit,
  private val port: Int = DEFAULT_PORT,
  private val labelFor: (Socket) -> String = { "lan" },
  clock: () -> Long = System::currentTimeMillis,
  /** Receives control connections (pairing + handshake over TCP); null rejects them. */
  private val onControl: ((TcpRecordLink) -> Unit)? = null,
) : AutoCloseable {
  private val authenticator = TicketAuthenticator(
    pairings = pairings,
    clock = clock,
    sessionActive = { id -> session(SessionKeys.sessionIdLong(id)) != null },
  )
  private var server: ServerSocket? = null
  private var job: Job? = null

  val boundPort: Int get() = server?.localPort ?: -1

  /** Binds the port; throws when it is taken so the caller can report it instead of looping. */
  @Synchronized
  fun start() {
    check(server == null) { "Listener already started" }
    val socket = ServerSocket()
    try {
      socket.reuseAddress = true
      socket.bind(InetSocketAddress(port), BACKLOG)
    } catch (error: IOException) {
      runCatching { socket.close() }
      throw error
    }
    server = socket
    job = scope.launch(Dispatchers.IO) {
      while (isActive) {
        val accepted = try {
          socket.accept()
        } catch (_: IOException) {
          break
        }
        launch(Dispatchers.IO) { handle(accepted) }
      }
    }
  }

  private fun handle(socket: Socket) {
    try {
      TcpRecordLink.configure(socket)
      socket.soTimeout = TcpAttach.ATTACH_TIMEOUT_MILLIS
      val input = DataInputStream(BufferedInputStream(socket.getInputStream(), TcpRecordLink.BUFFER_BYTES))
      input.mark(TcpAttach.CONTROL_MAGIC.size)
      if (input.readUnsignedByte() == (TcpAttach.CONTROL_MAGIC[0].toInt() and 0xff)) {
        val rest = ByteArray(TcpAttach.CONTROL_MAGIC.size - 1).also(input::readFully)
        val handler = onControl
        if (handler == null ||
          !rest.contentEquals(TcpAttach.CONTROL_MAGIC.copyOfRange(1, TcpAttach.CONTROL_MAGIC.size))) {
          return socket.close()
        }
        socket.soTimeout = 0
        handler(TcpRecordLink(socket, labelFor(socket), input))
        return
      }
      input.reset()
      val envelope = SessionTicketCodec.readEnvelope(input)
      val ticket = authenticator.authenticate(envelope, listenerDeviceId) ?: return socket.close()
      val active = session(SessionKeys.sessionIdLong(ticket.sessionId.toByteArray())) ?: return socket.close()
      if (active.peerDeviceId != ticket.peerDeviceId) return socket.close()
      val salt = TransferCrypto.randomBytes(TcpAttach.SALT_BYTES)
      socket.getOutputStream().apply {
        write(salt)
        flush()
      }
      socket.soTimeout = 0
      val label = labelFor(socket)
      val link = TcpRecordLink(socket, label, input)
      val salts = ticket.connectionNonce.toByteArray() + salt
      onLink(active, link, active.linkCipher(PeerConnection.LABEL_TCP, salts, link.maxRecordBytes))
    } catch (cancelled: CancellationException) {
      runCatching { socket.close() }
      throw cancelled
    } catch (_: Throwable) {
      runCatching { socket.close() }
    }
  }

  @Synchronized
  override fun close() {
    runCatching { server?.close() }
    server = null
    job?.cancel()
    job = null
    authenticator.reset()
  }

  companion object {
    const val DEFAULT_PORT = 39817
    private const val BACKLOG = 16
  }
}
