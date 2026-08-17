package com.betterhv.transfer.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.betterhv.transfer.core.ContentKind
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** BLE peripheral used by the phone. Control payloads are deliberately small and MTU-safe. */
class BleSenderPeripheral(
    context: Context,
    private val deviceId: String,
    private val deviceName: String,
    private val counts: () -> Pair<Int, Int>,
    private val onCommand: (BluetoothDevice, ByteArray) -> ByteArray
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter get() = manager.adapter
    private var server: BluetoothGattServer? = null
    private var response: BluetoothGattCharacteristic? = null
    private val subscribed = LinkedHashSet<BluetoothDevice>()

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState != BluetoothGatt.STATE_CONNECTED) subscribed.remove(device)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            val bytes = identityBytes()
            val value = if (offset in 0..bytes.size) bytes.copyOfRange(offset, bytes.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            val result = runCatching { onCommand(device, value) }
            if (responseNeeded) server?.sendResponse(
                device, requestId,
                if (result.isSuccess) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                offset, null
            )
            result.getOrNull()?.let { notify(device, it) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (descriptor.uuid == BleConstants.CCCD_UUID) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) subscribed += device
                else subscribed -= device
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        check(adapter.isEnabled) { "Bluetooth is disabled" }
        check(adapter.bluetoothLeAdvertiser != null) { "BLE peripheral advertising is unsupported" }
        val identity = BluetoothGattCharacteristic(
            BleConstants.IDENTITY_UUID, BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val command = BluetoothGattCharacteristic(
            BleConstants.COMMAND_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        response = BluetoothGattCharacteristic(
            BleConstants.RESPONSE_UUID,
            BluetoothGattCharacteristic.PROPERTY_INDICATE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).also { it.addDescriptor(BluetoothGattDescriptor(
            BleConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )) }
        val service = android.bluetooth.BluetoothGattService(
            BleConstants.SERVICE_UUID, android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply { addCharacteristic(identity); addCharacteristic(command); addCharacteristic(response) }
        server = manager.openGattServer(appContext, callback).also { it.addService(service) }
        startAdvertisement()
    }

    @SuppressLint("MissingPermission")
    fun refreshAdvertisement() {
        adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        startAdvertisement()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisement() {
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .setIncludeDeviceName(false).build()
        val scanResponse = AdvertiseData.Builder()
            .addManufacturerData(MANUFACTURER_ID, advertisementBytes())
            .setIncludeDeviceName(false).build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true).build()
        adapter.bluetoothLeAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun notify(device: BluetoothDevice, value: ByteArray) {
        if (device !in subscribed) return
        val characteristic = response ?: return
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            server?.notifyCharacteristicChanged(device, characteristic, true, value)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            server?.notifyCharacteristicChanged(device, characteristic, true)
        }
    }

    private fun advertisementBytes(): ByteArray {
        val (images, texts) = counts()
        val idHash = com.betterhv.transfer.core.TransferCrypto.sha256(deviceId.encodeToByteArray())
        val encodedName = deviceName.encodeToByteArray()
        val name = encodedName.copyOfRange(0, encodedName.size.coerceAtMost(MAX_ADVERTISED_NAME_BYTES))
        return byteArrayOf(
            BleConstants.PROTOCOL_VERSION.toByte(), images.coerceIn(0, 255).toByte(),
            texts.coerceIn(0, 255).toByte()
        ) + idHash.copyOf(5) + name
    }

    private fun identityBytes(): ByteArray {
        val name = deviceName.encodeToByteArray().copyOf(48)
        val id = deviceId.encodeToByteArray().copyOf(48)
        val (images, texts) = counts()
        return ByteBuffer.allocate(4 + id.size + name.size).order(ByteOrder.BIG_ENDIAN)
            .put(BleConstants.PROTOCOL_VERSION.toByte())
            .put(images.coerceIn(0, 255).toByte()).put(texts.coerceIn(0, 255).toByte())
            .put(id.size.toByte()).put(id).put(name).array()
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        subscribed.clear()
        server?.close(); server = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
        }
    }

    private companion object {
        const val MANUFACTURER_ID = 0x0B17
        const val TAG = "BetterHvBle"
        const val MAX_ADVERTISED_NAME_BYTES = 18
    }

}
