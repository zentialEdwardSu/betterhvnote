package com.betterhv.note.sender

import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.ContentSniffer
import com.betterhv.transfer.core.TransferLimits

/** Why one shared item was not added to the send queue. */
enum class ShareRejectReason { UNSUPPORTED_TYPE, TOO_LARGE, TOO_MANY_PIXELS, EMPTY, UNREADABLE }

/** One candidate from a share intent, described by metadata and its leading bytes. */
data class SharedPayload(
  val displayName: String?,
  val declaredMime: String?,
  val byteLength: Long?,
  val header: ByteArray,
)

data class PlannedShare(val payload: SharedPayload, val kind: ContentKind, val mimeType: String)

data class ShareRejection(
  val displayName: String?,
  val detectedType: String?,
  val reason: ShareRejectReason,
  val limitDescription: String? = null,
)

data class SharePlan(val accepted: List<PlannedShare>, val rejected: List<ShareRejection>) {
  val isEmpty: Boolean
    get() = accepted.isEmpty() && rejected.isEmpty()
}

/** Per-kind limits used by the share intake; defaults mirror [TransferLimits]. */
data class ShareLimits(
  val imageBytes: Long = TransferLimits.MAX_IMAGE_BYTES,
  val pdfBytes: Long = TransferLimits.MAX_PDF_BYTES,
  val textBytes: Int = TransferLimits.MAX_TEXT_BYTES,
) {
  fun bytesFor(kind: ContentKind): Long? = when (kind) {
    ContentKind.IMAGE -> imageBytes
    ContentKind.PDF -> pdfBytes
    else -> null
  }

  fun describe(kind: ContentKind): String = when (kind) {
    ContentKind.IMAGE -> describeBytes(imageBytes)
    ContentKind.PDF -> describeBytes(pdfBytes)
    else -> "${textBytes / 1024} KiB"
  }

  private fun describeBytes(bytes: Long): String = when {
    bytes >= MIB -> "${bytes / MIB} MiB"
    bytes >= KIB -> "${bytes / KIB} KiB"
    else -> "$bytes B"
  }

  private companion object {
    const val KIB = 1024L
    const val MIB = 1024L * 1024L
  }
}

/**
 * Classifies shared payloads one by one so a single unsupported or oversized item never discards the
 * rest of the batch. Framework-free: the Android intake feeds it names, declared types and headers.
 */
object SharePlanner {
  private const val UNKNOWN_TYPE = "unknown"

  fun plan(payloads: List<SharedPayload>, limits: ShareLimits = ShareLimits()): SharePlan {
    val accepted = mutableListOf<PlannedShare>()
    val rejected = mutableListOf<ShareRejection>()
    for (payload in payloads) {
      val sniffed = ContentSniffer.sniff(payload.declaredMime, payload.displayName, payload.header)
      if (sniffed == null) {
        rejected += ShareRejection(
          payload.displayName,
          payload.declaredMime ?: UNKNOWN_TYPE,
          ShareRejectReason.UNSUPPORTED_TYPE,
        )
        continue
      }
      val size = payload.byteLength
      val limit = limits.bytesFor(sniffed.kind)
      when {
        size != null && size <= 0 -> rejected += ShareRejection(
          payload.displayName,
          sniffed.mimeType,
          ShareRejectReason.EMPTY,
        )

        size != null && limit != null && size > limit -> rejected += ShareRejection(
          payload.displayName,
          sniffed.mimeType,
          ShareRejectReason.TOO_LARGE,
          limits.describe(sniffed.kind),
        )

        else -> accepted += PlannedShare(payload, sniffed.kind, sniffed.mimeType)
      }
    }
    return SharePlan(accepted, rejected)
  }
}
