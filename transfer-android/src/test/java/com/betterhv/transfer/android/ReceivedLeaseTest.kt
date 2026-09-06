package com.betterhv.transfer.android

import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.QueueItem
import com.betterhv.transfer.core.RemotePayload
import com.betterhv.transfer.core.TransferOffer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ReceivedLeaseTest {
  @Test
  fun commitAndAwaitDoesNotBlockCallingDispatcher() = runBlocking {
    val committed = AtomicBoolean(false)
    val lease = testLease(onCommit = {
      Thread.sleep(400)
      committed.set(true)
    })

    val commit = async { lease.commitAndAwait() }
    delay(50)

    assertFalse(committed.get())
    assertTrue(commit.isActive)
    commit.await()
    assertTrue(committed.get())
  }

  private fun testLease(onCommit: () -> Unit): ReceivedLease {
    val item = QueueItem(
      id = UUID.randomUUID(),
      destinationDeviceId = null,
      kind = ContentKind.TEXT,
      mimeType = "text/plain",
      byteLength = 0,
      sha256 = ByteArray(32),
      createdAt = 0,
      position = 0,
    )
    return object : ReceivedLease {
      override val offer = TransferOffer(item)
      override val payload = RemotePayload.Text(item, "")
      override fun heartbeat() = Unit
      override fun markTransferring() = Unit
      override fun markAwaitingCommit() = Unit
      override fun commit() = onCommit()
      override fun release() = Unit
    }
  }
}
