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
import android.util.Log
import com.betterhv.transfer.core.BleTransportFrameCodec
import com.betterhv.transfer.core.BleTransportReassembler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

private class GattConnectionException(
    val status: Int,
    newState: Int
) : IllegalStateException("GATT connection failed: $status (state=$newState)")

/** One authenticated-control connection. Responses are indications, so commands remain request/response ordered. */
class BleGattSession private constructor(
    private val context: Context,
    private val device: BluetoothDevice
) : AutoCloseable {
    private val connected = CompletableDeferred<Unit>()
    private val ready = CompletableDeferred<Unit>()
    private val mtuReady = CompletableDeferred<Int>()
    private var responseWaiter: CompletableDeferred<ByteArray>? = null
    private var writeWaiter: CompletableDeferred<Unit>? = null
    private var identityWaiter: CompletableDeferred<ByteArray>? = null
    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private var response: BluetoothGattCharacteristic? = null
    private var identity: BluetoothGattCharacteristic? = null
    private var responseReassembler = BleTransportReassembler()
    private var maxFrameBytes = BleTransportFrameCodec.DEFAULT_FRAME_BYTES
    @Volatile private var closed = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val error = GattConnectionException(status, newState)
                connected.completeExceptionally(error); ready.completeExceptionally(error)
                responseWaiter?.completeExceptionally(error)
                writeWaiter?.completeExceptionally(error)
                identityWaiter?.completeExceptionally(error)
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val error = IllegalStateException("GATT disconnected")
                if (!connected.isCompleted) connected.completeExceptionally(error)
                if (!ready.isCompleted) ready.completeExceptionally(error)
                responseWaiter?.completeExceptionally(error)
                writeWaiter?.completeExceptionally(error)
                identityWaiter?.completeExceptionally(error)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ready.completeExceptionally(IllegalStateException("GATT discovery failed: $status")); return
            }
            val service = gatt.getService(BleConstants.SERVICE_UUID)
            identity = service?.getCharacteristic(BleConstants.IDENTITY_UUID)
            command = service?.getCharacteristic(BleConstants.COMMAND_UUID)
            response = service?.getCharacteristic(BleConstants.RESPONSE_UUID)
            val result = response ?: run {
                ready.completeExceptionally(IllegalStateException("BetterHv BLE response characteristic missing")); return
            }
            @SuppressLint("MissingPermission")
            gatt.setCharacteristicNotification(result, true)
            val descriptor = result.getDescriptor(BleConstants.CCCD_UUID)
            if (descriptor == null) { ready.complete(Unit); return }
            if (Build.VERSION.SDK_INT >= 33) {
                @SuppressLint("MissingPermission")
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                @Suppress("DEPRECATION", "MissingPermission")
                gatt.writeDescriptor(descriptor)
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

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (closed) return
            if (status == BluetoothGatt.GATT_SUCCESS) writeWaiter?.complete(Unit)
            else writeWaiter?.completeExceptionally(IllegalStateException("GATT frame write failed: $status"))
        }

        @Deprecated("API 33 callback")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            acceptResponse(characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) { acceptResponse(value) }

        private fun acceptResponse(value: ByteArray) {
            val waiter = responseWaiter ?: return
            if (!BleTransportFrameCodec.isFrame(value)) {
                waiter.complete(value)
                return
            }
            runCatching { responseReassembler.add(value) }
                .onSuccess { complete -> complete?.let(waiter::complete) }
                .onFailure { waiter.completeExceptionally(it) }
        }

        @Deprecated("API 33 callback")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != BleConstants.IDENTITY_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                identityWaiter?.complete(characteristic.value ?: ByteArray(0))
            } else identityWaiter?.completeExceptionally(IllegalStateException("GATT identity read failed: $status"))
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (characteristic.uuid != BleConstants.IDENTITY_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) identityWaiter?.complete(value)
            else identityWaiter?.completeExceptionally(IllegalStateException("GATT identity read failed: $status"))
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun open() {
        gatt = if (Build.VERSION.SDK_INT >= 26) device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(context, false, callback)
        // Android can otherwise wait roughly 30 seconds before surfacing a
        // stale Windows address as GATT status 133. Fail fast so the caller
        // can retry a fresh advertisement instead of blocking the transfer.
        withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { connected.await() }
            ?: throw GattConnectionException(133, BluetoothProfile.STATE_DISCONNECTED)
        // Windows creates a local GATT service dynamically. Its handles can
        // change after NoteLink restarts, while Android keeps the old service
        // table even across app process restarts. Refresh before discovery so
        // a cached command handle cannot fail later with GATT_INVALID_HANDLE.
        if (refreshGattCache()) delay(GATT_CACHE_SETTLE_MILLIS)
        check(gatt?.discoverServices() == true) { "GATT service discovery was rejected" }
        withTimeout(15_000L) { ready.await() }
        val mtu = if (gatt?.requestMtu(247) == true) {
            try {
                withTimeout(5_000L) { mtuReady.await() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                23
            }
        } else 23
        // Windows' GATT peripheral accepts the negotiated MTU but can still
        // reject characteristic writes above the legacy ATT payload. Keep
        // client-to-peripheral control frames at the universally safe size;
        // large responses remain independently fragmented by the sender.
        maxFrameBytes = (mtu - 3).coerceAtMost(SAFE_WRITE_FRAME_BYTES)
        require(maxFrameBytes >= BleTransportFrameCodec.HEADER_BYTES) {
            "BLE MTU too small for NoteLink framing: $mtu"
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun exchange(commandValue: ByteArray, timeoutMillis: Long = 15_000L): ByteArray {
        val characteristic = requireNotNull(command) { "GATT session is not ready" }
        check(responseWaiter == null || responseWaiter?.isCompleted == true) { "A BLE request is already active" }
        val waiter = CompletableDeferred<ByteArray>().also { responseWaiter = it }
        responseReassembler.reset()
        return try {
            withTimeout(timeoutMillis) {
                val messageId = NEXT_MESSAGE_ID.getAndIncrement()
                BleTransportFrameCodec.fragment(commandValue, messageId, maxFrameBytes)
                    .forEach { frame -> writeFrame(characteristic, frame) }
                waiter.await()
            }
        } finally {
            if (responseWaiter === waiter) responseWaiter = null
            responseReassembler.reset()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeFrame(characteristic: BluetoothGattCharacteristic, frame: ByteArray) {
        val waiter = CompletableDeferred<Unit>().also { writeWaiter = it }
        try {
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                gatt?.writeCharacteristic(characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = frame
                @Suppress("DEPRECATION")
                gatt?.writeCharacteristic(characteristic) == true
            }
            check(accepted) { "GATT frame write was rejected" }
            withTimeout(5_000L) { waiter.await() }
        } finally {
            if (writeWaiter === waiter) writeWaiter = null
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun readIdentity(timeoutMillis: Long = 10_000L): BleIdentity {
        val characteristic = requireNotNull(identity) { "BetterHv BLE identity characteristic missing" }
        check(identityWaiter == null || identityWaiter?.isCompleted == true) { "A BLE identity read is already active" }
        val waiter = CompletableDeferred<ByteArray>().also { identityWaiter = it }
        check(gatt?.readCharacteristic(characteristic) == true) { "GATT identity read was rejected" }
        return try {
            BleIdentityCodec.decode(withTimeout(timeoutMillis) { waiter.await() })
        } finally {
            if (identityWaiter === waiter) identityWaiter = null
        }
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        closed = true
        val error = IllegalStateException("GATT session closed")
        responseWaiter?.completeExceptionally(error)
        writeWaiter?.completeExceptionally(error)
        identityWaiter?.completeExceptionally(error)
        responseWaiter = null
        writeWaiter = null
        identityWaiter = null
        responseReassembler.reset()
        gatt?.disconnect(); gatt?.close(); gatt = null
    }

    private fun refreshGattCache(): Boolean = runCatching {
        val activeGatt = gatt ?: return@runCatching false
        val refresh = BluetoothGatt::class.java.getMethod("refresh")
        refresh.invoke(activeGatt) as? Boolean ?: false
    }.getOrDefault(false)

    companion object {
        private const val SAFE_WRITE_FRAME_BYTES = 20
        private const val GATT_CACHE_SETTLE_MILLIS = 300L
        private const val GATT_ERROR = 133
        private const val CONNECT_ATTEMPTS = 3
        private const val CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val RETRY_BASE_DELAY_MILLIS = 750L
        private const val TAG = "NoteLinkGatt"
        private val NEXT_MESSAGE_ID = AtomicInteger(1)
        private val CONNECT_MUTEX = Mutex()

        @SuppressLint("MissingPermission")
        suspend fun connect(context: Context, bluetoothAddress: String): BleGattSession = CONNECT_MUTEX.withLock {
            val appContext = context.applicationContext
            val manager = appContext.getSystemService(BluetoothManager::class.java)
            var lastFailure: GattConnectionException? = null
            repeat(CONNECT_ATTEMPTS) { index ->
                val session = BleGattSession(appContext, manager.adapter.getRemoteDevice(bluetoothAddress))
                try {
                    session.open()
                    return@withLock session
                } catch (error: GattConnectionException) {
                    session.close()
                    lastFailure = error
                    val attempt = index + 1
                    if (error.status != GATT_ERROR || attempt == CONNECT_ATTEMPTS) throw error
                    Log.w(TAG, "Transient GATT 133 connecting to $bluetoothAddress; retry $attempt/$CONNECT_ATTEMPTS")
                    delay(RETRY_BASE_DELAY_MILLIS * attempt)
                } catch (error: Throwable) {
                    session.close()
                    throw error
                }
            }
            throw checkNotNull(lastFailure)
        }
    }
}
