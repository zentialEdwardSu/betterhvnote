package com.betterhv.transfer.core

import java.util.UUID

/** Final result recorded for a queue item that left the send queue. */
enum class SendOutcome { SUCCEEDED, FAILED }

/**
 * Immutable record of one completed or abandoned send.
 *
 * [payloadPath] points at the retained copy of the content; it is null for text entries, for failed
 * sends, and after the retention policy dropped the payload. [text] keeps the original text so it can
 * be re-sent without a payload file.
 */
data class SendHistoryEntry(
  val id: UUID,
  val queueItemId: UUID,
  val destinationDeviceId: String?,
  val destinationName: String?,
  val kind: ContentKind,
  val mimeType: String,
  val byteLength: Long,
  val sha256: ByteArray,
  val displayName: String?,
  val completedAt: Long,
  val outcome: SendOutcome,
  val failureReason: String? = null,
  val payloadPath: String? = null,
  val text: String? = null,
) {
  init {
    require(byteLength >= 0) { "byteLength must be non-negative" }
    require(sha256.size == QueueItem.SHA256_BYTES) { "sha256 must contain 32 bytes" }
  }

  override fun equals(other: Any?): Boolean = other is SendHistoryEntry &&
    id == other.id && queueItemId == other.queueItemId &&
    destinationDeviceId == other.destinationDeviceId && destinationName == other.destinationName &&
    kind == other.kind && mimeType == other.mimeType && byteLength == other.byteLength &&
    sha256.contentEquals(other.sha256) && displayName == other.displayName &&
    completedAt == other.completedAt && outcome == other.outcome &&
    failureReason == other.failureReason && payloadPath == other.payloadPath && text == other.text

  override fun hashCode(): Int = 31 * id.hashCode() + sha256.contentHashCode()
}

/** Persistence boundary for send history; implementations must keep one row per queue item. */
interface SendHistoryStore {
  /** Inserts [entry] unless a row with the same `queueItemId` exists, and returns the stored row. */
  fun insert(entry: SendHistoryEntry): SendHistoryEntry

  /** Returns entries newest first, at most [limit]. */
  fun entries(limit: Int = Int.MAX_VALUE): List<SendHistoryEntry>

  fun find(id: UUID): SendHistoryEntry?

  fun findByQueueItem(queueItemId: UUID): SendHistoryEntry?

  fun updatePayloadPath(id: UUID, payloadPath: String?)

  fun delete(id: UUID): Boolean

  fun clear()
}

/** Thread-safe reference implementation used by tests and small hosts. */
class InMemorySendHistoryStore : SendHistoryStore {
  private val values = LinkedHashMap<UUID, SendHistoryEntry>()

  @Synchronized
  override fun insert(entry: SendHistoryEntry): SendHistoryEntry {
    values.values.firstOrNull { it.queueItemId == entry.queueItemId }?.let { return it }
    values[entry.id] = entry
    return entry
  }

  @Synchronized
  override fun entries(limit: Int): List<SendHistoryEntry> =
    values.values.sortedWith(
      compareByDescending<SendHistoryEntry> { it.completedAt }.thenBy { it.id.toString() },
    ).take(limit)

  @Synchronized
  override fun find(id: UUID): SendHistoryEntry? = values[id]

  @Synchronized
  override fun findByQueueItem(queueItemId: UUID): SendHistoryEntry? =
    values.values.firstOrNull { it.queueItemId == queueItemId }

  @Synchronized
  override fun updatePayloadPath(id: UUID, payloadPath: String?) {
    val existing = values[id] ?: return
    values[id] = existing.copy(payloadPath = payloadPath)
  }

  @Synchronized
  override fun delete(id: UUID): Boolean = values.remove(id) != null

  @Synchronized
  override fun clear() = values.clear()
}

/** Bounds applied to history metadata and retained payloads. */
data class HistoryRetentionPolicy(
  val maxEntries: Int = DEFAULT_MAX_ENTRIES,
  val payloadMaxAgeMillis: Long = DEFAULT_PAYLOAD_MAX_AGE_MILLIS,
  val maxPayloadBytes: Long = DEFAULT_MAX_PAYLOAD_BYTES,
) {
  init {
    require(maxEntries > 0) { "maxEntries must be positive" }
    require(payloadMaxAgeMillis > 0) { "payloadMaxAgeMillis must be positive" }
    require(maxPayloadBytes >= 0) { "maxPayloadBytes must not be negative" }
  }

  companion object {
    const val DEFAULT_MAX_ENTRIES = 100
    const val DEFAULT_PAYLOAD_MAX_AGE_MILLIS = 30L * 24L * 60L * 60L * 1000L
    const val DEFAULT_MAX_PAYLOAD_BYTES = 512L * 1024L * 1024L
  }
}

/** Work the caller must apply: delete rows, clear payload references, then delete the listed files. */
data class RetentionPlan(
  val deleteEntries: List<SendHistoryEntry> = emptyList(),
  val clearPayloads: List<SendHistoryEntry> = emptyList(),
) {
  val payloadFilesToDelete: List<SendHistoryEntry>
    get() = (deleteEntries + clearPayloads).filter { it.payloadPath != null }

  val isEmpty: Boolean
    get() = deleteEntries.isEmpty() && clearPayloads.isEmpty()
}

sealed interface RequeueSource {
  data class Payload(val path: String) : RequeueSource

  data class Text(val value: String) : RequeueSource
}

data class RequeuePlan(val source: RequeueSource, val destinationDeviceId: String?)

sealed interface RequeueDecision {
  data class Ready(val plan: RequeuePlan) : RequeueDecision

  data object PayloadUnavailable : RequeueDecision
}

