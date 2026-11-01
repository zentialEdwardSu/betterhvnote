package com.betterhv.transfer.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.betterhv.transfer.core.ContentCounts
import com.betterhv.transfer.core.NoteLinkAdvertisementCodec
import com.betterhv.transfer.core.TransferCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** GATT-server side events for the NoteLink control characteristic. */
interface ControlPeripheralListener {
  fun onRecord(peerKey: String, record: ByteArray)
  fun onMtu(peerKey: String, mtu: Int)
  fun onDisconnected(peerKey: String)
}

/**
 * NoteLink BLE peripheral: one write-without-response + notify control characteristic.
 *
 * Advertising runs as one [AdvertisingSet], so queue-count changes are applied in place with
 * `setScanResponseData` instead of a stop/start cycle that hid the device for ~400 ms.
 * Notifications are sent one at a time per device, gated by `onNotificationSent`.
 */
class BleSenderPeripheral(
  context: Context,
  private val deviceId: String,
  private val deviceName: String,
  private val counts: () -> ContentCounts,
  private val listener: ControlPeripheralListener,
) : AutoCloseable {
  private val appContext = context.applicationContext
  private val manager = appContext.getSystemService(BluetoothManager::class.java)
  private val adapter: BluetoothAdapter get() = manager.adapter
  @Volatile private var server: BluetoothGattServer? = null
  @Volatile private var control: BluetoothGattCharacteristic? = null
  private val devices = ConcurrentHashMap<String, BluetoothDevice>()
  private val subscribed = ConcurrentHashMap.newKeySet<String>()
  private val notifyWaiters = ConcurrentHashMap<String, CompletableDeferred<Int>>()
  @Volatile private var advertisingSet: AdvertisingSet? = null
  @Volatile private var lastScanResponse: ByteArray? = null
  @Volatile private var pairingMode = false
  @Volatile private var closed = false

  private val callback = object : BluetoothGattServerCallback() {
    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        devices[device.address] = device
        Log.i(TAG, "central connected ${device.address}")
      } else {
        devices.remove(device.address)
        subscribed.remove(device.address)
        notifyWaiters.remove(device.address)?.complete(BluetoothGatt.GATT_FAILURE)
        Log.i(TAG, "central disconnected ${device.address} status=$status")
        listener.onDisconnected(device.address)
      }
    }

    override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
      Log.i(TAG, "mtu ${device.address} = $mtu")
      listener.onMtu(device.address, mtu)
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicWriteRequest(
      device: BluetoothDevice,
      requestId: Int,
      characteristic: BluetoothGattCharacteristic,
      preparedWrite: Boolean,
      responseNeeded: Boolean,
      offset: Int,
      value: ByteArray,
    ) {
      if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
      if (characteristic.uuid != BleConstants.CONTROL_UUID || preparedWrite || offset != 0) return
      devices[device.address] = device
      runCatching { listener.onRecord(device.address, value) }
        .onFailure { Log.w(TAG, "control record rejected", it) }
    }

    @SuppressLint("MissingPermission")
    override fun onDescriptorWriteRequest(
      device: BluetoothDevice,
      requestId: Int,
      descriptor: BluetoothGattDescriptor,
      preparedWrite: Boolean,
      responseNeeded: Boolean,
      offset: Int,
      value: ByteArray,
    ) {
      if (descriptor.uuid == BleConstants.CCCD_UUID) {
        if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
          subscribed += device.address
        } else {
          subscribed -= device.address
        }
      }
      if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
    }

    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
      notifyWaiters.remove(device.address)?.complete(status)
    }
  }

  @SuppressLint("MissingPermission")
  fun start() {
    check(adapter.isEnabled) { "Bluetooth is off" }
    val advertiser = adapter.bluetoothLeAdvertiser ?: error("BLE peripheral advertising is unsupported")
    closed = false
    val characteristic = BluetoothGattCharacteristic(
      BleConstants.CONTROL_UUID,
      BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
      BluetoothGattCharacteristic.PERMISSION_WRITE,
    ).also {
      it.addDescriptor(
        BluetoothGattDescriptor(
          BleConstants.CCCD_UUID,
          BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        ),
      )
    }
    val service = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
      .apply { addCharacteristic(characteristic) }
    control = characteristic
    server = manager.openGattServer(appContext, callback)?.also { it.addService(service) }
      ?: error("Could not open the GATT server")
    val parameters = AdvertisingSetParameters.Builder()
      .setLegacyMode(true)
      .setConnectable(true)
      .setScannable(true)
      .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
      .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
      .build()
    val data = AdvertiseData.Builder()
      .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
      .setIncludeDeviceName(false)
      .build()
    val response = scanResponse()
    lastScanResponse = response.second
    advertiser.startAdvertisingSet(parameters, data, response.first, null, null, advertiseCallback)
  }

  /** Re-publishes queue counts and the pairing flag without interrupting advertising. */
  @SuppressLint("MissingPermission")
  fun refreshAdvertisement() {
    val set = advertisingSet ?: return
    val (data, bytes) = scanResponse()
    if (lastScanResponse?.contentEquals(bytes) == true) return
    lastScanResponse = bytes
    set.setScanResponseData(data)
  }

  fun setPairingMode(enabled: Boolean) {
    pairingMode = enabled
    refreshAdvertisement()
  }

  private fun scanResponse(): Pair<AdvertiseData, ByteArray> {
    val counts = runCatching(counts).getOrDefault(ContentCounts(0, 0))
    val bytes = NoteLinkAdvertisementCodec.encode(
      TransferCrypto.sha256(deviceId.encodeToByteArray()),
      deviceName,
      counts.images,
      counts.texts,
      counts.pdfs,
      if (pairingMode) NoteLinkAdvertisementCodec.FLAG_PAIRING else 0,
    )
    val data = AdvertiseData.Builder()
      .addManufacturerData(NoteLinkAdvertisementCodec.MANUFACTURER_ID, bytes)
      .setIncludeDeviceName(false)
      .build()
    return data to bytes
  }

  /** Sends one record as a notification and waits until the stack reported it sent. */
  @SuppressLint("MissingPermission")
  suspend fun notify(peerKey: String, record: ByteArray) {
    val device = devices[peerKey] ?: throw V4LinkException("Central $peerKey is not connected")
    if (peerKey !in subscribed) throw V4LinkException("Central $peerKey has not subscribed")
    val characteristic = control ?: throw V4LinkException("GATT server is closed")
    val active = server ?: throw V4LinkException("GATT server is closed")
    repeat(NOTIFY_ATTEMPTS) { attempt ->
      val waiter = CompletableDeferred<Int>()
      notifyWaiters[peerKey] = waiter
      val accepted = if (Build.VERSION.SDK_INT >= 33) {
        active.notifyCharacteristicChanged(device, characteristic, false, record) == BluetoothStatusCodes.SUCCESS
      } else {
        @Suppress("DEPRECATION")
        characteristic.value = record
        @Suppress("DEPRECATION")
        active.notifyCharacteristicChanged(device, characteristic, false)
      }
      if (accepted) {
        val status = withTimeoutOrNull(NOTIFY_TIMEOUT_MILLIS) { waiter.await() }
        if (status == BluetoothGatt.GATT_SUCCESS) return
        if (devices[peerKey] == null) throw V4LinkException("Central $peerKey disconnected")
      }
      notifyWaiters.remove(peerKey, waiter)
      kotlinx.coroutines.delay(BUSY_BACKOFF_MILLIS * (attempt + 1))
    }
    throw V4LinkException("Notification to $peerKey failed repeatedly")
  }

  @SuppressLint("MissingPermission")
  override fun close() {
    closed = true
    runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(advertiseCallback) }
    advertisingSet = null
    notifyWaiters.values.forEach { it.complete(BluetoothGatt.GATT_FAILURE) }
    notifyWaiters.clear()
    devices.keys.toList().forEach(listener::onDisconnected)
    devices.clear()
    subscribed.clear()
    runCatching { server?.close() }
    server = null
    control = null
  }

  private val advertiseCallback = object : AdvertisingSetCallback() {
    override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
      if (status == ADVERTISE_SUCCESS && set != null) {
        advertisingSet = set
        Log.i(TAG, "BLE advertising started tx=$txPower")
        if (closed) runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(this) }
      } else {
        Log.e(TAG, "BLE advertising failed: $status")
      }
    }

    override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
      if (advertisingSet === set) advertisingSet = null
    }
  }

  private companion object {
    const val TAG = "NoteLinkPeripheral"
    const val NOTIFY_ATTEMPTS = 6
    const val NOTIFY_TIMEOUT_MILLIS = 2_000L
    const val BUSY_BACKOFF_MILLIS = 8L
  }
}
