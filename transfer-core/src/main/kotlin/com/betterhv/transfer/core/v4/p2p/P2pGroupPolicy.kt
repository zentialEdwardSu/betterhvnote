package com.betterhv.transfer.core.v4.p2p

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class P2pCredentials(val ssid: String, val passphrase: String) {
  init {
    require(ssid.length in 1..32) { "SSID must be 1..32 bytes" }
    require(passphrase.length in 8..63) { "Passphrase must be 8..63 characters" }
  }
}

/**
 * Shared Wi-Fi Direct group policy: reuse a warm group, release it after 5 min idle/background,
 * derive stable credentials from the pairing key, and bound host rebuild backoff to 250 ms–2 s.
 */
class P2pGroupPolicy(
  private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
  private val teardownDelayMillis: Long = DEFAULT_TEARDOWN_DELAY_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun shouldReuse(groupExists: Boolean, lastUsedAtMillis: Long, nowMillis: Long = clock()): Boolean =
    groupExists && nowMillis - lastUsedAtMillis < idleTimeoutMillis

  fun shouldRelease(lastUsedAtMillis: Long, foreground: Boolean, nowMillis: Long = clock()): Boolean =
    !foreground || nowMillis - lastUsedAtMillis >= idleTimeoutMillis

  /** No unconditional 750 ms wait: a fresh group only needs a short settle window. */
  fun teardownDelay(): Long = teardownDelayMillis

  fun reconnectDelay(attempt: Int): Long {
    require(attempt >= 0)
    return (250L shl attempt.coerceAtMost(4)).coerceAtMost(DEFAULT_MAX_BACKOFF_MILLIS)
  }

  fun credentials(pairingKey: ByteArray): P2pCredentials {
    require(pairingKey.isNotEmpty()) { "Pairing key is empty" }
    val material = hmac(pairingKey, "notelink/v4/p2p/credentials")
    val ssid = "DIRECT-BH-" + material.copyOfRange(0, 6).toHex()
    val passphrase = material.copyOfRange(6, 22).toHex()
    return P2pCredentials(ssid, passphrase)
  }

  private fun hmac(key: ByteArray, label: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(label.encodeToByteArray())
  }

  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

  companion object {
    const val DEFAULT_IDLE_TIMEOUT_MILLIS = 5 * 60 * 1000L
    const val DEFAULT_TEARDOWN_DELAY_MILLIS = 300L
    const val DEFAULT_MAX_BACKOFF_MILLIS = 2_000L
  }
}

enum class P2pBand { AUTO, BAND_5GHZ, BAND_24GHZ }

enum class TransportKind { LAN, WIFI_DIRECT }

data class TransportDecision(
  val kind: TransportKind,
  val reason: String,
  val probeBudgetMillis: Long = LAN_PROBE_BUDGET_MILLIS,
) {
  companion object {
    const val LAN_PROBE_BUDGET_MILLIS = 500L
  }
}

/**
 * Bounded transport choice: a warm P2P group or an SSID mismatch skips the LAN probe entirely;
 * otherwise LAN gets one 500 ms probe and a failure switches to P2P immediately (never 20 s).
 */
class TransportSelector(private val concurrentEnabled: Boolean = false) {
  fun choose(
    lanReachable: Boolean?,
    ssidMatch: Boolean,
    warmP2pGroup: Boolean,
    p2pConcurrentFeature: Boolean = false,
  ): TransportDecision {
    if (warmP2pGroup) return TransportDecision(TransportKind.WIFI_DIRECT, "warm-p2p-group")
    if (!ssidMatch) return TransportDecision(TransportKind.WIFI_DIRECT, "ssid-mismatch")
    if (lanReachable == true) return TransportDecision(TransportKind.LAN, "lan-probe-ok")
    if (concurrentEnabled && p2pConcurrentFeature) {
      return TransportDecision(
        TransportKind.LAN,
        "concurrent-race-started",
        TransportDecision.LAN_PROBE_BUDGET_MILLIS,
      )
    }
    return TransportDecision(TransportKind.WIFI_DIRECT, "lan-probe-timeout")
  }

  companion object {
    fun fingerprint(value: String): String =
      MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        .copyOf(8)
        .joinToString("") { "%02x".format(it) }
  }
}