/** Metadata-only recovery actions computed after a crash during the commit sequence. */
data class HistoryReconcilePlan(
  val completedQueueItemIds: List<UUID> = emptyList(),
  val orphanPayloadIds: List<UUID> = emptyList(),
  val prunedHistoryIds: List<UUID> = emptyList(),
)

/**
 * Applies send-history policy without touching the file system, so Android and Windows share the
 * same retention, re-send and crash-recovery rules. Callers apply [RetentionPlan] and delete the
 * returned payload files themselves.
 */
class SendHistoryRecorder(
  private val store: SendHistoryStore,
  private val clock: () -> Long = System::currentTimeMillis,
  private val policy: HistoryRetentionPolicy = HistoryRetentionPolicy(),
) {
  /**
   * Records the final result of [item] once. Repeated calls for the same item return the existing
   * row, which makes the commit sequence idempotent across process deaths.
   */
  fun record(
    item: QueueItem,
    destinationName: String?,
    outcome: SendOutcome,
    failureReason: String? = null,
    text: String? = null,
  ): SendHistoryEntry {
    store.findByQueueItem(item.id)?.let { return it }
    val entry = SendHistoryEntry(
      id = UUID.randomUUID(),
      queueItemId = item.id,
      destinationDeviceId = item.destinationDeviceId,
      destinationName = destinationName,
      kind = item.kind,
      mimeType = item.mimeType,
      byteLength = item.byteLength,
      sha256 = item.sha256,
      displayName = item.displayName,
      completedAt = clock(),
      outcome = outcome,
      failureReason = failureReason?.take(MAX_FAILURE_REASON_CHARS),
      text = text,
    )
    return store.insert(entry)
  }

  /** Associates the retained copy with an existing row; null means the payload was pruned. */
  fun attachPayload(entry: SendHistoryEntry, payloadPath: String?) {
    store.updatePayloadPath(entry.id, payloadPath)
  }

  /**
   * Plans pruning: records beyond the newest [HistoryRetentionPolicy.maxEntries] are deleted whole;
   * retained payloads older than the age limit or beyond the byte budget are cleared while the
   * metadata row stays visible.
   */
  fun retentionPlan(now: Long = clock()): RetentionPlan {
    val ordered = store.entries().sortedWith(NEWEST_FIRST)
    val deleted = ordered.drop(policy.maxEntries)
    val retained = ordered.take(policy.maxEntries)
    val cleared = retained
      .filter { it.payloadPath != null && now - it.completedAt > policy.payloadMaxAgeMillis }
      .toMutableList()
    var retainedBytes = retained
      .filter { it.payloadPath != null && it !in cleared }
      .sumOf(SendHistoryEntry::byteLength)
    if (retainedBytes > policy.maxPayloadBytes) {
      val oldestFirst = retained
        .filter { it.payloadPath != null && it !in cleared }
        .sortedBy(SendHistoryEntry::completedAt)
      for (entry in oldestFirst) {
        if (retainedBytes <= policy.maxPayloadBytes) break
        cleared += entry
        retainedBytes -= entry.byteLength
      }
    }
    return RetentionPlan(deleteEntries = deleted, clearPayloads = cleared)
  }

  /**
   * Decides whether and how a history entry can be re-sent. Payload entries need their retained file;
   * text entries always can be re-sent. The original destination is kept while it is still paired,
   * otherwise the current selection takes over.
   */
  fun requeueDecision(
    entry: SendHistoryEntry,
    payloadExists: Boolean,
    pairedDeviceIds: Set<String>,
    currentDeviceId: String?,
  ): RequeueDecision {
    val source = requeueSource(entry, payloadExists) ?: return RequeueDecision.PayloadUnavailable
    val destination =
      entry.destinationDeviceId?.takeIf(pairedDeviceIds::contains) ?: currentDeviceId
    return RequeueDecision.Ready(RequeuePlan(source, destination))
  }

  /**
   * Plans recovery for an interrupted commit sequence (rename payload, insert row, delete queue row).
   *
   * @param queuePayloadPresent whether the queue item's payload file still exists
   * @param missingHistoryPayloadIds history rows whose retained payload file disappeared
   * @param orphanPayloadIds payload files under the history directory without a row
   */
  fun reconcile(
    queueItems: Collection<QueueItem>,
    queuePayloadPresent: (QueueItem) -> Boolean,
    history: Collection<SendHistoryEntry>,
    missingHistoryPayloadIds: Collection<UUID>,
    orphanPayloadIds: Collection<UUID>,
  ): HistoryReconcilePlan {
    val historyQueueItemIds = history.mapTo(HashSet()) { it.queueItemId }
    val completed = queueItems
      .filter { it.id in historyQueueItemIds && !queuePayloadPresent(it) }
      .map(QueueItem::id)
    val missing = missingHistoryPayloadIds.toSet()
    val pruned =
      history.filter { it.payloadPath != null && it.id in missing }.map(SendHistoryEntry::id)
    return HistoryReconcilePlan(
      completedQueueItemIds = completed.distinct(),
      orphanPayloadIds = orphanPayloadIds.distinct(),
      prunedHistoryIds = pruned.distinct(),
    )
  }

  private fun requeueSource(entry: SendHistoryEntry, payloadExists: Boolean): RequeueSource? =
    when {
      entry.kind == ContentKind.APP_PACKAGE -> null
      entry.payloadPath != null && payloadExists -> RequeueSource.Payload(entry.payloadPath)
      entry.payloadPath == null && entry.text != null -> RequeueSource.Text(entry.text)
      else -> null
    }

  private companion object {
    const val MAX_FAILURE_REASON_CHARS = 512
    val NEWEST_FIRST =
      compareByDescending<SendHistoryEntry> { it.completedAt }.thenBy { it.id.toString() }
  }
}
