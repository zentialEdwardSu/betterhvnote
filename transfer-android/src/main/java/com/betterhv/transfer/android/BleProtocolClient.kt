package com.betterhv.transfer.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.betterhv.transfer.core.v4.session.PeerConnection
import com.betterhv.transfer.core.v4.session.RecordLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class V4UnsupportedException(message: String = "Peer does not expose the NoteLink control characteristic") :
  IllegalStateException(message)

class V4LinkException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * GATT client for the NoteLink control characteristic, exposed as a v4 [RecordLink].
 *
 * Android allows exactly one outstanding GATT operation per connection, so every step waits for
 * its callback: connect → MTU → service discovery → CCCD write. Each record write waits for
 * `onCharacteristicWrite` too; without that, a burst of fragments is silently dropped as busy.
 */
class BleProtocolClient private constructor(
  private val context: Context,
  private val device: BluetoothDevice,
) : RecordLink {
  private val incoming = Channel<ByteArray>(INBOUND_CAPACITY)
  private val writeLock = Mutex()
  private val disconnected = CompletableDeferred<Unit>()
  @Volatile private var gatt: BluetoothGatt? = null
  @Volatile private var control: BluetoothGattCharacteristic? = null
  @Volatile private var pending: CompletableDeferred<Int>? = null
  @Volatile private var pendingKind = Op.NONE
  @Volatile private var negotiatedMtu = DEFAULT_ATT_MTU
  @Volatile private var closed = false

  private enum class Op { NONE, CONNECT, MTU, SERVICES, DESCRIPTOR, WRITE }

  override val label: String = PeerConnection.LABEL_BLE
  override val maxRecordBytes: Int get() = (negotiatedMtu - ATT_OVERHEAD).coerceAtMost(MAX_ATT_PAYLOAD)

  val address: String get() = device.address
  val mtu: Int get() = negotiatedMtu

  private val callback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
        complete(Op.CONNECT, status)
        return
      }
      if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
        Log.i(TAG, "GATT disconnected ${device.address} status=$status")
        disconnected.complete(Unit)
        pending?.completeExceptionally(V4LinkException("GATT disconnected (status $status)"))
        incoming.close()
      }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
      if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
      complete(Op.MTU, status)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = complete(Op.SERVICES, status)

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
      complete(Op.DESCRIPTOR, status)

    override fun onCharacteristicWrite(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      status: Int,
    ) = complete(Op.WRITE, status)

    @Deprecated("API 33 callback")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
      @Suppress("DEPRECATION")
      accept(characteristic, characteristic.value ?: return)
    }

    override fun onCharacteristicChanged(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray,
    ) = accept(characteristic, value)

    private fun accept(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
      if (characteristic.uuid != BleConstants.CONTROL_UUID) return
      if (incoming.trySend(value.copyOf()).isFailure) Log.w(TAG, "control rx dropped ${value.size} bytes")
    }
  }

  private fun complete(op: Op, status: Int) {
    if (pendingKind == op) pending?.complete(status)
  }

  /** Runs one GATT operation and waits for its callback status. */
  private suspend fun await(op: Op, timeoutMillis: Long, start: () -> Boolean): Int {
    val waiter = CompletableDeferred<Int>()
    pending = waiter
    pendingKind = op
    try {
      if (!start()) throw V4LinkException("GATT refused $op")
      return withTimeoutOrNull(timeoutMillis) { waiter.await() }
        ?: throw V4LinkException("GATT $op timed out after $timeoutMillis ms")
    } finally {
      pendingKind = Op.NONE
      pending = null
    }
  }

  @SuppressLint("MissingPermission")
  private suspend fun open(autoConnect: Boolean, connectTimeoutMillis: Long) {
    var active: BluetoothGatt? = null
    val connectStatus = await(Op.CONNECT, if (autoConnect) AUTO_CONNECT_TIMEOUT_MILLIS else connectTimeoutMillis) {
      active = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
      gatt = active
      active != null
    }
    val connected = checkNotNull(active)
    if (connectStatus != BluetoothGatt.GATT_SUCCESS) throw V4LinkException("GATT connect failed: $connectStatus")
    connected.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    // MTU first: both phones and Windows answer it before service discovery, and a large MTU
    // makes the 200-byte hello a single record.
    runCatching { await(Op.MTU, MTU_TIMEOUT_MILLIS) { connected.requestMtu(MAX_MTU) } }
      .onFailure { Log.w(TAG, "MTU request failed: ${it.message}") }
    val servicesStatus = await(Op.SERVICES, SERVICE_TIMEOUT_MILLIS) { connected.discoverServices() }
    if (servicesStatus != BluetoothGatt.GATT_SUCCESS) throw V4LinkException("Service discovery failed: $servicesStatus")
    val characteristic = connected.getService(BleConstants.SERVICE_UUID)?.getCharacteristic(BleConstants.CONTROL_UUID)
      ?: throw V4UnsupportedException()
    if (!connected.setCharacteristicNotification(characteristic, true)) {
      throw V4LinkException("Could not enable control notifications")
    }
    val descriptor = characteristic.getDescriptor(BleConstants.CCCD_UUID)
      ?: throw V4LinkException("Control characteristic has no CCCD")
    val cccdStatus = await(Op.DESCRIPTOR, DESCRIPTOR_TIMEOUT_MILLIS) {
      writeDescriptor(connected, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }
    if (cccdStatus != BluetoothGatt.GATT_SUCCESS) throw V4LinkException("Notification subscription failed: $cccdStatus")
    control = characteristic
    if (maxRecordBytes < MIN_RECORD_BYTES) {
      throw V4UnsupportedException("ATT MTU $negotiatedMtu is too small for NoteLink")
    }
  }

  @SuppressLint("MissingPermission")
  private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
    if (Build.VERSION.SDK_INT >= 33) {
      gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    } else {
      @Suppress("DEPRECATION")
      descriptor.value = value
      @Suppress("DEPRECATION")
      gatt.writeDescriptor(descriptor)
    }

  /** Writes one record and waits until the stack accepted it. */
  @SuppressLint("MissingPermission")
  override suspend fun send(record: ByteArray) {
    if (record.size > maxRecordBytes) {
      throw V4LinkException("Record ${record.size} exceeds the negotiated packet size $maxRecordBytes")
    }
    writeLock.withLock {
      val active = gatt ?: throw V4LinkException("GATT is closed")
      val characteristic = control ?: throw V4LinkException("GATT is not ready")
      repeat(WRITE_ATTEMPTS) { attempt ->
        val status = runCatching {
          await(Op.WRITE, WRITE_TIMEOUT_MILLIS) { write(active, characteristic, record) }
        }
        val value = status.getOrNull()
        if (value == BluetoothGatt.GATT_SUCCESS) return
        if (closed || disconnected.isCompleted) throw V4LinkException("GATT disconnected")
        if (value != null) throw V4LinkException("GATT write failed: $value")
        // Refused as busy: the controller's buffers are full. Back off one connection event.
        delay(BUSY_BACKOFF_MILLIS * (attempt + 1))
      }
      throw V4LinkException("GATT write was rejected repeatedly")
    }
  }

  @SuppressLint("MissingPermission")
  private fun write(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, record: ByteArray): Boolean {
    val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    return if (Build.VERSION.SDK_INT >= 33) {
      gatt.writeCharacteristic(characteristic, record, type) == BluetoothStatusCodes.SUCCESS
    } else {
      @Suppress("DEPRECATION")
      characteristic.writeType = type
      @Suppress("DEPRECATION")
      characteristic.value = record
      @Suppress("DEPRECATION")
      gatt.writeCharacteristic(characteristic)
    }
  }

  override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()

  /** Completes when the link went down for any reason. */
  suspend fun awaitDisconnect() = disconnected.await()

  @SuppressLint("MissingPermission")
  override fun close() {
    if (closed) return
    closed = true
    pending?.completeExceptionally(V4LinkException("Link closed"))
    incoming.close()
    disconnected.complete(Unit)
    val active = gatt
    gatt = null
    control = null
    runCatching { active?.disconnect() }
    runCatching { active?.close() }
  }

  companion object {
    private const val TAG = "NoteLinkBle"
    private const val MAX_MTU = 517
    private const val DEFAULT_ATT_MTU = 23
    private const val ATT_OVERHEAD = 3
    private const val MAX_ATT_PAYLOAD = 512
    private const val MIN_RECORD_BYTES = 100
    private const val INBOUND_CAPACITY = 512
    private const val CONNECT_TIMEOUT_MILLIS = 6_000L
    private const val AUTO_CONNECT_TIMEOUT_MILLIS = 30_000L
    private const val MTU_TIMEOUT_MILLIS = 1_500L
    private const val SERVICE_TIMEOUT_MILLIS = 6_000L
    private const val DESCRIPTOR_TIMEOUT_MILLIS = 3_000L
    private const val WRITE_TIMEOUT_MILLIS = 2_000L
    private const val WRITE_ATTEMPTS = 6
    private const val BUSY_BACKOFF_MILLIS = 8L
    private const val CONNECT_ATTEMPTS = 2

    /**
     * Opens a ready link. Android's `GATT_ERROR (133)` on the first connect after a scan is
     * common and transient, so one retry is made with a fresh `BluetoothGatt`.
     */
    suspend fun connect(
      context: Context,
      address: String,
      autoConnect: Boolean = false,
      attempts: Int = CONNECT_ATTEMPTS,
      connectTimeoutMillis: Long = CONNECT_TIMEOUT_MILLIS,
    ): BleProtocolClient {
      val manager = context.applicationContext.getSystemService(BluetoothManager::class.java)
      val adapter = manager?.adapter ?: throw V4LinkException("Bluetooth is unavailable")
      if (!adapter.isEnabled) throw V4LinkException("Bluetooth is off")
      val remote = adapter.getRemoteDevice(address)
      var last: Throwable? = null
      repeat(attempts.coerceAtLeast(1)) { attempt ->
        val client = BleProtocolClient(context.applicationContext, remote)
        try {
          val started = System.nanoTime()
          client.open(autoConnect, connectTimeoutMillis)
          Log.i(TAG, "GATT link ready $address mtu=${client.mtu} in ${(System.nanoTime() - started) / 1_000_000} ms")
          return client
        } catch (error: V4UnsupportedException) {
          client.close()
          throw error
        } catch (error: Throwable) {
          client.close()
          last = error
          Log.w(TAG, "GATT link attempt ${attempt + 1} to $address failed: ${error.message}")
          if (error is kotlinx.coroutines.CancellationException) throw error
          delay(RETRY_DELAY_MILLIS)
        }
      }
      throw V4LinkException("Could not connect to NoteLink: ${last?.message}", last)
    }

    private const val RETRY_DELAY_MILLIS = 250L
  }
}
