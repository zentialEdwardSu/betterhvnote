package com.betterhv.transfer.core.v4.session

import com.betterhv.transfer.core.v4.codec.RecordCodec
import com.betterhv.transfer.core.v4.crypto.Direction
import com.betterhv.transfer.core.v4.crypto.SessionKeys

/**
 * Keys and negotiated limits of one authenticated session. Transport state lives in
 * [PeerConnection]; this class only hands out per-link ciphers.
 *
 * `initiator` selects which direction key is used for sending; both directions are independent.
 */
class ProtocolSession(
  val peerDeviceId: String,
  val sessionId: Long,
  val keys: SessionKeys,
  val capabilities: NegotiatedCapabilities,
  val initiator: Boolean,
) {
  init {
    require(sessionId != 0L) { "Session id must be non-zero" }
  }

  private val sendDirection = if (initiator) Direction.C2S else Direction.S2C
  private val receiveDirection = if (initiator) Direction.S2C else Direction.C2S

  /** Cipher for the link that carried the handshake; it uses the session direction keys. */
  fun handshakeLinkCipher(maxRecordBytes: Int): LinkCipher = LinkCipher(
    sessionId,
    keys.directionKey(sendDirection),
    keys.directionKey(receiveDirection),
    fragmentBody(maxRecordBytes),
  )

  /** Cipher for an additional link; [salt] holds both sides' connection salts. */
  fun linkCipher(label: String, salt: ByteArray, maxRecordBytes: Int): LinkCipher = LinkCipher(
    sessionId,
    keys.linkKey(sendDirection, label, salt),
    keys.linkKey(receiveDirection, label, salt),
    fragmentBody(maxRecordBytes),
  )

  companion object {
    fun fragmentBody(maxRecordBytes: Int): Int {
      val body = maxRecordBytes - RecordCodec.HELLO_HEADER_BYTES
      if (body < MIN_FRAGMENT_BODY) {
        throw RecordCodec.RecordException(
          com.betterhv.transfer.proto.v4.ErrorCode.ERROR_UNSUPPORTED_MODE,
          "Link record size $maxRecordBytes is too small for v4",
        )
      }
      return body
    }

    /** ATT MTU 100 or larger; the 23-byte default MTU cannot carry a v4 record. */
    const val MIN_FRAGMENT_BODY = 48
  }
}
