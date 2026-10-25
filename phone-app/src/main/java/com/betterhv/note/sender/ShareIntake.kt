package com.betterhv.note.sender

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.IntentCompat
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.ContentSniffer
import com.betterhv.transfer.core.TransferLimits
import java.io.File
import java.io.InputStream
import java.util.UUID

data class ShareIntakeResult(
  val accepted: List<UUID>,
  val rejected: List<ShareRejection>,
  val openedOnly: Boolean = false,
)

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
  private val clock: () -> Long = System::currentTimeMillis,
  private val dedupeWindowMillis: Long = DEDUPE_WINDOW_MILLIS,
) {
  private val appContext = context.applicationContext
  private val limits = ShareLimits()
  private var lastFingerprint: String? = null
  private var lastAcceptedAt = 0L

  fun accept(intent: Intent, destinationDeviceId: String?): ShareIntakeResult {
    val action = intent.action
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return openedOnly()
    val uris = uris(intent)
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    if (uris.isEmpty() && text.isEmpty()) return openedOnly()
    val fingerprint = fingerprint(action, intent.type, uris, text)
    if (isDuplicate(fingerprint)) return openedOnly()

    val accepted = mutableListOf<UUID>()
    val rejected = mutableListOf<ShareRejection>()
    uris.forEach { uri -> acceptStream(uri, intent.type, destinationDeviceId, accepted, rejected) }
    if (text.isNotEmpty()) acceptText(text, destinationDeviceId, accepted, rejected)
    rejected.forEach { Log.i(TAG, "share.reject reason=${it.reason} type=${it.detectedType}") }
    Log.i(TAG, "share.accept accepted=${accepted.size} rejected=${rejected.size}")
    lastFingerprint = fingerprint
    lastAcceptedAt = clock()
    return ShareIntakeResult(accepted, rejected)
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
        val total = copyWithLimit(stream, header.copyOf(headerLength), temp, limit)
        if (total == null) {
          rejected += ShareRejection(
            displayName,
            sniffed.mimeType,
            ShareRejectReason.TOO_LARGE,
            limits.describe(sniffed.kind),
          )
          return
        }
        if (total == 0L) {
          rejected += ShareRejection(displayName, sniffed.mimeType, ShareRejectReason.EMPTY)
          return
        }
        if (sniffed.kind == ContentKind.IMAGE && !withinPixelLimit(temp)) {
          rejected += ShareRejection(
            displayName,
            sniffed.mimeType,
            ShareRejectReason.TOO_MANY_PIXELS
          )
          return
        }
        val planned = SharePlanner.plan(
          listOf(SharedPayload(displayName, declaredMime, total, header.copyOf(headerLength))),
          limits,
        )
        val share = planned.accepted.firstOrNull()
        if (share == null) {
          rejected += planned.rejected
          return
        }
        accepted += enqueue(share, temp, displayName, destinationDeviceId)
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
    displayName: String?,
    destination: String?
  ): UUID =
    when (share.kind) {
      ContentKind.PDF -> queue.enqueuePdfFile(temp, displayName, destination).id
      else -> queue.enqueueImageFile(temp, share.mimeType, displayName, destination).id
    }

  @Suppress("ReturnCount")
  private fun copyWithLimit(
    input: InputStream,
    header: ByteArray,
    target: File,
    limit: Long
  ): Long? {
    var total = header.size.toLong()
    var exceeded = total > limit
    if (!exceeded) {
      target.outputStream().buffered().use { output ->
        output.write(header)
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (!exceeded) {
          val count = input.read(buffer)
          if (count < 0) break
          total += count
          if (total > limit) {
            exceeded = true
          } else {
            output.write(buffer, 0, count)
          }
        }
      }
    }
    return if (exceeded) null else total
  }

  private fun withinPixelLimit(file: File): Boolean {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return false
    return options.outWidth.toLong() * options.outHeight.toLong() <= TransferLimits.MAX_IMAGE_PIXELS
  }

  private fun queryDisplayName(uri: Uri): String? = runCatching {
    appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { if (it.moveToFirst()) it.getString(0) else null }
  }.getOrNull()

  private fun resolveMime(uri: Uri, intentMime: String?): String? =
    runCatching { appContext.contentResolver.getType(uri) }.getOrNull() ?: intentMime

  private fun isDuplicate(fingerprint: String): Boolean =
    fingerprint == lastFingerprint && clock() - lastAcceptedAt <= dedupeWindowMillis

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
    const val COPY_BUFFER_BYTES = 64 * 1024
    const val UNKNOWN_TYPE = "unknown"
  }
}
