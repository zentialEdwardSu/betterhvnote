package com.betterhv.note

import org.junit.Assert.assertEquals
import org.junit.Test

class InsertionEntryPolicyTest {
  @Test fun disabledSettingAlwaysChoosesSource() {
    assertEquals(InsertionStartDecision.CHOOSE_SOURCE, insertionStartDecision(false, true))
    assertEquals(InsertionStartDecision.CHOOSE_SOURCE, insertionStartDecision(false, false))
  }

  @Test fun enabledSettingDiscoversOrRequestsPermission() {
    assertEquals(InsertionStartDecision.DISCOVER_REMOTE, insertionStartDecision(true, true))
    assertEquals(InsertionStartDecision.REQUEST_PERMISSIONS, insertionStartDecision(true, false))
  }

  @Test fun discoveryResultSelectsTheExpectedNextStep() {
    assertEquals(AutomaticRemoteDecision.CHOOSE_SOURCE, automaticRemoteDecision(0))
    assertEquals(AutomaticRemoteDecision.PULL_SINGLE, automaticRemoteDecision(1))
    assertEquals(AutomaticRemoteDecision.CHOOSE_CLIENT, automaticRemoteDecision(2))
  }
}
