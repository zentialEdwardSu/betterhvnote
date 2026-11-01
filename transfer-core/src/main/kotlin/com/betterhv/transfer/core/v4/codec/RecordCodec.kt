package com.betterhv.transfer.core.v4.codec

import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.proto.v4.ErrorCode
import java.nio.ByteBuffer

/**
 * Fixed-layout record framing for the v4 protocol.
 *
 * The header is deliberately *not* protobuf: it is the AEAD additional data, and protobuf
 * serialization is not canonical. A whole message is encrypted exactly once and the ciphertext is
 * then split into fragments, so every fragment of one message shares a single nonce/tag. Fragment
 * fields are routing metadata and are zeroed in the AAD.
 */
object RecordCodec {
  const val MAGIC_0: Byte = 0x4e // 'N'
  const val MAGIC_1: Byte = 0x4c // 'L'
  const val VERSION = 4
  const val HEADER_BYTES = 28
  const val HELLO_NONCE_BYTES = 16
  const val HELLO_HEADER_BYTES = HEADER_BYTES + HELLO_NONCE_BYTES
  const val MAX_MESSAGE_BYTES = 64 * 1024
  const val MAX_FRAGMENTS = 64
  const val TAG_BYTES = 16

  const val FLAG_ENCRYPTED = 1
  const val FLAG_HELLO = 1 shl 1
  const val FLAG_RESPONSE = 1 shl 2
  const val FLAG_ACK_REQUESTED = 1 shl 3

  /**
   * Clear-text pairing record. Only SRP public values and proofs travel in it, so it needs no key;
   * it is the one record type without [FLAG_ENCRYPTED].
   */
  const val FLAG_PAIRING = 1 shl 4
  private const val KNOWN_FLAGS =
    FLAG_ENCRYPTED or FLAG_HELLO or FLAG_RESPONSE or FLAG_ACK_REQUESTED or FLAG_PAIRING

  /** Routing fields shared by all fragments of one message. */
  data class Header(
    val flags: Int,
    val keyId: Int,
    val sessionId: Long,
    val streamId: Int,
    val messageId: Int,
  ) {
    val isHello: Boolean get() = flags and FLAG_HELLO != 0
    val isResponse: Boolean get() = flags and FLAG_RESPONSE != 0
    val isPairing: Boolean get() = flags and FLAG_PAIRING != 0
    val isEncrypted: Boolean get() = flags and FLAG_ENCRYPTED != 0

    init {
      require(flags and KNOWN_FLAGS.inv() == 0) { "Unknown record flags: $flags" }
      require(validFlags(flags)) { "v4 records must be encrypted unless they are pairing records" }
      require(keyId in 0..0xffff) { "Invalid key id" }
    }
  }

  data class Fragment(
    val header: Header,
    val index: Int,
    val count: Int,
    val body: ByteArray,
  ) {
    override fun equals(other: Any?): Boolean = other is Fragment &&
      header == other.header && index == other.index && count == other.count &&
      body.contentEquals(other.body)

    override fun hashCode(): Int = 31 * header.hashCode() + body.contentHashCode()
  }

  class RecordException(val code: ErrorCode, message: String) : IllegalArgumentException(message)

  /**
   * Encrypts [payload] once and splits the ciphertext into fragments of at most [maxFragmentBody]
   * bytes. [handshakeNonce] is required exactly when the header carries [FLAG_HELLO].
   */
  fun seal(
    key: ByteArray,
    header: Header,
    payload: ByteArray,
    maxFragmentBody: Int,
    handshakeNonce: ByteArray? = null,
  ): List<ByteArray> {
    if (payload.size > MAX_MESSAGE_BYTES) {
      throw RecordException(ErrorCode.ERROR_MESSAGE_TOO_LARGE, "Message exceeds ${MAX_MESSAGE_BYTES} bytes")
    }
    require(maxFragmentBody >= 1) { "Fragment body budget must be positive" }
    require(header.isEncrypted) { "Use sealClear for pairing records" }
    if (header.isHello) {
      require(handshakeNonce != null && handshakeNonce.size == HELLO_NONCE_BYTES) {
        "HELLO records need a ${HELLO_NONCE_BYTES}-byte handshake nonce"
      }
    } else {
      require(handshakeNonce == null) { "Only HELLO records carry a handshake nonce" }
    }
    val ciphertext = TransferCrypto.encrypt(key, nonceFor(header.messageId), payload, aad(header, handshakeNonce))
    val count = ((ciphertext.size + maxFragmentBody - 1) / maxFragmentBody).coerceAtLeast(1)
    if (count > MAX_FRAGMENTS) {
      throw RecordException(ErrorCode.ERROR_MESSAGE_TOO_LARGE, "Message needs $count fragments")
    }
    return List(count) { index ->
      val start = index * maxFragmentBody
      val end = (start + maxFragmentBody).coerceAtMost(ciphertext.size)
      encode(header, index, count, ciphertext.copyOfRange(start, end), handshakeNonce)
    }
  }

