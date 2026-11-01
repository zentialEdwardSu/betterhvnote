package com.betterhv.transfer.core.v4.session

import com.betterhv.transfer.core.v4.codec.MessageIdWindow
import com.betterhv.transfer.core.v4.codec.RecordCodec
import com.betterhv.transfer.proto.v4.ErrorCode
import com.betterhv.transfer.proto.v4.Frame

data class InboundFrame(val streamId: Int, val frame: Frame)

/**
 * Record protection for one physical link of a session.
 *
 * Every link numbers its own messages from 1 under its own key, so frames may travel over BLE and
 * LAN at the same time without sharing a nonce space or a replay window. A link is ordered, so a
 * message's fragments arrive back to back and a gap or interleaving is a protocol error.
 */
class LinkCipher internal constructor(
  private val sessionId: Long,
  private val sendKey: ByteArray,
  private val receiveKey: ByteArray,
  val maxFragmentBody: Int,
) {
  init {
    require(maxFragmentBody > 0) { "Fragment body budget must be positive" }
  }

  private var nextMessageId = 1
  private val window = MessageIdWindow()
  private var pendingId = 0
  private val pending = ArrayList<ByteArray>()

  /** Largest serialized [Frame] that still fits [RecordCodec.MAX_FRAGMENTS] fragments. */
  val maxFrameBytes: Int =
    minOf(RecordCodec.MAX_MESSAGE_BYTES, maxFragmentBody * RecordCodec.MAX_FRAGMENTS - RecordCodec.TAG_BYTES)

  @Synchronized
  fun seal(streamId: Int, frame: Frame): List<ByteArray> {
    val header = RecordCodec.Header(
      flags = RecordCodec.FLAG_ENCRYPTED,
      keyId = 0,
      sessionId = sessionId,
      streamId = streamId,
      messageId = nextMessageId++,
    )
    return RecordCodec.seal(sendKey, header, frame.toByteArray(), maxFragmentBody)
  }

  /** Consumes one record; returns the frame once its last fragment arrived. */
  @Synchronized
  fun open(record: ByteArray): InboundFrame? {
    val fragment = RecordCodec.parse(record)
    val header = fragment.header
    if (!header.isEncrypted || header.isHello) {
      throw RecordCodec.RecordException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Handshake record on an established link")
    }
    if (header.sessionId != sessionId) {
      throw RecordCodec.RecordException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Record for another session")
    }
    if (fragment.index == 0) {
      if (pending.isNotEmpty()) {
        pending.clear()
        throw RecordCodec.RecordException(ErrorCode.ERROR_REPLAY_REJECTED, "Interleaved message ${header.messageId}")
      }
      if (header.messageId <= window.last()) {
        throw RecordCodec.RecordException(ErrorCode.ERROR_REPLAY_REJECTED, "Replayed message ${header.messageId}")
      }
      pendingId = header.messageId
    } else if (pending.size != fragment.index || header.messageId != pendingId) {
      pending.clear()
      throw RecordCodec.RecordException(
        ErrorCode.ERROR_REPLAY_REJECTED,
        "Out-of-order fragment ${fragment.index} for ${header.messageId}",
      )
    }
    pending += record
    if (pending.size < fragment.count) return null
    val records = pending.toList()
    pending.clear()
    val frame = try {
      Frame.parseFrom(RecordCodec.open(receiveKey, records))
    } catch (error: RecordCodec.RecordException) {
      throw error
    } catch (error: Throwable) {
      throw RecordCodec.RecordException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Malformed frame")
    }
    // Only an authenticated message may advance the window, so forged ids cannot block a link.
    window.accept(header.messageId)
    if (frame.bodyCase == Frame.BodyCase.BODY_NOT_SET) {
      throw RecordCodec.RecordException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Empty frame")
    }
    return InboundFrame(header.streamId, frame)
  }
}
