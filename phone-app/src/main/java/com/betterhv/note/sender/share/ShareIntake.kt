package com.betterhv.note.sender.share

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.IntentCompat
import com.betterhv.note.sender.data.PhoneQueueRepository
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.ContentSniffer
import com.betterhv.transfer.core.TransferLimits
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class ShareIntakeResult(
  val accepted: List<UUID>,
  val rejected: List<ShareRejection>,
  val openedOnly: Boolean = false,
)

internal data class CopiedStream(val totalBytes: Long, val sha256: ByteArray)

internal object ShareImageBounds {
  fun validate(width: Int, height: Int): ShareRejectReason? {
    if (width <= 0 || height <= 0) return ShareRejectReason.UNREADABLE
    val pixels = width.toLong() * height.toLong()
    return if (pixels > TransferLimits.MAX_IMAGE_PIXELS) ShareRejectReason.TOO_MANY_PIXELS else null
  }
}

internal class ShareDedupeTracker(
  private val clock: () -> Long,
  private val dedupeWindowMillis: Long,
) {
  private val lock = Any()
  private val inFlight = HashSet<String>()
  private var lastFingerprint: String? = null
  private var lastAcceptedAt = 0L

  fun tryAcquire(fingerprint: String): Boolean = synchronized(lock) {
    if (fingerprint in inFlight) return false
    if (fingerprint == lastFingerprint && clock() - lastAcceptedAt <= dedupeWindowMillis) return false
    inFlight += fingerprint
    true
  }

  fun complete(fingerprint: String) = synchronized(lock) {
    lastFingerprint = fingerprint
    lastAcceptedAt = clock()
  }

  fun release(fingerprint: String) = synchronized(lock) {
    inFlight -= fingerprint
    Unit
  }
}

@Suppress("ReturnCount")
internal fun copyStreamWithSha256(
  input: InputStream,
  header: ByteArray,
  target: File,
  limit: Long,
): CopiedStream? {
  var total = header.size.toLong()
  var exceeded = total > limit
  val digest = MessageDigest.getInstance("SHA-256")
  if (!exceeded) {
    target.outputStream().buffered().use { output ->
      output.write(header)
      digest.update(header)
      val buffer = ByteArray(COPY_BUFFER_BYTES)
      while (!exceeded) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > limit) {
          exceeded = true
        } else {
          output.write(buffer, 0, count)
          digest.update(buffer, 0, count)
        }
      }
    }
  }
  return if (exceeded) null else CopiedStream(total, digest.digest())
}

private const val COPY_BUFFER_BYTES = 64 * 1024

/**
 * Turns a share intent into queue entries one item at a time.
 *
 * The provider MIME type is only a hint: screenshots, chat apps and vendor gallery providers often
 * report null or `application/octet-stream`. Each stream is therefore sniffed from its leading bytes
 * before it is copied, and a rejected item never affects the rest of the batch.
 */