  /** Splits a clear pairing message into fragments; see [FLAG_PAIRING]. */
  fun sealClear(header: Header, payload: ByteArray, maxFragmentBody: Int): List<ByteArray> {
    require(header.isPairing) { "Only pairing records travel in the clear" }
    require(payload.isNotEmpty()) { "Pairing message is empty" }
    require(maxFragmentBody >= 1) { "Fragment body budget must be positive" }
    if (payload.size > MAX_MESSAGE_BYTES) {
      throw RecordException(ErrorCode.ERROR_MESSAGE_TOO_LARGE, "Message exceeds ${MAX_MESSAGE_BYTES} bytes")
    }
    val count = (payload.size + maxFragmentBody - 1) / maxFragmentBody
    if (count > MAX_FRAGMENTS) {
      throw RecordException(ErrorCode.ERROR_MESSAGE_TOO_LARGE, "Message needs $count fragments")
    }
    return List(count) { index ->
      val start = index * maxFragmentBody
      val end = (start + maxFragmentBody).coerceAtMost(payload.size)
      encode(header, index, count, payload.copyOfRange(start, end), null)
    }
  }

  /** Reassembles one clear pairing message from its ordered fragments. */
  fun openClear(records: List<ByteArray>): ByteArray {
    val fragments = orderedFragments(records)
    if (!fragments.first().header.isPairing) {
      throw RecordException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Not a pairing record")
    }
    return concatenate(fragments)
  }

  /** Reassembles and decrypts one message from its ordered fragments. */
  fun open(key: ByteArray, records: List<ByteArray>): ByteArray {
    val fragments = orderedFragments(records)
    val first = fragments.first()
    if (!first.header.isEncrypted) {
      throw RecordException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Record is not encrypted")
    }
    val ciphertext = concatenate(fragments)
    if (ciphertext.size < TAG_BYTES) {
      throw RecordException(ErrorCode.ERROR_MESSAGE_TOO_LARGE, "Truncated ciphertext")
    }
    val nonce = handshakeNonce(first.header, records.first())
    return try {
      TransferCrypto.decrypt(key, nonceFor(first.header.messageId), ciphertext, aad(first.header, nonce))
    } catch (error: Throwable) {
      throw RecordException(ErrorCode.ERROR_AUTHENTICATION_FAILED, "Record authentication failed")
    }
  }

  fun parse(record: ByteArray): Fragment {
    if (record.size < HEADER_BYTES) {
      throw RecordException(ErrorCode.ERROR_MESSAGE_TOO_LARGE, "Truncated record header")
    }
    if (record[0] != MAGIC_0 || record[1] != MAGIC_1) {
      throw RecordException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Not a v4 record")
    }
    val version = record[2].toInt() and 0xff
    if (version != VERSION) {
      throw RecordException(ErrorCode.ERROR_UNSUPPORTED_PROTOCOL, "Unsupported record version $version")
    }
    val flags = record[3].toInt() and 0xff
    if (!validFlags(flags)) {
      throw RecordException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Invalid record flags $flags")
    }
    val headerLength = record[4].toInt() and 0xff
    val expectedHeader = if (flags and FLAG_HELLO != 0) HELLO_HEADER_BYTES else HEADER_BYTES
    if (headerLength != expectedHeader || record.size <= headerLength) {
      throw RecordException(ErrorCode.ERROR_MESSAGE_TOO_LARGE, "Invalid record header length")
    }
    if (record[5].toInt() != 0) {
      throw RecordException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Reserved header byte is not zero")
    }
    val header = Header(
      flags = flags,
      keyId = readShort(record, 6),
      sessionId = readLong(record, 8),
      streamId = readInt(record, 16),
      messageId = readInt(record, 20),
    )
    val index = readShort(record, 24)
    val count = readShort(record, 26)
    if (count !in 1..MAX_FRAGMENTS || index !in 0 until count) {
      throw RecordException(ErrorCode.ERROR_MESSAGE_TOO_LARGE, "Invalid fragment sequence $index/$count")
    }
    return Fragment(header, index, count, record.copyOfRange(headerLength, record.size))
  }

  private fun validFlags(flags: Int): Boolean {
    if (flags and KNOWN_FLAGS.inv() != 0) return false
    val encrypted = flags and FLAG_ENCRYPTED != 0
    val pairing = flags and FLAG_PAIRING != 0
    if (encrypted == pairing) return false
    return !(pairing && flags and FLAG_HELLO != 0)
  }

