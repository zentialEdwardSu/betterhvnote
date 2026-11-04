package com.betterhv.transfer.windows

import com.betterhv.transfer.core.NoteLinkAdvertisementCodec
import com.betterhv.transfer.core.TransferCrypto
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

internal interface NoteLinkNativeLibrary : Library {
  fun nl_initialize(): Int
  fun nl_capabilities(): Int
  fun nl_ble_start(advertisement: Pointer?, advertisementLength: Int): Int
  fun nl_ble_update(advertisement: Pointer?, advertisementLength: Int): Int
  fun nl_ble_poll_event(
    type: IntByReference,
    client: Pointer?,
    clientCapacity: Int,
    payload: Pointer?,
    payloadCapacity: Int,
    payloadLength: IntByReference,
    timeoutMillis: Int,
  ): Int
  fun nl_ble_notify(client: String, value: Pointer?, length: Int): Int
  fun nl_ble_stop()
  fun nl_lan_info(ipv4: Pointer?, ipv4Capacity: Int, ssid: Pointer?, ssidCapacity: Int): Int
  fun nl_protect(input: Pointer?, inputLength: Int, output: Pointer?, outputCapacity: Int): Int
  fun nl_unprotect(input: Pointer?, inputLength: Int, output: Pointer?, outputCapacity: Int): Int
  fun nl_last_error(output: Pointer?, capacity: Int): Int
  fun nl_shutdown()
}

class JnaWindowsNativeApi private constructor(private val library: NoteLinkNativeLibrary) : WindowsNativeApi {
  private val initialized = AtomicBoolean(false)
  private val bleRunning = AtomicBoolean(false)
  private var bleDeviceId = ""

  override fun initialize() {
    if (initialized.compareAndSet(false, true)) {
      if (library.nl_initialize() != 0) {
        initialized.set(false)
        throw failure("Unable to initialize the Windows radio bridge")
      }
    }
  }

  override fun capabilities(): WindowsCapabilities {
    initialize()
    val flags = library.nl_capabilities()
    return WindowsCapabilities(
      blePeripheral = flags and CAP_BLE_PERIPHERAL != 0,
      lan = flags and CAP_LAN != 0,
      dataProtection = flags and CAP_DPAPI != 0,
    )
  }

  @Synchronized
  override fun startBle(
    deviceId: String, deviceName: String, imageCount: Int, textCount: Int, pdfCount: Int, flags: Int
  ) {
    require(deviceId.isNotBlank())
    initialize()
    if (bleRunning.get()) stopBle()
    bleDeviceId = deviceId
    val advertisement = advertisement(imageCount, textCount, pdfCount, flags)
    checkCall(
      library.nl_ble_start(advertisement.memory(), advertisement.size),
      "Unable to start BLE",
    )
    bleRunning.set(true)
  }

  override fun updateBleCounts(imageCount: Int, textCount: Int, pdfCount: Int, flags: Int) {
    check(bleRunning.get()) { "BLE is not running" }
    val advertisement = advertisement(imageCount, textCount, pdfCount, flags)
    checkCall(
      library.nl_ble_update(advertisement.memory(), advertisement.size),
      "Unable to update BLE queue counts",
    )
  }

  private val eventPayload = Memory(MAX_CONTROL_BYTES.toLong())
  private val eventClient = Memory(CLIENT_ID_BYTES.toLong())

  /** Called from one reader thread only; the event buffers are reused. */
  override fun pollBleEvent(timeoutMillis: Int): BleControlEvent? {
    check(bleRunning.get()) { "BLE is not running" }
    require(timeoutMillis >= 0)
    val type = IntByReference()
    val length = IntByReference()
    val result = library.nl_ble_poll_event(
      type, eventClient, CLIENT_ID_BYTES, eventPayload, MAX_CONTROL_BYTES, length, timeoutMillis,
    )
    if (result == 0) return null
    if (result < -1) throw WindowsNativeException("BLE event exceeded the native event buffer")
    if (result < 0) throw failure("Unable to read a BLE event")
    val client = eventClient.getString(0, Charsets.UTF_8.name())
    val payload = eventPayload.getByteArray(0, length.value)
    return when (type.value) {
      EVENT_RECORD -> BleControlEvent.Record(client, payload)
      EVENT_CONNECTED -> BleControlEvent.Connected(client)
      EVENT_DISCONNECTED -> BleControlEvent.Disconnected(client)
      EVENT_MTU -> BleControlEvent.Mtu(
        client,
        if (payload.size >= 2) (payload[0].toInt() and 0xff) or ((payload[1].toInt() and 0xff) shl 8) else DEFAULT_MTU,
      )
      else -> null
    }
  }

  override fun notifyBle(client: String, value: ByteArray) {
    check(bleRunning.get()) { "BLE is not running" }
    checkCall(library.nl_ble_notify(client, value.memory(), value.size), "Unable to notify the BLE client")
  }

  @Synchronized
  override fun stopBle() {
    if (bleRunning.getAndSet(false)) library.nl_ble_stop()
  }

