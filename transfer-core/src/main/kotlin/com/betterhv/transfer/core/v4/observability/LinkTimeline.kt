package com.betterhv.transfer.core.v4.observability

/** One observed link setup/transfer, safe to display and log. */
data class LinkTimeline(
  val operation: String,
  val peerDeviceId: String? = null,
  val transport: String = "ble",
  val protocolVersion: Int = 4,
  val totalMillis: Long,
  val phasesMillis: Map<String, Long> = emptyMap(),
  val retries: Int = 0,
  val downgradeReason: String? = null,
  val mtu: Int? = null,
  val recordedAtMillis: Long = 0,
) {
  init {
    require(operation.isNotBlank())
    require(totalMillis >= 0)
    require(phasesMillis.values.all { it >= 0 })
  }
}

data class LinkTimelineSummary(
  val samples: Int,
  val p50Millis: Long,
  val p95Millis: Long,
  val p99Millis: Long,
)

/**
 * Rolling in-memory timeline store. The data class deliberately has no payload, key, ticket,
 * nonce or message bytes, so displaying it cannot leak content.
 */
class LinkTimelineRecorder(
  private val capacity: Int = DEFAULT_CAPACITY,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  init {
    require(capacity > 0)
  }

  private val entries = ArrayDeque<LinkTimeline>()

  @Synchronized
  fun record(timeline: LinkTimeline): LinkTimeline {
    val stamped = if (timeline.recordedAtMillis == 0L) {
      timeline.copy(recordedAtMillis = clock())
    } else {
      timeline
    }
    entries.addLast(stamped)
    while (entries.size > capacity) entries.removeFirst()
    return stamped
  }

  @Synchronized
  fun recent(limit: Int = DEFAULT_RECENT): List<LinkTimeline> =
    entries.takeLast(limit.coerceAtLeast(0))

  @Synchronized
  fun summary(limit: Int = DEFAULT_RECENT): LinkTimelineSummary? {
    val values = recent(limit).map { it.totalMillis }.sorted()
    if (values.isEmpty()) return null
    return LinkTimelineSummary(
      samples = values.size,
      p50Millis = percentile(values, 50),
      p95Millis = percentile(values, 95),
      p99Millis = percentile(values, 99),
    )
  }

  @Synchronized
  fun size(): Int = entries.size

  @Synchronized
  fun clear() = entries.clear()

  private fun percentile(sorted: List<Long>, percent: Int): Long {
    val rank = ((percent * sorted.size + 99) / 100).coerceAtLeast(1)
    return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
  }

  companion object {
    const val DEFAULT_CAPACITY = 200
    const val DEFAULT_RECENT = 20
  }
}