class ShareIntake(
  context: Context,
  private val queue: PhoneQueueRepository,
  clock: () -> Long = System::currentTimeMillis,
  dedupeWindowMillis: Long = DEDUPE_WINDOW_MILLIS,
) {
  private val appContext = context.applicationContext
  private val limits = ShareLimits()
  private val dedupe = ShareDedupeTracker(clock, dedupeWindowMillis)

  fun accept(intent: Intent, destinationDeviceId: String?): ShareIntakeResult {
    val action = intent.action
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return openedOnly()
    val uris = uris(intent)
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    if (uris.isEmpty() && text.isEmpty()) return openedOnly()
    val fingerprint = fingerprint(action, intent.type, uris, text)
    if (!dedupe.tryAcquire(fingerprint)) return openedOnly()

    return try {
      val accepted = mutableListOf<UUID>()
      val rejected = mutableListOf<ShareRejection>()
      uris.forEach { uri -> acceptStream(uri, intent.type, destinationDeviceId, accepted, rejected) }
      if (text.isNotEmpty()) acceptText(text, destinationDeviceId, accepted, rejected)
      rejected.forEach { Log.i(TAG, "share.reject reason=${it.reason} type=${it.detectedType}") }
      Log.i(TAG, "share.accept accepted=${accepted.size} rejected=${rejected.size}")
      dedupe.complete(fingerprint)
      ShareIntakeResult(accepted, rejected)
    } finally {
      dedupe.release(fingerprint)
    }
  }

  private fun acceptStream(
    uri: Uri,
    intentMime: String?,
    destinationDeviceId: String?,
    accepted: MutableList<UUID>,
    rejected: MutableList<ShareRejection>,
  ) {
    val displayName = queryDisplayName(uri)
    val declaredMime = resolveMime(uri, intentMime)
    val temp = File(appContext.cacheDir, "share-${UUID.randomUUID()}.tmp")
    try {
      val input = appContext.contentResolver.openInputStream(uri)
      if (input == null) {
        rejected += ShareRejection(displayName, declaredMime, ShareRejectReason.UNREADABLE)
        return
      }
      input.use { stream ->
        val header = ByteArray(ContentSniffer.HEADER_BYTES)
        val headerLength = stream.readUpTo(header)
        val sniffed = ContentSniffer.sniff(declaredMime, displayName, header.copyOf(headerLength))
        if (sniffed == null) {
          rejected += ShareRejection(
            displayName,
            declaredMime ?: UNKNOWN_TYPE,
            ShareRejectReason.UNSUPPORTED_TYPE
          )
          return
        }
        val limit = limits.bytesFor(sniffed.kind)
        if (limit == null) {
          rejected += ShareRejection(
            displayName,
            sniffed.mimeType,
            ShareRejectReason.UNSUPPORTED_TYPE
          )
          return
        }
        val copied = copyStreamWithSha256(stream, header.copyOf(headerLength), temp, limit)
        if (copied == null) {
          rejected += ShareRejection(
            displayName,
            sniffed.mimeType,
            ShareRejectReason.TOO_LARGE,
            limits.describe(sniffed.kind),
          )
          return
        }
        if (copied.totalBytes == 0L) {
          rejected += ShareRejection(displayName, sniffed.mimeType, ShareRejectReason.EMPTY)
          return
        }
        if (sniffed.kind == ContentKind.IMAGE) {
          checkImageDimensions(temp)?.let { reason ->
            rejected += ShareRejection(displayName, sniffed.mimeType, reason)
            return
          }
        }
        val planned = SharePlanner.plan(
          listOf(SharedPayload(displayName, declaredMime, copied.totalBytes, header.copyOf(headerLength))),
          limits,
        )
        val share = planned.accepted.firstOrNull()
        if (share == null) {
          rejected += planned.rejected
          return
        }
        accepted += enqueue(share, temp, copied.sha256, displayName, destinationDeviceId)
      }
    } catch (_: Throwable) {
      rejected += ShareRejection(displayName, declaredMime, ShareRejectReason.UNREADABLE)
    } finally {
      temp.delete()
    }
  }

  private fun acceptText(
    text: String,
    destinationDeviceId: String?,
    accepted: MutableList<UUID>,
    rejected: MutableList<ShareRejection>,
  ) {
    if (text.encodeToByteArray().size > limits.textBytes) {
      rejected += ShareRejection(
        null,
        "text/plain",
        ShareRejectReason.TOO_LARGE,
        "${limits.textBytes / 1024} KiB",
      )
      return
    }
    accepted += queue.enqueueText(text, destinationDeviceId).id
  }

  private fun enqueue(
    share: PlannedShare,
    temp: File,
    sha256: ByteArray,
    displayName: String?,
    destination: String?,
  ): UUID =
    when (share.kind) {
      ContentKind.PDF -> queue.enqueuePdfFile(temp, displayName, destination, sha256).id
      else -> queue.enqueueImageFile(temp, share.mimeType, displayName, destination, sha256).id
    }

  private fun checkImageDimensions(file: File): ShareRejectReason? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return ShareImageBounds.validate(options.outWidth, options.outHeight)
  }

  private fun queryDisplayName(uri: Uri): String? = runCatching {
    appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { if (it.moveToFirst()) it.getString(0) else null }
  }.getOrNull()

  private fun resolveMime(uri: Uri, intentMime: String?): String? =
    runCatching { appContext.contentResolver.getType(uri) }.getOrNull() ?: intentMime

  private fun fingerprint(action: String?, type: String?, uris: List<Uri>, text: String): String =
    buildString {
      append(action)
      append('|')
      append(type)
      append('|')
      uris.forEach {
        append(it)
        append(';')
      }
      append('|')
      append(text)
    }

  private fun uris(intent: Intent): List<Uri> = when (intent.action) {
    Intent.ACTION_SEND -> listOfNotNull(
      IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
    )

    Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
      intent,
      Intent.EXTRA_STREAM,
      Uri::class.java,
    ).orEmpty()

    else -> emptyList()
  }

  private fun InputStream.readUpTo(target: ByteArray): Int {
    var read = 0
    while (read < target.size) {
      val count = read(target, read, target.size - read)
      if (count < 0) break
      read += count
    }
    return read
  }

  private fun openedOnly() = ShareIntakeResult(emptyList(), emptyList(), openedOnly = true)

  private companion object {
    const val TAG = "NoteLinkShare"
    const val DEDUPE_WINDOW_MILLIS = 2_000L
    const val UNKNOWN_TYPE = "unknown"
  }
}
