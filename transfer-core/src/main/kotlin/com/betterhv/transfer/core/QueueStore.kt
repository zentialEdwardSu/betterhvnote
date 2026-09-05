package com.betterhv.transfer.core

import java.util.UUID

interface QueueStore {
    fun items(): List<QueueItem>
    fun find(id: UUID): QueueItem?
    fun next(kind: ContentKind, destinationDeviceId: String?, now: Long): QueueItem?
    fun insert(item: QueueItem)
    fun update(item: QueueItem)
    fun remove(id: UUID): Boolean
    fun reorder(idsInOrder: List<UUID>)
    fun releaseExpired(now: Long): Int
}

/** Thread-safe reference implementation used by protocol tests and small hosts. */
class InMemoryQueueStore : QueueStore {
    private val values = LinkedHashMap<UUID, QueueItem>()

    @Synchronized override fun items(): List<QueueItem> =
        values.values.sortedWith(compareBy<QueueItem> { it.position }.thenBy { it.createdAt })

    @Synchronized override fun find(id: UUID): QueueItem? = values[id]

    @Synchronized override fun next(kind: ContentKind, destinationDeviceId: String?, now: Long): QueueItem? {
        releaseExpired(now)
        return items().firstOrNull {
            it.kind == kind && it.state == QueueState.PENDING &&
                (it.destinationDeviceId == null || it.destinationDeviceId == destinationDeviceId)
        }
    }

    @Synchronized override fun insert(item: QueueItem) {
        require(item.id !in values) { "Duplicate queue item ${item.id}" }
        values[item.id] = item
    }

    @Synchronized override fun update(item: QueueItem) {
        require(item.id in values) { "Unknown queue item ${item.id}" }
        values[item.id] = item
    }

    @Synchronized override fun remove(id: UUID): Boolean = values.remove(id) != null

    @Synchronized override fun reorder(idsInOrder: List<UUID>) {
        require(idsInOrder.toSet() == values.keys) { "Reorder must contain every item exactly once" }
        idsInOrder.forEachIndexed { index, id ->
            values[id] = values.getValue(id).copy(position = index.toLong())
        }
    }

    @Synchronized override fun releaseExpired(now: Long): Int {
        var released = 0
        values.replaceAll { _, value ->
            if (value.state in LEASED_STATES && value.leaseExpiresAt != null && value.leaseExpiresAt <= now) {
                released++
                value.copy(state = QueueState.PENDING, leaseExpiresAt = null, failureReason = null)
            } else value
        }
        return released
    }

    private companion object {
        val LEASED_STATES = setOf(QueueState.LEASED, QueueState.TRANSFERRING, QueueState.AWAITING_COMMIT)
    }
}

class QueueCoordinator(
    private val store: QueueStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Synchronized fun leaseNext(kind: ContentKind, deviceId: String): QueueItem? {
        val now = clock()
        val item = store.next(kind, deviceId, now) ?: return null
        return item.copy(
            destinationDeviceId = item.destinationDeviceId ?: deviceId,
            state = QueueState.LEASED,
            leaseExpiresAt = now + TransferLimits.LEASE_TIMEOUT_MILLIS,
            failureReason = null
        ).also(store::update)
    }

    @Synchronized fun transition(id: UUID, state: QueueState): QueueItem {
        val current = requireNotNull(store.find(id)) { "Unknown queue item $id" }
        require(state in allowedTransitions.getValue(current.state)) {
            "Invalid queue transition ${current.state} -> $state"
        }
        val leased = state in leasedStates
        return current.copy(
            state = state,
            leaseExpiresAt = if (leased) clock() + TransferLimits.LEASE_TIMEOUT_MILLIS else null,
            failureReason = null
        ).also(store::update)
    }

    @Synchronized fun heartbeat(id: UUID): QueueItem {
        val current = requireNotNull(store.find(id)) { "Unknown queue item $id" }
        require(current.state in leasedStates) { "Cannot renew ${current.state}" }
        return current.copy(leaseExpiresAt = clock() + TransferLimits.LEASE_TIMEOUT_MILLIS)
            .also(store::update)
    }

    @Synchronized fun release(id: UUID): QueueItem {
        val current = requireNotNull(store.find(id)) { "Unknown queue item $id" }
        require(current.state in leasedStates || current.state == QueueState.FAILED)
        return current.copy(state = QueueState.PENDING, leaseExpiresAt = null, failureReason = null)
            .also(store::update)
    }

    @Synchronized fun fail(id: UUID, reason: String): QueueItem {
        val current = requireNotNull(store.find(id)) { "Unknown queue item $id" }
        return current.copy(state = QueueState.FAILED, leaseExpiresAt = null, failureReason = reason)
            .also(store::update)
    }

    @Synchronized fun commit(id: UUID): Boolean = store.remove(id)

    private companion object {
        val leasedStates = setOf(QueueState.LEASED, QueueState.TRANSFERRING, QueueState.AWAITING_COMMIT)
        val allowedTransitions = mapOf(
            QueueState.PENDING to setOf(QueueState.LEASED),
            QueueState.LEASED to setOf(QueueState.TRANSFERRING, QueueState.AWAITING_COMMIT),
            QueueState.TRANSFERRING to setOf(QueueState.AWAITING_COMMIT),
            QueueState.AWAITING_COMMIT to emptySet(),
            QueueState.FAILED to setOf(QueueState.PENDING)
        )
    }
}

interface TransferLease : AutoCloseable {
    val offer: TransferOffer
    fun heartbeat()
    fun markTransferring()
    fun markAwaitingCommit()
    fun commit()
    fun release()
    override fun close() = release()
}

class QueueTransferLease(
    override val offer: TransferOffer,
    private val coordinator: QueueCoordinator
) : TransferLease {
    private var finished = false

    override fun heartbeat() { if (!finished) coordinator.heartbeat(offer.item.id) }
    override fun markTransferring() { if (!finished) coordinator.transition(offer.item.id, QueueState.TRANSFERRING) }
    override fun markAwaitingCommit() { if (!finished) coordinator.transition(offer.item.id, QueueState.AWAITING_COMMIT) }
    override fun commit() { if (!finished) { coordinator.commit(offer.item.id); finished = true } }
    override fun release() { if (!finished) { coordinator.release(offer.item.id); finished = true } }
}
