package com.betterhv.transfer.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.betterhv.transfer.core.TransferCrypto
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class BleReceiverScanner(context: Context) {
    private val manager = context.applicationContext.getSystemService(BluetoothManager::class.java)

    @SuppressLint("MissingPermission")
    suspend fun discover(timeoutMillis: Long = 20_000L): List<DiscoveredSender> {
        val scanner = manager.adapter?.bluetoothLeScanner ?: error("Bluetooth is disabled")
        val found = ConcurrentHashMap<String, DiscoveredSender>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { result.toSender()?.let { found[it.bluetoothAddress] = it } }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { it.toSender()?.let { sender -> found[sender.bluetoothAddress] = sender } } }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        try { delay(timeoutMillis.coerceIn(1_000L, 60_000L)) } finally { scanner.stopScan(callback) }
        return found.values.sortedBy { it.name }
    }

    /** Stops the radio scan as soon as a usable sender is observed. */
    @SuppressLint("MissingPermission")
    suspend fun discoverFirst(
        timeoutMillis: Long = 20_000L,
        matches: (DiscoveredSender) -> Boolean = { true }
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
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        return try {
            withTimeoutOrNull(timeoutMillis.coerceIn(1_000L, 60_000L)) { found.await() }
        } finally {
            scanner.stopScan(callback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toSender(): DiscoveredSender? {
        val data = scanRecord?.getManufacturerSpecificData(0x0B17) ?: return null
        if (data.size < 8 || data[0].toInt() != BleConstants.PROTOCOL_VERSION) return null
        val hash = data.copyOfRange(3, 8).joinToString("") { "%02x".format(it) }
        val advertisedName = data.takeIf { it.size > 8 }
            ?.copyOfRange(8, data.size)?.decodeToString()?.takeIf(String::isNotBlank)
        return DiscoveredSender(
            device.address, hash, advertisedName ?: device.name ?: scanRecord?.deviceName ?: "NoteLink",
            data[1].toInt() and 0xff, data[2].toInt() and 0xff
        )
    }
}
