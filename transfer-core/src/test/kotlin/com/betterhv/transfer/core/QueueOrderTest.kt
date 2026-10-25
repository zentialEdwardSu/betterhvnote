package com.betterhv.transfer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class QueueOrderTest {
  @Test fun `queue order leases the oldest pending item first`() {
    val store = InMemoryQueueStore()
    val items = listOf(item(ContentKind.IMAGE, 0), item(ContentKind.IMAGE, 1), item(ContentKind.IMAGE, 2))
    items.forEach(store::insert)
    val coordinator = QueueCoordinator(store)

    assertEquals(items[0].id, coordinator.leaseNext(ContentKind.IMAGE, "note")?.id)
    assertEquals(items[1].id, coordinator.leaseNext(ContentKind.IMAGE, "note")?.id)
    assertEquals(items[2].id, coordinator.leaseNext(ContentKind.IMAGE, "note")?.id)
    assertNull(coordinator.leaseNext(ContentKind.IMAGE, "note"))
  }

  @Test fun `stack order leases the newest pending item first`() {
    val store = InMemoryQueueStore()
    val items = listOf(item(ContentKind.IMAGE, 0), item(ContentKind.IMAGE, 1), item(ContentKind.IMAGE, 2))
    items.forEach(store::insert)
    val coordinator = QueueCoordinator(store, order = { SendOrder.STACK })

    assertEquals(items[2].id, coordinator.leaseNext(ContentKind.IMAGE, "note")?.id)
    assertEquals(items[1].id, coordinator.leaseNext(ContentKind.IMAGE, "note")?.id)
    assertEquals(items[0].id, coordinator.leaseNext(ContentKind.IMAGE, "note")?.id)
  }

  @Test fun `switching order applies from the next lease without releasing anything`() {
    val store = InMemoryQueueStore()
    val items = listOf(item(ContentKind.IMAGE, 0), item(ContentKind.IMAGE, 1), item(ContentKind.IMAGE, 2))
    items.forEach(store::insert)
    var order = SendOrder.QUEUE
    val coordinator = QueueCoordinator(store, order = { order })

    assertEquals(items[0].id, coordinator.leaseNext(ContentKind.IMAGE, "note")?.id)
    order = SendOrder.STACK
    assertEquals(items[2].id, coordinator.leaseNext(ContentKind.IMAGE, "note")?.id)
    assertEquals(items[1].id, coordinator.leaseNext(ContentKind.IMAGE, "note")?.id)
  }

  @Test fun `selection skips items for other devices and non pending states`() {
    val store = InMemoryQueueStore()
    val otherDevice = item(ContentKind.IMAGE, 0, device = "other")
    val leased = item(ContentKind.TEXT, 1, state = QueueState.LEASED)
    val available = item(ContentKind.IMAGE, 2)
    listOf(otherDevice, leased, available).forEach(store::insert)

    assertEquals(available.id, store.next(ContentKind.IMAGE, "note", NOW)?.id)
    assertNull(store.next(ContentKind.TEXT, "note", NOW))
  }

  @Test fun `selection stays inside the requested kind`() {
    val store = InMemoryQueueStore()
    val text = item(ContentKind.TEXT, 0)
    val image = item(ContentKind.IMAGE, 1)
    listOf(text, image).forEach(store::insert)

    assertEquals(image.id, store.next(ContentKind.IMAGE, "note", NOW)?.id)
    assertEquals(text.id, store.next(ContentKind.TEXT, "note", NOW)?.id)
  }

  @Test fun `equal position and creation time fall back to the id`() {
    val store = InMemoryQueueStore()
    val low = item(ContentKind.IMAGE, 5, createdAt = 100, id = UUID.fromString(LOW_ID))
    val high = item(ContentKind.IMAGE, 5, createdAt = 100, id = UUID.fromString(HIGH_ID))
    listOf(high, low).forEach(store::insert)

    assertEquals(low.id, store.next(ContentKind.IMAGE, "note", NOW, SendOrder.QUEUE)?.id)
    assertEquals(high.id, store.next(ContentKind.IMAGE, "note", NOW, SendOrder.STACK)?.id)
  }

  @Test fun `ordered helper mirrors the lease order`() {
    val items = listOf(item(ContentKind.IMAGE, 0), item(ContentKind.IMAGE, 1), item(ContentKind.IMAGE, 2))

    assertEquals(items.map(QueueItem::id), SendOrderSelector.ordered(items, SendOrder.QUEUE).map(QueueItem::id))
    assertEquals(
      items.map(QueueItem::id).reversed(),
      SendOrderSelector.ordered(items, SendOrder.STACK).map(QueueItem::id),
    )
  }

  private fun item(
    kind: ContentKind,
    position: Long,
    device: String? = null,
    state: QueueState = QueueState.PENDING,
    createdAt: Long = position,
    id: UUID = UUID.randomUUID(),
  ) = QueueItem(
    id = id,
    destinationDeviceId = device,
    kind = kind,
    mimeType = if (kind == ContentKind.IMAGE) "image/png" else "text/plain",
    byteLength = 1,
    sha256 = ByteArray(QueueItem.SHA256_BYTES),
    createdAt = createdAt,
    position = position,
    state = state,
  )

  private companion object {
    const val NOW = 1_000L
    const val LOW_ID = "00000000-0000-0000-0000-000000000001"
    const val HIGH_ID = "00000000-0000-0000-0000-0000000000ff"
  }
}
