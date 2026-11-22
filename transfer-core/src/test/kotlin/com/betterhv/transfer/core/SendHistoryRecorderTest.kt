package com.betterhv.transfer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SendHistoryRecorderTest {
  private var now = 1_000_000L
  private val store = InMemorySendHistoryStore()
  private val recorder = SendHistoryRecorder(store, clock = { now })

  @Test fun `records one row per queue item`() {
    val item = item()

    val first = recorder.record(item, "N10Pro", SendOutcome.SUCCEEDED, text = "hello")
    val second = recorder.record(item, "N10Pro", SendOutcome.SUCCEEDED, text = "hello")

    assertEquals(first.id, second.id)
    assertEquals(1, store.entries().size)
    assertEquals(SendOutcome.SUCCEEDED, first.outcome)
    assertEquals("hello", first.text)
    assertEquals("N10Pro", first.destinationName)
  }

  @Test fun `records failure with a bounded reason and no payload`() {
    val item = item()

    val entry = recorder.record(item, "N10Pro", SendOutcome.FAILED, failureReason = "x".repeat(2_000))

    assertEquals(SendOutcome.FAILED, entry.outcome)
    assertEquals(512, entry.failureReason?.length ?: 0)
    assertNull(entry.payloadPath)
  }

  @Test fun `retention deletes rows beyond the entry limit`() {
    val limited = SendHistoryRecorder(
      store,
      clock = { now },
      policy = HistoryRetentionPolicy(maxEntries = 2),
    )
    val oldest = limited.record(item(position = 0), "N10Pro", SendOutcome.SUCCEEDED)
    now += 10
    limited.record(item(position = 1), "N10Pro", SendOutcome.SUCCEEDED)
    now += 10
    limited.record(item(position = 2), "N10Pro", SendOutcome.SUCCEEDED)

    val plan = limited.retentionPlan(now)

    assertEquals(listOf(oldest.id), plan.deleteEntries.map(SendHistoryEntry::id))
    assertTrue(plan.clearPayloads.isEmpty())
  }

  @Test fun `retention clears payloads past the age limit`() {
    val limited = SendHistoryRecorder(
      store,
      clock = { now },
      policy = HistoryRetentionPolicy(payloadMaxAgeMillis = 1_000),
    )
    val entry = limited.record(item(), "N10Pro", SendOutcome.SUCCEEDED)
    limited.attachPayload(entry, "history/old.bin")
    now += 5_000

    val plan = limited.retentionPlan(now)

    assertEquals(listOf(entry.id), plan.clearPayloads.map(SendHistoryEntry::id))
    assertEquals(1, plan.payloadFilesToDelete.size)
    assertTrue(plan.deleteEntries.isEmpty())
  }

  @Test fun `retention enforces the payload byte budget oldest first`() {
    val limited = SendHistoryRecorder(
      store,
      clock = { now },
      policy = HistoryRetentionPolicy(maxPayloadBytes = 100),
    )
    val oldest = limited.record(item(byteLength = 80), "N10Pro", SendOutcome.SUCCEEDED)
    limited.attachPayload(oldest, "history/oldest.bin")
    now += 10
    val newest = limited.record(item(byteLength = 80), "N10Pro", SendOutcome.SUCCEEDED)
    limited.attachPayload(newest, "history/newest.bin")

    val plan = limited.retentionPlan(now)

    assertEquals(listOf(oldest.id), plan.clearPayloads.map(SendHistoryEntry::id))
    assertEquals(listOf("history/oldest.bin"), plan.payloadFilesToDelete.mapNotNull(SendHistoryEntry::payloadPath))
  }

  @Test fun `retention ignores entries without payloads`() {
    val entry = recorder.record(item(), "N10Pro", SendOutcome.FAILED, failureReason = "peer gone")

    val plan = recorder.retentionPlan(now + 10_000_000_000)

    assertTrue(plan.isEmpty)
    assertNull(store.find(entry.id)?.payloadPath)
  }

  @Test fun `requeue uses the retained payload and original device`() {
    val entry = recorder.record(item(device = "note-a"), "N10Pro", SendOutcome.SUCCEEDED)
    recorder.attachPayload(entry, "history/keep.bin")
    val stored = requireNotNull(store.find(entry.id))

    val decision = recorder.requeueDecision(
      stored,
      payloadExists = true,
      pairedDeviceIds = setOf("note-a"),
      currentDeviceId = "note-b",
    )

    val plan = (decision as RequeueDecision.Ready).plan
    assertEquals(RequeueSource.Payload("history/keep.bin"), plan.source)
    assertEquals("note-a", plan.destinationDeviceId)
  }

  @Test fun `requeue falls back to the current device and supports text`() {
    val textEntry = recorder.record(
      item(kind = ContentKind.TEXT, text = "notes"),
      "N10Pro",
      SendOutcome.SUCCEEDED,
      text = "notes",
    )
    val payloadEntry = recorder.record(item(kind = ContentKind.IMAGE), "N10Pro", SendOutcome.SUCCEEDED)
    recorder.attachPayload(payloadEntry, "history/image.png")
    val storedPayload = requireNotNull(store.find(payloadEntry.id))

    val textDecision = recorder.requeueDecision(
      textEntry,
      payloadExists = false,
      pairedDeviceIds = emptySet(),
      currentDeviceId = "note-b",
    )
    val payloadDecision = recorder.requeueDecision(
      storedPayload,
      payloadExists = true,
      pairedDeviceIds = emptySet(),
      currentDeviceId = "note-b",
    )

    assertEquals(RequeueSource.Text("notes"), (textDecision as RequeueDecision.Ready).plan.source)
    assertEquals("note-b", (payloadDecision as RequeueDecision.Ready).plan.destinationDeviceId)
  }

  @Test fun `requeue fails when the payload was pruned`() {
    val entry = recorder.record(item(device = "note-a"), "N10Pro", SendOutcome.SUCCEEDED)
    recorder.attachPayload(entry, "history/pruned.bin")
    val stored = requireNotNull(store.find(entry.id))

    val pruned = recorder.requeueDecision(
      stored,
      payloadExists = false,
      pairedDeviceIds = setOf("note-a"),
      currentDeviceId = "note-a",
    )
    val noPayloadNoText = recorder.requeueDecision(
      stored.copy(payloadPath = null),
      payloadExists = true,
      pairedDeviceIds = emptySet(),
      currentDeviceId = null,
    )

    assertEquals(RequeueDecision.PayloadUnavailable, pruned)
    assertEquals(RequeueDecision.PayloadUnavailable, noPayloadNoText)
  }

  @Test fun `requeue rejects app packages even when a payload path exists`() {
    val entry = recorder.record(item(kind = ContentKind.APP_PACKAGE), "N10Pro", SendOutcome.SUCCEEDED)
    recorder.attachPayload(entry, "history/app.apk")
    val stored = requireNotNull(store.find(entry.id))

    val decision = recorder.requeueDecision(
      stored,
      payloadExists = true,
      pairedDeviceIds = setOf("note-a"),
      currentDeviceId = "note-a",
    )

    assertEquals(RequeueDecision.PayloadUnavailable, decision)
  }

  @Test fun `reconcile completes interrupted commits and reports orphans`() {
    val completedItem = item(position = 0)
    val pendingItem = item(position = 1)
    val entry = recorder.record(completedItem, "N10Pro", SendOutcome.SUCCEEDED)
    recorder.attachPayload(entry, "history/${entry.id}.bin")
    val orphan = UUID.randomUUID()

    val plan = recorder.reconcile(
      queueItems = listOf(completedItem, pendingItem),
      queuePayloadPresent = { it.id != completedItem.id },
      history = store.entries(),
      missingHistoryPayloadIds = emptyList(),
      orphanPayloadIds = listOf(orphan),
    )

    assertEquals(listOf(completedItem.id), plan.completedQueueItemIds)
    assertEquals(listOf(orphan), plan.orphanPayloadIds)
    assertTrue(plan.prunedHistoryIds.isEmpty())
  }

  @Test fun `reconcile prunes history rows whose payload disappeared`() {
    val entry = recorder.record(item(), "N10Pro", SendOutcome.SUCCEEDED)
    recorder.attachPayload(entry, "history/lost.bin")

    val plan = recorder.reconcile(
      queueItems = emptyList(),
      queuePayloadPresent = { true },
      history = store.entries(),
      missingHistoryPayloadIds = listOf(entry.id),
      orphanPayloadIds = emptyList(),
    )

    assertEquals(listOf(entry.id), plan.prunedHistoryIds)
  }

  private fun item(
    position: Long = 0,
    kind: ContentKind = ContentKind.IMAGE,
    device: String? = "note-a",
    byteLength: Long = 1,
    text: String? = null,
  ) = QueueItem(
    id = UUID.randomUUID(),
    destinationDeviceId = device,
    kind = kind,
    mimeType = if (kind == ContentKind.IMAGE) "image/png" else "text/plain; charset=utf-8",
    byteLength = if (text == null) byteLength else text.encodeToByteArray().size.toLong(),
    sha256 = ByteArray(QueueItem.SHA256_BYTES),
    createdAt = position,
    position = position,
    displayName = text ?: "item-$position",
  )
}
