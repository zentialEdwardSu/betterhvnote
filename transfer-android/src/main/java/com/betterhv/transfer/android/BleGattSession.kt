package com.betterhv.transfer.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** One authenticated-control connection. Responses are indications, so commands remain request/response ordered. */
class BleGattSession private constructor(
    private val context: Context,
    private val device: BluetoothDevice
) : AutoCloseable {
    private val connected = CompletableDeferred<Unit>()
    private val ready = CompletableDeferred<Unit>()
    private val mtuReady = CompletableDeferred<Int>()
    private var responseWaiter: CompletableDeferred<ByteArray>? = null
    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private var response: BluetoothGattCharacteristic? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val error = IllegalStateException("GATT connection failed: $status")
                connected.completeExceptionally(error); ready.completeExceptionally(error)
                responseWaiter?.completeExceptionally(error)
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
                @SuppressLint("MissingPermission") gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                responseWaiter?.completeExceptionally(IllegalStateException("GATT disconnected"))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ready.completeExceptionally(IllegalStateException("GATT discovery failed: $status")); return
            }
            val service = gatt.getService(BleConstants.SERVICE_UUID)
            command = service?.getCharacteristic(BleConstants.COMMAND_UUID)
            response = service?.getCharacteristic(BleConstants.RESPONSE_UUID)
            val result = response ?: run {
                ready.completeExceptionally(IllegalStateException("BetterHv BLE response characteristic missing")); return
            }
            @SuppressLint("MissingPermission") gatt.setCharacteristicNotification(result, true)
            val descriptor = result.getDescriptor(BleConstants.CCCD_UUID)
            if (descriptor == null) { ready.complete(Unit); return }
            if (Build.VERSION.SDK_INT >= 33) {
                @SuppressLint("MissingPermission") gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            } else {
                @Suppress("DEPRECATION") descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                @Suppress("DEPRECATION", "MissingPermission") gatt.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit)
            else ready.completeExceptionally(IllegalStateException("GATT subscribe failed: $status"))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtuReady.complete(mtu)
            else mtuReady.completeExceptionally(IllegalStateException("GATT MTU negotiation failed: $status"))
        }

        @Deprecated("API 33 callback")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") responseWaiter?.complete(characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) { responseWaiter?.complete(value) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun open() {
        gatt = if (Build.VERSION.SDK_INT >= 26) device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(context, false, callback)
        withTimeout(45_000L) { connected.await(); ready.await() }
        if (gatt?.requestMtu(247) == true) {
            val mtu = withTimeout(5_000L) { mtuReady.await() }
            require(mtu >= 247) { "手机 BLE MTU 过小：$mtu" }
        } else error("手机拒绝 BLE MTU 协商")
    }

    @SuppressLint("MissingPermission")
    suspend fun exchange(commandValue: ByteArray, timeoutMillis: Long = 15_000L): ByteArray {
        val characteristic = requireNotNull(command) { "GATT session is not ready" }
        check(responseWaiter == null || responseWaiter?.isCompleted == true) { "A BLE request is already active" }
        val waiter = CompletableDeferred<ByteArray>().also { responseWaiter = it }
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            gatt?.writeCharacteristic(characteristic, commandValue, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") characteristic.value = commandValue
            @Suppress("DEPRECATION") gatt?.writeCharacteristic(characteristic) == true
        }
        check(accepted) { "GATT command was rejected" }
        return withTimeout(timeoutMillis) { waiter.await() }
    }

    @SuppressLint("MissingPermission")
    override fun close() { gatt?.disconnect(); gatt?.close(); gatt = null }

    companion object {
        @SuppressLint("MissingPermission")
        suspend fun connect(context: Context, bluetoothAddress: String): BleGattSession {
            val manager = context.applicationContext.getSystemService(BluetoothManager::class.java)
            val session = BleGattSession(context.applicationContext, manager.adapter.getRemoteDevice(bluetoothAddress))
            return try {
                session.open()
                session
            } catch (t: Throwable) {
                session.close()
                throw t
            }
        }
    }
}
