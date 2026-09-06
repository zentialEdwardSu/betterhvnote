package com.betterhv.transfer.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class MessageType(val wire: Int) {
  HELLO(1),
  HEARTBEAT(2),
  OFFER_REQUEST(3),
  OFFER(4),
  ACCEPT(5),
  RELEASE(6),
  COMMIT(7),
  ALREADY_COMMITTED(8),
  WIFI_READY(9),
  GET(10),
  CHUNK(11),
  EOF(12),
  ERROR(13),
  PROBE(14),
  PROBE_ACK(15),
}

data class ProtocolFrame(val type: MessageType, val requestId: UUID, val counter: Long, val payload: ByteArray)

object ProtocolCodec {
  const val VERSION = 2
  private const val MAGIC = 0x42484E32 // BHN2
  private const val MAX_FRAME = 1024 * 1024

  fun write(output: OutputStream, frame: ProtocolFrame) {
    require(frame.payload.size <= MAX_FRAME)
    DataOutputStream(output).apply {
      writeInt(MAGIC)
      writeShort(VERSION)
      writeByte(frame.type.wire)
      writeLong(frame.requestId.mostSignificantBits)
      writeLong(frame.requestId.leastSignificantBits)
      writeLong(frame.counter)
      writeInt(frame.payload.size)
      write(frame.payload)
      flush()
    }
  }

  fun read(input: InputStream): ProtocolFrame = DataInputStream(input).run {
    require(readInt() == MAGIC) { "Invalid protocol magic" }
    require(readUnsignedShort() == VERSION) { "Unsupported protocol version" }
    val wireType = readUnsignedByte()
    val type = MessageType.entries.firstOrNull { it.wire == wireType }
      ?: throw IllegalArgumentException("Unknown message type $wireType")
    val id = UUID(readLong(), readLong())
    val counter = readLong()
    val length = readInt()
    require(length in 0..MAX_FRAME) { "Invalid frame length $length" }
    ProtocolFrame(type, id, counter, ByteArray(length).also(::readFully))
  }
}

class SecureFrameChannel(private val key: ByteArray, private val sendPrefix: Int, private val receivePrefix: Int) {
  private var sendCounter = 0L
  private val replay = ReplayWindow()

  @Synchronized fun seal(type: MessageType, requestId: UUID, plaintext: ByteArray): ProtocolFrame {
    val counter = sendCounter++
    val aad = aad(type, requestId, counter)
    return ProtocolFrame(
      type,
      requestId,
      counter,
      TransferCrypto.encrypt(key, TransferCrypto.nonce(sendPrefix, counter), plaintext, aad),
    )
  }

  fun open(frame: ProtocolFrame): ByteArray {
    replay.accept(frame.counter)
    return TransferCrypto.decrypt(
      key,
      TransferCrypto.nonce(receivePrefix, frame.counter),
      frame.payload,
      aad(frame.type, frame.requestId, frame.counter),
    )
  }

  private fun aad(type: MessageType, id: UUID, counter: Long): ByteArray =
    "${ProtocolCodec.VERSION}:${type.wire}:$id:$counter".encodeToByteArray()
}
