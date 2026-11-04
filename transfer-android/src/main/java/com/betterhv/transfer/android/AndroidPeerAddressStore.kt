package com.betterhv.transfer.android

import android.content.Context
import androidx.core.content.edit
import com.betterhv.transfer.core.v4.discovery.PeerAddressStore
import com.betterhv.transfer.core.v4.discovery.PeerProtocol
import com.betterhv.transfer.core.v4.discovery.PeerRecord

/**
 * SharedPreferences-backed address book. Each peer is one entry whose value is
 * `address|protocol|serviceTableKnown|updatedAt|failures`.
 */
class AndroidPeerAddressStore(context: Context) : PeerAddressStore {
  private val preferences = context.applicationContext
    .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  override fun load(): List<PeerRecord> = preferences.all.mapNotNull { (key, value) ->
    if (!key.startsWith(PREFIX) || value !is String) return@mapNotNull null
    decode(key.removePrefix(PREFIX), value)
  }

  override fun save(records: List<PeerRecord>) {
    preferences.edit {
      clear()
      records.forEach { record ->
        putString(PREFIX + record.peerDeviceId, encode(record))
      }
    }
  }

  private fun encode(record: PeerRecord): String = listOf(
    record.bleAddress,
    record.protocol.name,
    record.serviceTableKnown.toString(),
    record.updatedAtMillis.toString(),
    record.failures.toString(),
  ).joinToString("|")

  private fun decode(peerDeviceId: String, value: String): PeerRecord? {
    val parts = value.split('|')
    if (parts.size != 5) return null
    val protocol = runCatching { PeerProtocol.valueOf(parts[1]) }.getOrDefault(PeerProtocol.UNKNOWN)
    return PeerRecord(
      peerDeviceId = peerDeviceId,
      bleAddress = parts[0],
      protocol = protocol,
      serviceTableKnown = parts[2].toBooleanStrictOrNull() ?: false,
      updatedAtMillis = parts[3].toLongOrNull() ?: 0,
      failures = parts[4].toIntOrNull() ?: 0,
    )
  }

  private companion object {
    const val PREFERENCES = "notelink_peer_address_book"
    const val PREFIX = "peer:"
  }
}
