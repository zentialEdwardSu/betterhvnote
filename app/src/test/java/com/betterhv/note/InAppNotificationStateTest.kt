package com.betterhv.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppNotificationStateTest {
  @Test fun durationModesExposeTimedAndPersistentBehavior() {
    assertEquals(4_000L, InAppNotificationDuration.SHORT.timeoutMillis)
    assertEquals(8_000L, InAppNotificationDuration.LONG.timeoutMillis)
    assertNull(InAppNotificationDuration.PERSISTENT.timeoutMillis)
  }

  @Test fun showDefaultsToShortNotification() {
    val state = InAppNotificationState()

    state.show("Saved")

    assertEquals("Saved", state.current?.title)
    assertEquals(InAppNotificationDuration.SHORT, state.current?.duration)
  }

  @Test fun staleTimerCannotDismissReplacementNotification() {
    val state = InAppNotificationState()
    val firstId = state.show("First")
    val secondId = state.show("Second", duration = InAppNotificationDuration.PERSISTENT)

    state.dismiss(firstId)

    assertEquals(secondId, state.current?.id)
    assertEquals("Second", state.current?.title)
  }

  @Test fun dismissAndActionInvokeTheirCallbacks() {
    val state = InAppNotificationState()
    var dismissed = false
    var actionPerformed = false
    val id = state.show(
      title = "Update available",
      actionLabel = "Open",
      onAction = { actionPerformed = true },
      onDismiss = { dismissed = true },
    )

    state.performAction(id)

    assertTrue(dismissed)
    assertTrue(actionPerformed)
    assertNull(state.current)
  }

  @Test fun replacingNotificationDismissesPreviousNotification() {
    val state = InAppNotificationState()
    var dismissed = false
    state.show("First", onDismiss = { dismissed = true })

    state.show("Second")

    assertTrue(dismissed)
  }
}
