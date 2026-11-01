package com.betterhv.transfer.core.v4.crypto

import com.betterhv.transfer.core.TransferCrypto
import java.nio.ByteBuffer

/** Direction of a session key. Each direction has an independent key and nonce space. */
enum class Direction { C2S, S2C }

/**
 * Per-session key schedule. All keys are derived from the pairing PSK and the two handshake
 * nonces; the pairing key itself never leaves the device.
 *
 * The labels are part of the wire contract: changing one is a protocol break.
 */
class SessionKeys private constructor(
  val keyId: Int,
  val helloKey: ByteArray,
  val replyKey: ByteArray,
  private val c2sKey: ByteArray,
  private val s2cKey: ByteArray,
  val ticketKey: ByteArray,
  private val sessionId: ByteArray,
) {
  init {
    require(keyId in 0..0xffff)
    require(sessionId.size == SESSION_ID_BYTES)
    listOf(helloKey, replyKey, c2sKey, s2cKey, ticketKey).forEach {
      require(it.size == KEY_BYTES) { "Session keys must be $KEY_BYTES bytes" }
    }
  }

  fun directionKey(direction: Direction): ByteArray = when (direction) {
    Direction.C2S -> c2sKey
    Direction.S2C -> s2cKey
  }

  /**
   * Key for one physical link of the session. Each link (BLE, LAN connection, Wi-Fi Direct
   * connection) numbers its own messages from 1, so it needs its own key: [salt] is the LAN
   * connection nonce and makes every TCP connection's key unique within the session.
   */
  fun linkKey(direction: Direction, label: String, salt: ByteArray = ByteArray(0)): ByteArray {
    require(label.isNotBlank()) { "Link label is blank" }
    return TransferCrypto.hkdfSha256(
      directionKey(direction),
      salt,
      LINK_INFO + label.encodeToByteArray(),
      KEY_BYTES,
    )
  }

  fun streamKey(direction: Direction, streamId: Int): ByteArray {
    require(streamId != 0) { "Stream 0 is the session control stream" }
    return TransferCrypto.hkdfSha256(
      directionKey(direction),
      streamSalt(streamId),
      STREAM_INFO,
      KEY_BYTES,
    )
  }

  companion object {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 16
    const val SESSION_ID_BYTES = 8
    private val HELLO_INFO = "notelink/v4/hello".encodeToByteArray()
    private val REPLY_INFO = "notelink/v4/reply".encodeToByteArray()
    private val C2S_INFO = "notelink/v4/c2s".encodeToByteArray()
    private val S2C_INFO = "notelink/v4/s2c".encodeToByteArray()
    private val TICKET_INFO = "notelink/v4/ticket".encodeToByteArray()
    private val KEY_ID_INFO = "notelink/v4/key-id".encodeToByteArray()
    private val STREAM_INFO = "notelink/v4/stream".encodeToByteArray()
    private val LINK_INFO = "notelink/v4/link/".encodeToByteArray()

    /** Stable, non-secret hint that lets the server select one of its paired keys in O(1). */
    fun keyId(pairingKey: ByteArray): Int {
      require(pairingKey.isNotEmpty()) { "Pairing key is empty" }
      val digest = TransferCrypto.hmacSha256(pairingKey, KEY_ID_INFO)
      return ((digest[0].toInt() and 0xff) shl 8) or (digest[1].toInt() and 0xff)
    }

    fun derive(
      pairingKey: ByteArray,
      keyId: Int,
      clientNonce: ByteArray,
      serverNonce: ByteArray,
      sessionId: ByteArray,
    ): SessionKeys {
      require(pairingKey.size == KEY_BYTES) { "Pairing key must be $KEY_BYTES bytes" }
      require(clientNonce.size == NONCE_BYTES) { "Client nonce must be $NONCE_BYTES bytes" }
      require(serverNonce.size == NONCE_BYTES) { "Server nonce must be $NONCE_BYTES bytes" }
      require(sessionId.size == SESSION_ID_BYTES) { "Session id must be $SESSION_ID_BYTES bytes" }
      val helloKey = helloKey(pairingKey, keyId, clientNonce)
      val replyKey = replyKey(pairingKey, clientNonce, serverNonce)
      val c2sKey = TransferCrypto.hkdfSha256(
        pairingKey,
        clientNonce + serverNonce,
        C2S_INFO + sessionId,
        KEY_BYTES,
      )
      val s2cKey = TransferCrypto.hkdfSha256(
        pairingKey,
        clientNonce + serverNonce,
        S2C_INFO + sessionId,
        KEY_BYTES,
      )
      val ticketKey = ticketKey(pairingKey, sessionId)
      return SessionKeys(keyId, helloKey, replyKey, c2sKey, s2cKey, ticketKey, sessionId.copyOf())
    }

    /** Key used to seal the stateless LAN ticket; bound to one session. */
    fun ticketKey(pairingKey: ByteArray, sessionId: ByteArray): ByteArray {
      require(pairingKey.size == KEY_BYTES) { "Pairing key must be $KEY_BYTES bytes" }
      require(sessionId.size == SESSION_ID_BYTES) { "Session id must be $SESSION_ID_BYTES bytes" }
      return TransferCrypto.hkdfSha256(pairingKey, sessionId, TICKET_INFO, KEY_BYTES)
    }

    /**
     * Key for the first flight. It may only depend on values that are readable before decryption:
     * the clear handshake nonce and the key id. The timestamp travels inside the ciphertext.
     */
    fun helloKey(pairingKey: ByteArray, keyId: Int, clientNonce: ByteArray): ByteArray {
      require(pairingKey.size == KEY_BYTES) { "Pairing key must be $KEY_BYTES bytes" }
      require(clientNonce.size == NONCE_BYTES) { "Handshake nonce must be $NONCE_BYTES bytes" }
      val info = HELLO_INFO + ByteBuffer.allocate(2).putShort(keyId.toShort()).array()
      return TransferCrypto.hkdfSha256(pairingKey, clientNonce, info, KEY_BYTES)
    }

    /** Key for the server's first flight, derived from both clear handshake nonces. */
    fun replyKey(pairingKey: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray {
      require(pairingKey.size == KEY_BYTES) { "Pairing key must be $KEY_BYTES bytes" }
      require(clientNonce.size == NONCE_BYTES) { "Client nonce must be $NONCE_BYTES bytes" }
      require(serverNonce.size == NONCE_BYTES) { "Server nonce must be $NONCE_BYTES bytes" }
      return TransferCrypto.hkdfSha256(pairingKey, clientNonce + serverNonce, REPLY_INFO, KEY_BYTES)
    }

    /** Nonce for a data-phase record; message ids are unique per direction. */
    fun nonceFor(messageId: Int): ByteArray = ByteBuffer.allocate(12)
      .putInt(0)
      .putLong(messageId.toLong() and 0xffffffffL)
      .array()

    /** Converts the 8-byte handshake session id to the record-header representation. */
    fun sessionIdLong(sessionId: ByteArray): Long {
      require(sessionId.size == SESSION_ID_BYTES) { "Session id must be $SESSION_ID_BYTES bytes" }
      return ByteBuffer.wrap(sessionId).long
    }

    fun sessionIdBytes(sessionId: Long): ByteArray =
      ByteBuffer.allocate(SESSION_ID_BYTES).putLong(sessionId).array()

    private fun streamSalt(streamId: Int): ByteArray =
      ByteBuffer.allocate(Int.SIZE_BYTES).putInt(streamId).array()
  }
}
