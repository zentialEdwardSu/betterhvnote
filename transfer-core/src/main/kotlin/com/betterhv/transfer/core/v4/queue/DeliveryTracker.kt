package com.betterhv.transfer.core.v4.queue

/** Per-target delivery of one immutable content item (the fan-out half of a queue item). */
data class Delivery(
  val contentId: String,
  val peerDeviceId: String,
  val state: DeliveryState = DeliveryState.PENDING,
  val failure: String? = null,
  val updatedAtMillis: Long = 0,
)

enum class DeliveryState { PENDING, COMMITTED, FAILED }

/**
 * Tracks one delivery per (content, peer). Planning is idempotent, a failure only affects its own
 * target, and retrying a failed target does not touch already committed ones.
 */
class DeliveryTracker(private val clock: () -> Long = System::currentTimeMillis) {
  private val deliveries = LinkedHashMap<Pair<String, String>, Delivery>()

  @Synchronized
  fun plan(contentId: String, peerDeviceIds: Collection<String>): List<Delivery> {
    require(contentId.isNotBlank())
    return peerDeviceIds.distinct().map { peer ->
      deliveries.getOrPut(contentId to peer) {
        Delivery(contentId, peer, DeliveryState.PENDING, updatedAtMillis = clock())
      }
    }
  }

  @Synchronized
  fun markCommitted(contentId: String, peerDeviceId: String): Delivery = update(contentId, peerDeviceId) {
    it.copy(state = DeliveryState.COMMITTED, failure = null, updatedAtMillis = clock())
  }

  @Synchronized
  fun markFailed(contentId: String, peerDeviceId: String, reason: String): Delivery = update(contentId, peerDeviceId) {
    it.copy(state = DeliveryState.FAILED, failure = reason, updatedAtMillis = clock())
  }

  @Synchronized
  fun retry(contentId: String, peerDeviceId: String): Delivery = update(contentId, peerDeviceId) {
    it.copy(state = DeliveryState.PENDING, failure = null, updatedAtMillis = clock())
  }

  @Synchronized
  fun byContent(contentId: String): List<Delivery> =
    deliveries.values.filter { it.contentId == contentId }

  @Synchronized
  fun pending(contentId: String): List<Delivery> =
    byContent(contentId).filter { it.state == DeliveryState.PENDING }

  @Synchronized
  fun isComplete(contentId: String): Boolean =
    byContent(contentId).isNotEmpty() && byContent(contentId).all { it.state == DeliveryState.COMMITTED }

  @Synchronized
  fun forget(contentId: String) {
    deliveries.keys.removeIf { it.first == contentId }
  }

  private fun update(contentId: String, peerDeviceId: String, change: (Delivery) -> Delivery): Delivery {
    val key = contentId to peerDeviceId
    val current = deliveries[key] ?: throw IllegalArgumentException("Unknown delivery $contentId/$peerDeviceId")
    val updated = change(current)
    deliveries[key] = updated
    return updated
  }
}
