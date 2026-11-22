package com.betterhv.note.notelink

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeferredCompletionTest {
  @Test
  fun completedValueOrNullReturnsValueOnlyOnSuccessfulCompletion() {
    val incomplete = CompletableDeferred<String>()
    assertNull(incomplete.completedValueOrNull())

    val succeeded = CompletableDeferred<String>().apply { complete("lan-link") }
    assertEquals("lan-link", succeeded.completedValueOrNull())

    val cancelled = CompletableDeferred<String>().apply { cancel() }
    assertNull(cancelled.completedValueOrNull())

    val failed = CompletableDeferred<String>().apply {
      completeExceptionally(IllegalStateException("connect failed"))
    }
    assertNull(failed.completedValueOrNull())
  }
}
