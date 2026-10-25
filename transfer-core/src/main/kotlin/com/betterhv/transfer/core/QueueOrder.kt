package com.betterhv.transfer.core

/** Order in which pending queue items are offered to a requesting device. */
enum class SendOrder {
  QUEUE,
  STACK,
  ;

  companion object {
    /** Parses a persisted value, falling back to the default queue order. */
    fun fromStorageId(value: String?): SendOrder = entries.firstOrNull { it.name == value } ?: QUEUE
  }
}

/**
 * Single source of truth for "which pending item is offered next".
 *
 * The platform repositories implement [QueueStore.next] through this helper, and the dashboard
 * badge uses [nextPending] as well, so the highlighted item and the leased item cannot disagree.
 */
object SendOrderSelector {
  fun nextPending(
    items: List<QueueItem>,
    order: SendOrder,
    eligible: (QueueItem) -> Boolean,
  ): QueueItem? {
    var selected: QueueItem? = null
    for (item in items) {
      if (item.state != QueueState.PENDING || !eligible(item)) continue
      selected = pick(selected, item, order)
    }
    return selected
  }

  fun nextPending(
    items: List<QueueItem>,
    kind: ContentKind,
    destinationDeviceId: String?,
    order: SendOrder,
  ): QueueItem? = nextPending(items, order) { isEligible(it, kind, destinationDeviceId) }

  /** Whether [item] may be leased for [kind] by [destinationDeviceId]. */
  fun isEligible(item: QueueItem, kind: ContentKind, destinationDeviceId: String?): Boolean =
    item.kind == kind && (item.destinationDeviceId == null || item.destinationDeviceId == destinationDeviceId)

  /** Returns [items] in enqueue order, or reversed for [SendOrder.STACK]. */
  fun ordered(items: List<QueueItem>, order: SendOrder): List<QueueItem> {
    val sorted = items.sortedWith { left, right -> compareQueueOrder(left, right) }
    return if (order == SendOrder.QUEUE) sorted else sorted.reversed()
  }

  /** Total order used by both selection and display: position, then creation time, then id. */
  fun compareQueueOrder(left: QueueItem, right: QueueItem): Int = compareValuesBy(
    left,
    right,
    QueueItem::position,
    QueueItem::createdAt,
    { it.id.toString() },
  )

  private fun pick(selected: QueueItem?, candidate: QueueItem, order: SendOrder): QueueItem {
    if (selected == null) return candidate
    val comparison = compareQueueOrder(candidate, selected)
    val replaces = when (order) {
      SendOrder.QUEUE -> comparison < 0
      SendOrder.STACK -> comparison > 0
    }
    return if (replaces) candidate else selected
  }
}
