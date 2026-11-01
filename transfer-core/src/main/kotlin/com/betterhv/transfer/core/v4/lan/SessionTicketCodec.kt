package com.betterhv.transfer.core.v4.lan

import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.core.v4.crypto.SessionKeys
import com.betterhv.transfer.proto.v4.ErrorCode
import com.betterhv.transfer.proto.v4.SessionTicket
import java.io.DataInputStream

/**
 * Stateless LAN ticket envelope.
 *
 * Layout:
 * `u8 version | u16 key_id | u8 session_id[8] | u16 body_length | u8 nonce[12] | ciphertext`.
 * The session id is not secret and is needed to derive the ticket key, so it travels in the
 * clear header; it is authenticated because the first 11 bytes are the AEAD additional data.
 */
object SessionTicketCodec {
  const val VERSION = 1
  const val HEADER_BYTES = 13
  const val AAD_BYTES = 11
  const val NONCE_BYTES = 12
  const val MAX_BODY_BYTES = 512
  const val MIN_BODY_BYTES = NONCE_BYTES + 16
  const val MAX_TICKET_BYTES = MAX_BODY_BYTES - NONCE_BYTES - 16

  class TicketException(val code: ErrorCode, message: String) : IllegalArgumentException(message)

  fun keyId(envelope: ByteArray): Int? {
    if (envelope.size < HEADER_BYTES || (envelope[0].toInt() and 0xff) != VERSION) return null
    return readShort(envelope, 1)
  }

  fun sessionId(envelope: ByteArray): ByteArray? {
    if (envelope.size < HEADER_BYTES) return null
    return envelope.copyOfRange(3, 3 + SessionKeys.SESSION_ID_BYTES)
  }

  fun seal(pairingKey: ByteArray, ticket: SessionTicket): ByteArray {
    val sessionId = ticket.sessionId.toByteArray()
    if (sessionId.size != SessionKeys.SESSION_ID_BYTES || sessionId.all { it == 0.toByte() }) {
      throw TicketException(ErrorCode.ERROR_INVALID_ENDPOINT, "Ticket needs an 8-byte session id")
    }
    val plaintext = ticket.toByteArray()
    if (plaintext.size > MAX_TICKET_BYTES) {
      throw TicketException(ErrorCode.ERROR_MESSAGE_TOO_LARGE, "Ticket is too large")
    }
    val keyId = SessionKeys.keyId(pairingKey)
    val nonce = TransferCrypto.randomBytes(NONCE_BYTES)
    val aad = aad(keyId, sessionId)
    val ciphertext = TransferCrypto.encrypt(SessionKeys.ticketKey(pairingKey, sessionId), nonce, plaintext, aad)
    val body = nonce + ciphertext
    if (body.size > MAX_BODY_BYTES) {
      throw TicketException(ErrorCode.ERROR_MESSAGE_TOO_LARGE, "Ticket envelope is too large")
    }
    return ByteArray(HEADER_BYTES + body.size).also { output ->
      output[0] = VERSION.toByte()
      writeShort(output, 1, keyId)
      sessionId.copyInto(output, 3)
      writeShort(output, 11, body.size)
      body.copyInto(output, HEADER_BYTES)
    }
  }

  fun open(pairingKey: ByteArray, envelope: ByteArray): SessionTicket {
    if (envelope.size < HEADER_BYTES) {
      throw TicketException(ErrorCode.ERROR_TICKET_EXPIRED, "Truncated ticket")
    }
    if ((envelope[0].toInt() and 0xff) != VERSION) {
      throw TicketException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Unsupported ticket version")
    }
    val keyId = readShort(envelope, 1)
    if (keyId != SessionKeys.keyId(pairingKey)) {
      throw TicketException(ErrorCode.ERROR_AUTHENTICATION_FAILED, "Ticket key id mismatch")
    }
    val length = readShort(envelope, 11)
    if (length !in MIN_BODY_BYTES..MAX_BODY_BYTES || envelope.size != HEADER_BYTES + length) {
      throw TicketException(ErrorCode.ERROR_MESSAGE_TOO_LARGE, "Invalid ticket length")
    }
    val sessionId = requireNotNull(sessionId(envelope))
    if (sessionId.all { it == 0.toByte() }) {
      throw TicketException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Invalid session id")
    }
    val nonce = envelope.copyOfRange(HEADER_BYTES, HEADER_BYTES + NONCE_BYTES)
    val ciphertext = envelope.copyOfRange(HEADER_BYTES + NONCE_BYTES, envelope.size)
    val plaintext = try {
      TransferCrypto.decrypt(
        SessionKeys.ticketKey(pairingKey, sessionId),
        nonce,
        ciphertext,
        envelope.copyOfRange(0, AAD_BYTES),
      )
    } catch (error: Throwable) {
      throw TicketException(ErrorCode.ERROR_AUTHENTICATION_FAILED, "Ticket authentication failed")
    }
    return try {
      SessionTicket.parseFrom(plaintext)
    } catch (error: Throwable) {
      throw TicketException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Malformed ticket")
    }
  }

  fun readEnvelope(input: DataInputStream): ByteArray {
    val header = ByteArray(HEADER_BYTES)
    input.readFully(header)
    if ((header[0].toInt() and 0xff) != VERSION) {
      throw TicketException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Unsupported ticket version")
    }
    val length = readShort(header, 11)
    if (length !in MIN_BODY_BYTES..MAX_BODY_BYTES) {
      throw TicketException(ErrorCode.ERROR_MESSAGE_TOO_LARGE, "Invalid ticket length")
    }
    val body = ByteArray(length)
    input.readFully(body)
    return header + body
  }

  private fun aad(keyId: Int, sessionId: ByteArray): ByteArray = ByteArray(AAD_BYTES).also { output ->
    output[0] = VERSION.toByte()
    writeShort(output, 1, keyId)
    sessionId.copyInto(output, 3)
  }

  private fun writeShort(output: ByteArray, offset: Int, value: Int) {
    output[offset] = (value ushr 8).toByte()
    output[offset + 1] = value.toByte()
  }

  private fun readShort(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}