  private fun orderedFragments(records: List<ByteArray>): List<Fragment> {
    if (records.isEmpty()) throw RecordException(ErrorCode.ERROR_INTERNAL, "No records")
    val fragments = records.map(::parse)
    val first = fragments.first()
    if (fragments.size != first.count) {
      throw RecordException(ErrorCode.ERROR_MESSAGE_TOO_LARGE, "Incomplete fragment set")
    }
    fragments.forEachIndexed { index, fragment ->
      if (fragment.index != index) {
        throw RecordException(ErrorCode.ERROR_REPLAY_REJECTED, "Out-of-order fragment $index")
      }
      if (fragment.header != first.header || fragment.count != first.count) {
        throw RecordException(ErrorCode.ERROR_REPLAY_REJECTED, "Interleaved records")
      }
    }
    return fragments
  }

  private fun concatenate(fragments: List<Fragment>): ByteArray =
    ByteArray(fragments.sumOf { it.body.size }).also { output ->
      var offset = 0
      fragments.forEach { fragment ->
        fragment.body.copyInto(output, offset)
        offset += fragment.body.size
      }
    }

  fun fragmentBudget(mtu: Int, hello: Boolean = false): Int {
    val header = if (hello) HELLO_HEADER_BYTES else HEADER_BYTES
    val payload = mtu - 3 - header
    if (payload < 1) {
      throw RecordException(ErrorCode.ERROR_UNSUPPORTED_MODE, "ATT MTU $mtu is too small for v4")
    }
    return payload
  }

  fun nonceFor(messageId: Int): ByteArray = ByteBuffer.allocate(12)
    .putInt(0)
    .putLong(messageId.toLong() and 0xffffffffL)
    .array()

  private fun encode(
    header: Header,
    index: Int,
    count: Int,
    body: ByteArray,
    handshakeNonce: ByteArray?,
  ): ByteArray {
    val headerLength = if (header.isHello) HELLO_HEADER_BYTES else HEADER_BYTES
    val output = ByteArray(headerLength + body.size)
    output[0] = MAGIC_0
    output[1] = MAGIC_1
    output[2] = VERSION.toByte()
    output[3] = header.flags.toByte()
    output[4] = headerLength.toByte()
    output[5] = 0
    writeShort(output, 6, header.keyId)
    writeLong(output, 8, header.sessionId)
    writeInt(output, 16, header.streamId)
    writeInt(output, 20, header.messageId)
    writeShort(output, 24, index)
    writeShort(output, 26, count)
    if (handshakeNonce != null) {
      require(handshakeNonce.size == HELLO_NONCE_BYTES)
      handshakeNonce.copyInto(output, HEADER_BYTES)
    }
    body.copyInto(output, headerLength)
    return output
  }

  /** The routing header with fragment fields zeroed, plus the HELLO nonce when present. */
  private fun aad(header: Header, handshakeNonce: ByteArray?): ByteArray {
    val output = ByteArray(if (handshakeNonce == null) HEADER_BYTES else HELLO_HEADER_BYTES)
    output[0] = MAGIC_0
    output[1] = MAGIC_1
    output[2] = VERSION.toByte()
    output[3] = header.flags.toByte()
    output[4] = output.size.toByte()
    output[5] = 0
    writeShort(output, 6, header.keyId)
    writeLong(output, 8, header.sessionId)
    writeInt(output, 16, header.streamId)
    writeInt(output, 20, header.messageId)
    // Bytes 24..27 stay zero: fragment index/count are not authenticated.
    handshakeNonce?.copyInto(output, HEADER_BYTES)
    return output
  }

  private fun handshakeNonce(header: Header, record: ByteArray): ByteArray? {
    if (!header.isHello) return null
    if (record.size < HELLO_HEADER_BYTES) {
      throw RecordException(ErrorCode.ERROR_MESSAGE_TOO_LARGE, "HELLO record is missing its nonce")
    }
    return record.copyOfRange(HEADER_BYTES, HELLO_HEADER_BYTES)
  }

  private fun writeShort(output: ByteArray, offset: Int, value: Int) {
    output[offset] = (value ushr 8).toByte()
    output[offset + 1] = value.toByte()
  }

  private fun writeInt(output: ByteArray, offset: Int, value: Int) {
    output[offset] = (value ushr 24).toByte()
    output[offset + 1] = (value ushr 16).toByte()
    output[offset + 2] = (value ushr 8).toByte()
    output[offset + 3] = value.toByte()
  }

  private fun writeLong(output: ByteArray, offset: Int, value: Long) {
    for (index in 0 until Long.SIZE_BYTES) {
      output[offset + index] = (value ushr (56 - index * 8)).toByte()
    }
  }

  private fun readShort(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

  private fun readInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() shl 24) or
      ((bytes[offset + 1].toInt() and 0xff) shl 16) or
      ((bytes[offset + 2].toInt() and 0xff) shl 8) or
      (bytes[offset + 3].toInt() and 0xff)

  private fun readLong(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (index in 0 until Long.SIZE_BYTES) {
      value = (value shl 8) or (bytes[offset + index].toLong() and 0xff)
    }
    return value
  }
}