  override fun lanInfo(): WindowsLanInfo {
    initialize()
    val address = Memory(LAN_ADDRESS_BYTES.toLong())
    val ssid = Memory(SSID_BYTES.toLong())
    val ssidLength = library.nl_lan_info(address, LAN_ADDRESS_BYTES, ssid, SSID_BYTES)
    if (ssidLength < 0) throw failure("Unable to read the active LAN")
    return WindowsLanInfo(
      address.getString(0, Charsets.UTF_8.name()),
      ssid.getByteArray(0, ssidLength).decodeToString(),
    )
  }

  override fun protect(value: ByteArray): ByteArray = transform(value, library::nl_protect)
  override fun unprotect(value: ByteArray): ByteArray = transform(value, library::nl_unprotect)

  @Synchronized
  override fun close() {
    stopBle()
    if (initialized.getAndSet(false)) library.nl_shutdown()
  }

  private fun transform(value: ByteArray, call: (Pointer?, Int, Pointer?, Int) -> Int): ByteArray {
    initialize()
    val input = value.memory()
    val requiredResult = call(input, value.size, null, 0)
    val required = when {
      requiredResult < 0 -> -requiredResult
      requiredResult == 0 -> return ByteArray(0)
      else -> requiredResult
    }
    if (required <= 0 || required > MAX_PROTECTED_BYTES) throw failure("Invalid protected data size")
    val output = Memory(required.toLong())
    val result = call(input, value.size, output, required)
    if (result < 0) throw failure("Windows data protection failed")
    return output.getByteArray(0, result)
  }

  private fun advertisement(imageCount: Int, textCount: Int, pdfCount: Int, flags: Int): ByteArray =
    NoteLinkAdvertisementCodec.encode(
      // A 128-bit service UUID plus this ten-byte payload fits in one legacy connectable
      // advertisement. The display name travels in the ServerHello capabilities.
      TransferCrypto.sha256(bleDeviceId.encodeToByteArray()),
      "",
      imageCount,
      textCount,
      pdfCount,
      flags,
    )

  private fun checkCall(result: Int, operation: String) {
    if (result != 0) throw failure(operation)
  }

  private fun failure(fallback: String): WindowsNativeException {
    val requiredResult = library.nl_last_error(null, 0)
    if (requiredResult < 0) {
      val output = Memory((-requiredResult).toLong())
      if (library.nl_last_error(output, -requiredResult) >= 0) {
        return WindowsNativeException(output.getString(0, Charsets.UTF_8.name()).ifBlank { fallback })
      }
    }
    return WindowsNativeException(fallback)
  }

  private fun ByteArray.memory(): Memory? = takeIf { it.isNotEmpty() }?.let { value ->
    Memory(value.size.toLong()).also { it.write(0, value, 0, value.size) }
  }

  companion object {
    private const val CAP_BLE_PERIPHERAL = 1
    private const val CAP_LAN = 2
    private const val CAP_DPAPI = 4
    private const val MAX_CONTROL_BYTES = 64 * 1024
    private const val CLIENT_ID_BYTES = 512
    private const val EVENT_RECORD = 1
    private const val EVENT_CONNECTED = 2
    private const val EVENT_DISCONNECTED = 3
    private const val EVENT_MTU = 4
    private const val DEFAULT_MTU = 23
    private const val MAX_PROTECTED_BYTES = 1024 * 1024
    private const val LAN_ADDRESS_BYTES = 256
    private const val SSID_BYTES = 32
    private const val BUNDLED_LIBRARY_RESOURCE = "/win32-x86-64/notelink_windows.dll"

    fun load(): JnaWindowsNativeApi {
      check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "The NoteLink Windows bridge can only run on Windows"
      }
      val explicitPath = System.getProperty("notelink.windows.library.path")
      val libraryPath = resolveLibraryPath(explicitPath)
      val library = Native.load(libraryPath, NoteLinkNativeLibrary::class.java)
      return JnaWindowsNativeApi(library)
    }

    /**
     * Resolves a filesystem path because JNA cannot load a native library directly from a
     * JAR resource. The packaged DLL is extracted once per application process into a
     * private temporary directory, which also avoids relying on the user's PATH.
     */
    internal fun resolveLibraryPath(explicitPath: String?): String {
      if (!explicitPath.isNullOrBlank()) return explicitPath

      val resource = JnaWindowsNativeApi::class.java.getResourceAsStream(BUNDLED_LIBRARY_RESOURCE)
        ?: error("Bundled Windows bridge resource $BUNDLED_LIBRARY_RESOURCE is missing")
      val directory = Files.createTempDirectory("notelink-native-")
      directory.toFile().deleteOnExit()
      val library = directory.resolve("notelink_windows.dll")
      resource.use { input ->
        Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING)
      }
      library.toFile().deleteOnExit()
      return library.toAbsolutePath().toString()
    }

    internal fun forTesting(library: NoteLinkNativeLibrary) = JnaWindowsNativeApi(library)
  }
}
