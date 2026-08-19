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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.betterhv.transfer.core.ContentKind
import com.betterhv.transfer.core.BleTransportFrameCodec
import com.betterhv.transfer.core.BleTransportReassembler
import com.betterhv.transfer.core.NoteLinkAdvertisementCodec
import com.betterhv.transfer.core.TransferCrypto
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

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
    private val commandReassemblers = mutableMapOf<String, BleTransportReassembler>()
    private val framedPeers = mutableMapOf<String, Boolean>()
    private val negotiatedMtus = mutableMapOf<String, Int>()
    private val pendingNotifications = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private val notificationInFlight = mutableSetOf<String>()

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState != BluetoothGatt.STATE_CONNECTED) {
                subscribed.remove(device)
                synchronized(this@BleSenderPeripheral) {
                    commandReassemblers.remove(device.address)
                    framedPeers.remove(device.address)
                    negotiatedMtus.remove(device.address)
                    pendingNotifications.remove(device.address)
                    notificationInFlight.remove(device.address)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            synchronized(this@BleSenderPeripheral) { negotiatedMtus[device.address] = mtu }
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
            val reassembler = synchronized(this@BleSenderPeripheral) {
                commandReassemblers.getOrPut(device.address) { BleTransportReassembler() }
            }
            val result = runCatching {
                if (BleTransportFrameCodec.isFrame(value)) {
                    synchronized(this@BleSenderPeripheral) { framedPeers[device.address] = true }
                    reassembler.add(value)
                } else {
                    synchronized(this@BleSenderPeripheral) { framedPeers[device.address] = false }
                    value.also { reassembler.reset() }
                }
            }.mapCatching { complete -> complete?.let { onCommand(device, it) } }
            if (result.isFailure) reassembler.reset()
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

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(this@BleSenderPeripheral) {
                notificationInFlight.remove(device.address)
                if (status != BluetoothGatt.GATT_SUCCESS) pendingNotifications.remove(device.address)
            }
            if (status == BluetoothGatt.GATT_SUCCESS) sendNextNotification(device)
            else Log.e(TAG, "BLE response frame failed: $status")
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
            .addManufacturerData(NoteLinkAdvertisementCodec.MANUFACTURER_ID, advertisementBytes())
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
        val framed = synchronized(this) { framedPeers[device.address] != false }
        val maxFrameBytes = synchronized(this) {
            ((negotiatedMtus[device.address] ?: 23) - 3)
                .coerceAtLeast(BleTransportFrameCodec.HEADER_BYTES)
                .coerceAtMost(BleTransportFrameCodec.DEFAULT_FRAME_BYTES)
        }
        val frames = if (framed) {
            BleTransportFrameCodec.fragment(value, NEXT_MESSAGE_ID.getAndIncrement(), maxFrameBytes)
        } else {
            listOf(value)
        }
        synchronized(this) {
            pendingNotifications.getOrPut(device.address) { ArrayDeque() }.addAll(frames)
        }
        sendNextNotification(device)
    }

    @SuppressLint("MissingPermission")
    private fun sendNextNotification(device: BluetoothDevice) {
        val characteristic = response ?: return
        val frame = synchronized(this) {
            if (device.address in notificationInFlight) return
            val queue = pendingNotifications[device.address] ?: return
            queue.pollFirst()?.also { notificationInFlight += device.address }
        } ?: return
        val accepted = if (android.os.Build.VERSION.SDK_INT >= 33) {
            server?.notifyCharacteristicChanged(device, characteristic, true, frame) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION")
            server?.notifyCharacteristicChanged(device, characteristic, true) == true
        }
        if (!accepted) {
            synchronized(this) {
                notificationInFlight.remove(device.address)
                pendingNotifications.remove(device.address)
            }
            Log.e(TAG, "BLE response frame was rejected")
        } else {
            synchronized(this) {
                if (pendingNotifications[device.address]?.isEmpty() == true) pendingNotifications.remove(device.address)
            }
        }
    }

    private fun advertisementBytes(): ByteArray {
        val (images, texts) = counts()
        return NoteLinkAdvertisementCodec.encode(
            TransferCrypto.sha256(deviceId.encodeToByteArray()), deviceName, images, texts
        )
    }

    private fun identityBytes(): ByteArray {
        val (images, texts) = counts()
        return BleIdentityCodec.encode(deviceId, deviceName, images, texts)
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        subscribed.clear()
        synchronized(this) {
            commandReassemblers.clear()
            framedPeers.clear()
            negotiatedMtus.clear()
            pendingNotifications.clear()
            notificationInFlight.clear()
        }
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
        const val TAG = "BetterHvBle"
        val NEXT_MESSAGE_ID = AtomicInteger(1)
    }

}
