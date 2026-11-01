package com.betterhv.transfer.core.v4.discovery

enum class PeerProtocol { UNKNOWN, V4, V3 }

data class PeerRecord(
  val peerDeviceId: String,
  val bleAddress: String,
  val protocol: PeerProtocol = PeerProtocol.UNKNOWN,
  val serviceTableKnown: Boolean = false,
  val updatedAtMillis: Long = 0,
  val failures: Int = 0,
)

interface PeerAddressStore {
  fun load(): List<PeerRecord>
  fun save(records: List<PeerRecord>)
}

/**
 * Remembers the BLE address and negotiated protocol per paired peer.
 *
 * A successful lookup lets the client connect without scanning and skip the GATT cache refresh.
 * Consecutive failures invalidate the address (the peer may have changed its random address) while
 * keeping the last known protocol for diagnostics.
 */
class PeerAddressBook(
  private val store: PeerAddressStore,
  private val clock: () -> Long = System::currentTimeMillis,
  private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
  private val maxFailures: Int = DEFAULT_MAX_FAILURES,
) {
  private val records = LinkedHashMap<String, PeerRecord>()

  init {
    store.load().forEach { records[it.peerDeviceId] = it }
  }

  @Synchronized
  fun addressFor(peerDeviceId: String, nowMillis: Long = clock()): String? {
    val record = records[peerDeviceId] ?: return null
    if (record.bleAddress.isBlank() || record.failures >= maxFailures) return null
    if (nowMillis - record.updatedAtMillis > ttlMillis) return null
    return record.bleAddress
  }

  @Synchronized
  fun protocolFor(peerDeviceId: String): PeerProtocol =
    records[peerDeviceId]?.protocol ?: PeerProtocol.UNKNOWN

  @Synchronized
  fun serviceTableKnown(peerDeviceId: String): Boolean =
    records[peerDeviceId]?.serviceTableKnown == true

  @Synchronized
  fun recordSuccess(
    peerDeviceId: String,
    bleAddress: String,
    protocol: PeerProtocol,
    serviceTableKnown: Boolean = true,
    nowMillis: Long = clock(),
  ) {
    require(peerDeviceId.isNotBlank())
    require(bleAddress.isNotBlank())
    records[peerDeviceId] = PeerRecord(
      peerDeviceId = peerDeviceId,
      bleAddress = bleAddress,
      protocol = protocol,
      serviceTableKnown = serviceTableKnown,
      updatedAtMillis = nowMillis,
      failures = 0,
    )
    persist()
  }

  @Synchronized
  fun recordFailure(peerDeviceId: String, nowMillis: Long = clock()) {
    val current = records[peerDeviceId] ?: return
    val failures = current.failures + 1
    records[peerDeviceId] = current.copy(
      bleAddress = if (failures >= maxFailures) "" else current.bleAddress,
      failures = failures,
      updatedAtMillis = nowMillis,
    )
    persist()
  }

  @Synchronized
  fun forget(peerDeviceId: String) {
    if (records.remove(peerDeviceId) != null) persist()
  }

  @Synchronized
  fun snapshot(): List<PeerRecord> = records.values.toList()

  private fun persist() {
    store.save(records.values.toList())
  }

  companion object {
    const val DEFAULT_TTL_MILLIS = 30 * 60 * 1000L
    const val DEFAULT_MAX_FAILURES = 2
  }
}
