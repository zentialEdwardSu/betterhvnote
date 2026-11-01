package com.betterhv.transfer.core.v4.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pGroupPolicyTest {
  private var now = 10_000_000L
  private val policy = P2pGroupPolicy(clock = { now })
  private val key = ByteArray(32) { (it * 7 + 1).toByte() }

  @Test
  fun warmGroupIsReusedAndExpiresAfterFiveMinutes() {
    assertTrue(policy.shouldReuse(groupExists = true, lastUsedAtMillis = now - 1_000))
    assertFalse(policy.shouldReuse(groupExists = false, lastUsedAtMillis = now))
    assertFalse(policy.shouldReuse(groupExists = true, lastUsedAtMillis = now - 5 * 60_000))
  }

  @Test
  fun backgroundOrIdleReleasesTheGroup() {
    assertTrue(policy.shouldRelease(now, foreground = false))
    assertTrue(policy.shouldRelease(now - 5 * 60_000, foreground = true))
    assertFalse(policy.shouldRelease(now - 1_000, foreground = true))
  }

  @Test
  fun credentialsAreStableBoundedAndKeyDependent() {
    val first = policy.credentials(key)
    val second = policy.credentials(key)
    assertEquals(first, second)
    assertTrue(first.ssid.length <= 32)
    assertTrue(first.passphrase.length in 8..63)
    assertTrue(first.ssid.startsWith("DIRECT-BH-"))
    assertFalse(first == policy.credentials(ByteArray(32) { (it + 1).toByte() }))
  }

  @Test
  fun backoffIsBoundedAndTeardownIsShort() {
    assertTrue(policy.teardownDelay() < 750)
    assertEquals(250, policy.reconnectDelay(0))
    assertEquals(500, policy.reconnectDelay(1))
    assertEquals(2_000, policy.reconnectDelay(10))
  }

  @Test
  fun transportChoicesAreBoundedAndExplainable() {
    val selector = TransportSelector(concurrentEnabled = false)
    assertEquals(TransportKind.WIFI_DIRECT, selector.choose(true, true, true).kind)
    assertEquals("warm-p2p-group", selector.choose(true, true, true).reason)
    assertEquals(TransportKind.WIFI_DIRECT, selector.choose(true, false, false).kind)
    assertEquals("lan-probe-ok", selector.choose(true, true, false).reason)
    assertEquals("lan-probe-timeout", selector.choose(false, true, false).reason)
    assertEquals("lan-probe-timeout", selector.choose(null, true, false).reason)
    assertEquals(500, TransportDecision.LAN_PROBE_BUDGET_MILLIS)
  }

  @Test
  fun concurrentRaceRequiresBothTheUserSwitchAndTheFeature() {
    val disabled = TransportSelector(concurrentEnabled = false)
    assertEquals(TransportKind.WIFI_DIRECT, disabled.choose(null, true, false, p2pConcurrentFeature = true).kind)
    val enabled = TransportSelector(concurrentEnabled = true)
    assertEquals("concurrent-race-started", enabled.choose(null, true, false, p2pConcurrentFeature = true).reason)
    assertEquals(TransportKind.WIFI_DIRECT, enabled.choose(null, true, false, p2pConcurrentFeature = false).kind)
  }
}
