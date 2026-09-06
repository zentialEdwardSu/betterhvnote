package com.betterhv.transfer.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.betterhv.transfer.core.NoteLinkAdvertisementCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class BleReceiverScanner(context: Context) {
  private val manager = context.applicationContext.getSystemService(BluetoothManager::class.java)

  @SuppressLint("MissingPermission")
  suspend fun discover(
    timeoutMillis: Long = 20_000L,
    settleAfterFirstMillis: Long = 600L,
    onUpdate: (List<DiscoveredSender>) -> Unit = {},
  ): List<DiscoveredSender> {
    val scanner = manager.adapter?.bluetoothLeScanner ?: error("Bluetooth is disabled")
    val found = ConcurrentHashMap<String, DiscoveredSender>()
    val changed = Channel<Unit>(Channel.CONFLATED)
    val callback = object : ScanCallback() {
      private fun accept(result: ScanResult) {
        val sender = result.toSender() ?: return
        var contentChanged = false
        found.compute(sender.bluetoothAddress) { _, previous ->
          val merged = previous?.let { mergeDiscoveredSender(it, sender) } ?: sender
          contentChanged = previous != merged
          merged
        }
        if (contentChanged) changed.trySend(Unit)
      }

      override fun onScanResult(callbackType: Int, result: ScanResult) {
        accept(result)
      }
      override fun onBatchScanResults(results: MutableList<ScanResult>) {
        results.forEach(::accept)
      }
      override fun onScanFailed(errorCode: Int) {
        changed.close(IllegalStateException("BLE scan failed: $errorCode"))
      }
    }
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    // Do application-level filtering. Some Android BLE controllers do not
    // merge scan responses when hardware ScanFilter entries are present.
    scanner.startScan(null, settings, callback)
    val deadline = System.nanoTime() + timeoutMillis.coerceIn(1_000L, 60_000L) * 1_000_000L
    try {
      while (true) {
        val remainingMillis = ((deadline - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L)
        if (remainingMillis <= 0L) break
        val waitMillis = if (found.isEmpty()) {
          remainingMillis
        } else {
          minOf(
            remainingMillis,
            settleAfterFirstMillis.coerceIn(150L, 2_000L),
          )
        }
        val received = withTimeoutOrNull(waitMillis.coerceAtLeast(1L)) { changed.receiveCatching() }
          ?: break
        received.getOrThrow()
        onUpdate(found.values.sortedBy { it.name })
      }
    } finally {
      scanner.stopScan(callback)
      changed.close()
    }
    return found.values.sortedBy { it.name }.also(onUpdate)
  }

  /** Stops the radio scan as soon as a usable sender is observed. */
  @SuppressLint("MissingPermission")
  suspend fun discoverFirst(
    timeoutMillis: Long = 20_000L,
    matches: (DiscoveredSender) -> Boolean = { true },
  ): DiscoveredSender? {
    val scanner = manager.adapter?.bluetoothLeScanner ?: error("Bluetooth is disabled")
    val found = CompletableDeferred<DiscoveredSender>()
    val callback = object : ScanCallback() {
      private fun accept(result: ScanResult) {
        result.toSender()?.takeIf(matches)?.let { found.complete(it) }
      }

      override fun onScanResult(callbackType: Int, result: ScanResult) = accept(result)
      override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::accept)
      override fun onScanFailed(errorCode: Int) {
        found.completeExceptionally(IllegalStateException("BLE scan failed: $errorCode"))
      }
    }
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    scanner.startScan(null, settings, callback)
    return try {
      withTimeoutOrNull(timeoutMillis.coerceIn(1_000L, 60_000L)) { found.await() }
    } finally {
      scanner.stopScan(callback)
    }
  }

  @SuppressLint("MissingPermission")
  private fun ScanResult.toSender(): DiscoveredSender? {
    val record = scanRecord ?: return null
    val service = ParcelUuid(BleConstants.SERVICE_UUID)
    val serviceData = record.getServiceData(service)
    val hasNoteLinkService = record.serviceUuids?.contains(service) == true || serviceData != null
    // The Windows implementation publishes queue metadata separately from
    // its connectable GATT provider. That metadata has a different radio
    // address and must never be used as a GATT connection target.
    // Require the NoteLink service UUID whenever manufacturer data is used
    // so we only retain an address that actually hosts the GATT service.
    val manufacturerData = record.getManufacturerSpecificData(NoteLinkAdvertisementCodec.MANUFACTURER_ID)
    if (!hasNoteLinkService && manufacturerData != null) return null
    val data = manufacturerData ?: serviceData
    if (data == null) {
      if (!hasNoteLinkService) return null
      return DiscoveredSender(device.address, "", record.deviceName ?: "NoteLink", 0, 0, 0)
    }
    // Android phones use manufacturer data in the scan response. Windows
    // uses service data so queue discovery and the connectable GATT service
    // are guaranteed to come from one advertisement instance.
    val advertisement = runCatching { NoteLinkAdvertisementCodec.decode(data) }.getOrNull() ?: return null
    val hash = advertisement.identityHash.joinToString("") { "%02x".format(it) }
    return DiscoveredSender(
      device.address,
      hash,
      advertisement.deviceName,
      advertisement.imageCount,
      advertisement.textCount,
      advertisement.pdfCount,
    )
  }
}

internal fun mergeDiscoveredSender(previous: DiscoveredSender, update: DiscoveredSender): DiscoveredSender {
  if (update.identityHash.isNotBlank()) return update
  if (previous.identityHash.isNotBlank()) return previous
  return update.copy(
    name = update.name.takeUnless { it == "NoteLink" } ?: previous.name,
    imageCount = maxOf(previous.imageCount, update.imageCount),
    textCount = maxOf(previous.textCount, update.textCount),
    pdfCount = maxOf(previous.pdfCount, update.pdfCount),
  )
}
