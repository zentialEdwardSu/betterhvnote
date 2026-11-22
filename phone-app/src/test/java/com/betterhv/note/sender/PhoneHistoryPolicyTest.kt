package com.betterhv.note.sender

import com.betterhv.note.sender.data.PhoneHistoryPolicy
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.SendHistoryEntry
import com.betterhv.transfer.core.SendOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PhoneHistoryPolicyTest {
  @Test
  fun appPackageDoesNotRetainPayloadAndCannotBeResent() {
    assertFalse(PhoneHistoryPolicy.shouldRetainPayload(ContentKind.APP_PACKAGE))
    assertTrue(PhoneHistoryPolicy.shouldRetainPayload(ContentKind.IMAGE))
    assertTrue(PhoneHistoryPolicy.shouldRetainPayload(ContentKind.PDF))
    assertTrue(PhoneHistoryPolicy.shouldRetainPayload(ContentKind.TEXT))

    val appPackageEntry = SendHistoryEntry(
      id = UUID.randomUUID(),
      queueItemId = UUID.randomUUID(),
      destinationDeviceId = "note-1",
      destinationName = "Note",
      kind = ContentKind.APP_PACKAGE,
      mimeType = "application/vnd.android.package-archive",
      byteLength = 1024L,
      sha256 = ByteArray(32),
      displayName = "BetterHvNote.apk",
      completedAt = 1_000L,
      outcome = SendOutcome.SUCCEEDED,
      payloadPath = "history/fake.bin",
    )
    assertFalse(PhoneHistoryPolicy.isResendable(appPackageEntry) { true })
  }

  @Test
  fun textAndExistingPayloadEntriesAreResendable() {
    val textEntry = SendHistoryEntry(
      id = UUID.randomUUID(),
      queueItemId = UUID.randomUUID(),
      destinationDeviceId = "note-1",
      destinationName = "Note",
      kind = ContentKind.TEXT,
      mimeType = "text/plain; charset=utf-8",
      byteLength = 5L,
      sha256 = ByteArray(32),
      displayName = "hello",
      completedAt = 1_000L,
      outcome = SendOutcome.SUCCEEDED,
      text = "hello",
    )
    val imageEntry = SendHistoryEntry(
      id = UUID.randomUUID(),
      queueItemId = UUID.randomUUID(),
      destinationDeviceId = "note-1",
      destinationName = "Note",
      kind = ContentKind.IMAGE,
      mimeType = "image/png",
      byteLength = 100L,
      sha256 = ByteArray(32),
      displayName = "img.png",
      completedAt = 1_000L,
      outcome = SendOutcome.SUCCEEDED,
      payloadPath = "history/img.bin",
    )

    assertTrue(PhoneHistoryPolicy.isResendable(textEntry) { false })
    assertTrue(PhoneHistoryPolicy.isResendable(imageEntry) { true })
    assertFalse(PhoneHistoryPolicy.isResendable(imageEntry) { false })
  }
}
