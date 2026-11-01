package com.betterhv.transfer.core.v4.session

import kotlinx.coroutines.channels.Channel

/**
 * One ordered, reliable record pipe: a GATT control characteristic or a TCP connection.
 *
 * Records of one message are written back to back by [PeerConnection], so a link never sees
 * fragments of two messages interleaved.
 */
interface RecordLink : AutoCloseable {
  /** `ble`, `lan` or `p2p`; also the link-key label. */
  val label: String

  /** Largest record (header + body) the transport carries as one unit. */
  val maxRecordBytes: Int

  /** Suspends until the transport accepted the record; this is the link's backpressure. */
  suspend fun send(record: ByteArray)

  /** Returns null once the link is closed. */
  suspend fun receive(): ByteArray?
}

/** Link whose inbound side is fed by a callback transport, e.g. a GATT server write callback. */
class ChannelRecordLink(
  override val label: String,
  maxRecordBytes: Int,
  private val sender: suspend (ByteArray) -> Unit,
  private val onClose: () -> Unit = {},
  capacity: Int = DEFAULT_CAPACITY,
) : RecordLink {
  private val inbound = Channel<ByteArray>(capacity)

  @Volatile override var maxRecordBytes: Int = maxRecordBytes

  /** Called from the transport callback; false means the reader fell behind or the link closed. */
  fun offer(record: ByteArray): Boolean = inbound.trySend(record).isSuccess

  /** Suspending variant for transports with their own reader coroutine (TCP control links). */
  suspend fun deliver(record: ByteArray): Boolean = runCatching { inbound.send(record) }.isSuccess

  override suspend fun send(record: ByteArray) = sender(record)

  override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()

  override fun close() {
    if (inbound.close()) onClose()
  }

  companion object {
    const val DEFAULT_CAPACITY = 512
  }
}
