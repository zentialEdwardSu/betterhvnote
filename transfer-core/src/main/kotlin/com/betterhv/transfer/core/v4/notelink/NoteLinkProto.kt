package com.betterhv.transfer.core.v4.notelink

import com.betterhv.transfer.core.ContentCounts
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.proto.v4.Frame
import com.betterhv.transfer.proto.v4.KindCount
import com.betterhv.transfer.proto.v4.Offer
import com.betterhv.transfer.proto.v4.QueueChanged
import com.betterhv.transfer.proto.v4.StreamComplete
import com.betterhv.transfer.proto.v4.StreamData
import com.betterhv.transfer.proto.v4.StreamOpen
import com.google.protobuf.ByteString
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import com.betterhv.transfer.proto.v4.ContentKind as ProtoKind

/** Bytes the server can stream for one leased item, opened at any offset. */
class PayloadSource(
  val length: Long,
  val sha256: ByteArray,
  private val opener: (Long) -> InputStream,
) {
  fun open(offset: Long): InputStream = opener(offset)

  companion object {
    fun of(bytes: ByteArray): PayloadSource = PayloadSource(bytes.size.toLong(), TransferCrypto.sha256(bytes)) {
      bytes.inputStream().apply { skip(it) }
    }

    fun of(file: java.io.File, sha256: ByteArray): PayloadSource = PayloadSource(file.length(), sha256) { offset ->
      file.inputStream().apply { if (offset > 0) skip(offset) }
    }
  }
}

/** Destination of a received stream; [complete] returns false when the bytes do not verify. */
interface ByteSink {
  fun write(bytes: ByteArray)
  fun complete(sha256: ByteArray, totalBytes: Long): Boolean
  fun abort()
}

/** Writes to [file], hashing on the fly; a mismatch deletes the file. */
class FileByteSink(private val file: java.io.File) : ByteSink {
  private val output = java.io.BufferedOutputStream(java.io.FileOutputStream(file), 64 * 1024)
  private val digest = MessageDigest.getInstance("SHA-256")
  private var written = 0L

  override fun write(bytes: ByteArray) {
    output.write(bytes)
    digest.update(bytes)
    written += bytes.size
  }

  override fun complete(sha256: ByteArray, totalBytes: Long): Boolean {
    output.close()
    val ok = written == totalBytes && MessageDigest.isEqual(digest.digest(), sha256)
    if (!ok) file.delete()
    return ok
  }

  override fun abort() {
    runCatching { output.close() }
    file.delete()
  }
}

/** Collects a small stream in memory, e.g. text. */
class MemoryByteSink(private val limit: Int) : ByteSink {
  private val output = java.io.ByteArrayOutputStream()
  var bytes: ByteArray = ByteArray(0)
    private set

  override fun write(bytes: ByteArray) {
    check(output.size() + bytes.size <= limit) { "Payload exceeds $limit bytes" }
    output.write(bytes)
  }

  override fun complete(sha256: ByteArray, totalBytes: Long): Boolean {
    val value = output.toByteArray()
    bytes = value
    return value.size.toLong() == totalBytes && MessageDigest.isEqual(TransferCrypto.sha256(value), sha256)
  }

  override fun abort() = output.reset()
}

object NoteLinkProto {
  /** Text up to this size travels inside the offer and needs no stream. */
  const val INLINE_TEXT_BYTES = 4 * 1024

  /** `StreamOpen.flags`: the opener sends the bytes (export push) instead of receiving them. */
  const val FLAG_UPLOAD = 1

  fun toProto(kind: ContentKind): ProtoKind = when (kind) {
    ContentKind.IMAGE -> ProtoKind.CONTENT_IMAGE
    ContentKind.TEXT -> ProtoKind.CONTENT_TEXT
    ContentKind.PDF -> ProtoKind.CONTENT_PDF
    ContentKind.APP_PACKAGE -> ProtoKind.CONTENT_APP_PACKAGE
  }

  fun fromProto(kind: ProtoKind): ContentKind? = when (kind) {
    ProtoKind.CONTENT_IMAGE -> ContentKind.IMAGE
    ProtoKind.CONTENT_TEXT -> ContentKind.TEXT
    ProtoKind.CONTENT_PDF -> ContentKind.PDF
    ProtoKind.CONTENT_APP_PACKAGE -> ContentKind.APP_PACKAGE
    else -> null
  }

  fun uuidBytes(id: UUID): ByteArray = ByteBuffer.allocate(16)
    .putLong(id.mostSignificantBits)
    .putLong(id.leastSignificantBits)
    .array()

  fun uuid(bytes: ByteArray): UUID {
    require(bytes.size == 16) { "Item id must contain 16 bytes" }
    val buffer = ByteBuffer.wrap(bytes)
    return UUID(buffer.long, buffer.long)
  }

  fun uuid(bytes: ByteString): UUID = uuid(bytes.toByteArray())

  fun offer(
    id: UUID,
    kind: ContentKind,
    mimeType: String,
    byteLength: Long,
    sha256: ByteArray,
    displayName: String?,
    leaseTtlMillis: Long = 0,
    inlineData: ByteArray? = null,
  ): Offer = Offer.newBuilder()
    .setItemId(ByteString.copyFrom(uuidBytes(id)))
    .setKind(toProto(kind))
    .setMimeType(mimeType)
    .setByteLength(byteLength)
    .setSha256(ByteString.copyFrom(sha256))
    .setDisplayName(displayName.orEmpty())
    .setLeaseTtlMs(leaseTtlMillis.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
    .apply { inlineData?.let { setInlineData(ByteString.copyFrom(it)) } }
    .build()

  fun queueChanged(counts: ContentCounts, heads: List<Offer> = emptyList(), revision: Long = 0): QueueChanged =
    QueueChanged.newBuilder()
      .addAllCounts(
        ContentKind.entries.map { kind ->
          KindCount.newBuilder().setKind(toProto(kind)).setCount(counts.forKind(kind).coerceAtLeast(0)).build()
        },
      )
      .addAllHeads(heads)
      .setQueueRevision(revision)
      .build()

  fun counts(value: QueueChanged): ContentCounts {
    fun count(kind: ProtoKind) = value.countsList.firstOrNull { it.kind == kind }?.count ?: 0
    return ContentCounts(
      images = count(ProtoKind.CONTENT_IMAGE),
      texts = count(ProtoKind.CONTENT_TEXT),
      pdfs = count(ProtoKind.CONTENT_PDF),
      appPackages = count(ProtoKind.CONTENT_APP_PACKAGE),
    )
  }

  internal fun streamOpen(itemId: ByteString, total: Long, sha256: ByteArray, offset: Long, flags: Int = 0): Frame =
    Frame.newBuilder().setStreamOpen(
      StreamOpen.newBuilder()
        .setItemId(itemId)
        .setTotalBytes(total)
        .setSha256(ByteString.copyFrom(sha256))
        .setOffset(offset)
        .setFlags(flags),
    ).build()

  internal fun streamData(data: ByteArray, length: Int, offset: Long): Frame =
    Frame.newBuilder().setStreamData(
      StreamData.newBuilder().setData(ByteString.copyFrom(data, 0, length)).setOffset(offset),
    ).build()

  internal fun streamComplete(sha256: ByteArray, total: Long): Frame =
    Frame.newBuilder().setStreamComplete(
      StreamComplete.newBuilder().setSha256(ByteString.copyFrom(sha256)).setTotalBytes(total),
    ).build()

  internal fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")
}
