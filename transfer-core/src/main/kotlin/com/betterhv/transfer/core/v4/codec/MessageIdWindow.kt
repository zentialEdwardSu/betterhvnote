package com.betterhv.transfer.core.v4.codec

import com.betterhv.transfer.proto.v4.ErrorCode

/**
 * Monotonic message-id check for one direction of one session.
 *
 * The link is ordered (BLE and TCP both preserve order), so a non-increasing id is either a
 * replay or a protocol bug; both are rejected before the record is decrypted.
 */
class MessageIdWindow {
  private var lastAccepted = 0

  @Synchronized
  fun accept(messageId: Int) {
    if (messageId <= lastAccepted) {
      throw RecordCodec.RecordException(
        ErrorCode.ERROR_REPLAY_REJECTED,
        "Replayed or out-of-order message $messageId after $lastAccepted",
      )
    }
    lastAccepted = messageId
  }

  @Synchronized
  fun last(): Int = lastAccepted

  @Synchronized
  fun reset() {
    lastAccepted = 0
  }
}
