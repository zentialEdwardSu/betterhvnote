package com.betterhv.transfer.core.v4.resume

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Durable progress of one inbound stream. */
data class StreamCheckpoint(
  val itemId: String,
  val sessionId: Long,
  val streamId: Int,
  val receivedBytes: Long,
  val sha256: ByteArray,
  val updatedAtMillis: Long,
) {
  init {
    require(itemId.isNotBlank())
    require(receivedBytes >= 0)
    require(sha256.size == SHA256_BYTES)
  }

  override fun equals(other: Any?): Boolean = other is StreamCheckpoint &&
    itemId == other.itemId && sessionId == other.sessionId && streamId == other.streamId &&
    receivedBytes == other.receivedBytes && sha256.contentEquals(other.sha256) &&
    updatedAtMillis == other.updatedAtMillis

  override fun hashCode(): Int = 31 * itemId.hashCode() + sha256.contentHashCode()

  companion object {
    const val SHA256_BYTES = 32
  }
}

interface StreamCheckpointStore {
  fun loadAll(): List<StreamCheckpoint>
  fun save(checkpoint: StreamCheckpoint)
  fun remove(itemId: String, sessionId: Long)
}

/**
 * Resume bookkeeping for inbound streams.
 *
 * It owns the 24 h retention rule and the `.part` naming convention; the actual file write is the
 * caller's job, so this class stays JVM-testable.
 */
class ResumeManager(
  private val store: StreamCheckpointStore,
  private val clock: () -> Long = System::currentTimeMillis,
  private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
) {
  fun checkpoint(
    itemId: String,
    sessionId: Long,
    streamId: Int,
    receivedBytes: Long,
    sha256: ByteArray,
    nowMillis: Long = clock(),
  ): StreamCheckpoint {
    val checkpoint = StreamCheckpoint(itemId, sessionId, streamId, receivedBytes, sha256, nowMillis)
    store.save(checkpoint)
    return checkpoint
  }

  /** Offset to send in `StreamOpen.offset`; zero when there is no usable checkpoint. */
  fun offsetFor(itemId: String, sessionId: Long): Long {
    val checkpoint = store.loadAll().firstOrNull { it.itemId == itemId && it.sessionId == sessionId }
      ?: return 0
    if (clock() - checkpoint.updatedAtMillis > maxAgeMillis) {
      store.remove(itemId, sessionId)
      return 0
    }
    return checkpoint.receivedBytes
  }

  fun complete(itemId: String, sessionId: Long) {
    store.remove(itemId, sessionId)
  }

  /** Drops expired checkpoints; returns how many were removed. */
  fun cleanup(nowMillis: Long = clock()): Int {
    val expired = store.loadAll().filter { nowMillis - it.updatedAtMillis > maxAgeMillis }
    expired.forEach { store.remove(it.itemId, it.sessionId) }
    return expired.size
  }

  fun partFile(directory: File, itemId: String, sessionId: Long): File =
    File(directory, "${sanitize(itemId)}.${sessionId.toULong().toString(16)}.part")

  private fun sanitize(itemId: String): String =
    itemId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "item" }

  companion object {
    const val DEFAULT_MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
  }
}

/**
 * One line per checkpoint in `checkpoints.txt`, rewritten atomically on every change. The number of
 * in-flight streams is small (<= 8 per peer), so a full rewrite is cheaper than a database.
 */
class FileStreamCheckpointStore(private val directory: File) : StreamCheckpointStore {
  private val file = File(directory, FILE_NAME)

  init {
    directory.mkdirs()
  }

  @Synchronized
  override fun loadAll(): List<StreamCheckpoint> {
    if (!file.isFile) return emptyList()
    return file.readLines().mapNotNull(::decode)
  }

  @Synchronized
  override fun save(checkpoint: StreamCheckpoint) {
    val entries = loadAll()
      .filterNot { it.itemId == checkpoint.itemId && it.sessionId == checkpoint.sessionId }
      .plus(checkpoint)
    write(entries)
  }

  @Synchronized
  override fun remove(itemId: String, sessionId: Long) {
    write(loadAll().filterNot { it.itemId == itemId && it.sessionId == sessionId })
  }

  private fun write(entries: List<StreamCheckpoint>) {
    val temp = File(directory, "$FILE_NAME.tmp")
    FileOutputStream(temp).use { output ->
      entries.forEach { entry ->
        output.write((encode(entry) + "\n").encodeToByteArray())
      }
      output.fd.sync()
    }
    Files.move(
      temp.toPath(),
      file.toPath(),
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE,
    )
  }

  private fun encode(checkpoint: StreamCheckpoint): String = listOf(
    checkpoint.itemId,
    checkpoint.sessionId.toString(),
    checkpoint.streamId.toString(),
    checkpoint.receivedBytes.toString(),
    checkpoint.sha256.joinToString("") { "%02x".format(it) },
    checkpoint.updatedAtMillis.toString(),
  ).joinToString("|")

  private fun decode(line: String): StreamCheckpoint? {
    val parts = line.split('|')
    if (parts.size != 6) return null
    val sha = runCatching { hexToBytes(parts[4]) }.getOrNull() ?: return null
    if (sha.size != StreamCheckpoint.SHA256_BYTES) return null
    return StreamCheckpoint(
      itemId = parts[0],
      sessionId = parts[1].toLongOrNull() ?: return null,
      streamId = parts[2].toIntOrNull() ?: return null,
      receivedBytes = parts[3].toLongOrNull() ?: return null,
      sha256 = sha,
      updatedAtMillis = parts[5].toLongOrNull() ?: return null,
    )
  }

  private fun hexToBytes(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { index ->
      value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
  }

  private companion object {
    const val FILE_NAME = "checkpoints.txt"
  }
}
