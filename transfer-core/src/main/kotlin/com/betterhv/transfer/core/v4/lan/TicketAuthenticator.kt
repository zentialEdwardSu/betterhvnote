package com.betterhv.transfer.core.v4.lan

import com.betterhv.transfer.core.v4.session.PairingKeyStore
import com.betterhv.transfer.proto.v4.SessionTicket

/**
 * Validates a sealed LAN ticket and enforces single use of its connection nonce.
 *
 * The ticket is only accepted for the paired peer it names and for the listener it was issued by;
 * expiry is evaluated against the local clock. Failures return null so callers can close the
 * socket without leaking which check failed.
 */
class TicketAuthenticator(
  private val pairings: PairingKeyStore,
  private val clock: () -> Long = System::currentTimeMillis,
  private val nonceCapacity: Int = 128,
  private val sessionActive: (ByteArray) -> Boolean = { true },
) {
  init {
    require(nonceCapacity > 0)
  }

  private val usedNonces = object : LinkedHashMap<String, Long>(nonceCapacity, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
      size > nonceCapacity
  }

  @Synchronized
  fun authenticate(envelope: ByteArray, listenerDeviceId: String): SessionTicket? {
    val keyId = SessionTicketCodec.keyId(envelope) ?: return null
    val pairing = pairings.resolve(keyId) ?: return null
    val ticket = runCatching { SessionTicketCodec.open(pairing.psk, envelope) }.getOrNull() ?: return null
    if (ticket.expiresAtMs <= clock()) return null
    if (ticket.listenerDeviceId != listenerDeviceId) return null
    if (ticket.peerDeviceId != pairing.deviceId) return null
    if (!sessionActive(ticket.sessionId.toByteArray())) return null
    val nonce = ticket.connectionNonce.toByteArray()
    if (nonce.size != NONCE_BYTES) return null
    val key = nonce.joinToString("") { "%02x".format(it) }
    if (usedNonces.put(key, clock()) != null) return null
    return ticket
  }

  @Synchronized
  fun reset() {
    usedNonces.clear()
  }

  private companion object {
    const val NONCE_BYTES = 16
  }
}
