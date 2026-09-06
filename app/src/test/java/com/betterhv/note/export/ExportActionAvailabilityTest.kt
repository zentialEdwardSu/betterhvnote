package com.betterhv.note.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportActionAvailabilityTest {
  @Test fun normalTaskOffersAllAvailableDestinationsAndDelete() {
    val actions = ExportActionAvailability.resolve(
      ExportTaskState.CURRENT,
      busy = false,
      phoneTransferAvailable = true,
    )
    assertTrue(actions.saveEnabled)
    assertTrue(actions.sendEnabled)
    assertTrue(actions.deleteEnabled)
  }

  @Test fun missingSourceOnlyAllowsDelete() {
    val actions = ExportActionAvailability.resolve(
      ExportTaskState.SOURCE_MISSING,
      busy = false,
      phoneTransferAvailable = true,
    )
    assertFalse(actions.saveEnabled)
    assertFalse(actions.sendEnabled)
    assertTrue(actions.deleteEnabled)
  }

  @Test fun busyStateDisablesEveryAction() {
    val actions = ExportActionAvailability.resolve(
      ExportTaskState.OUTDATED,
      busy = true,
      phoneTransferAvailable = true,
    )
    assertFalse(actions.saveEnabled)
    assertFalse(actions.sendEnabled)
    assertFalse(actions.deleteEnabled)
  }

  @Test fun unavailablePhoneOnlyDisablesSend() {
    val actions = ExportActionAvailability.resolve(
      ExportTaskState.NEVER_GENERATED,
      busy = false,
      phoneTransferAvailable = false,
    )
    assertTrue(actions.saveEnabled)
    assertFalse(actions.sendEnabled)
    assertTrue(actions.deleteEnabled)
  }
}
