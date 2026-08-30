package com.betterhv.transfer.windows

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.betterhv.transfer.core.DeviceId
import com.betterhv.transfer.core.BleTransportFrameCodec
import com.betterhv.transfer.core.BleTransportReassembler
import com.betterhv.transfer.core.NoteLinkAdvertisementCodec
import com.betterhv.transfer.core.NoteLinkIdentityCodec
import com.betterhv.transfer.core.TransferCrypto
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal interface NoteLinkNativeLibrary : Library {
    fun nl_initialize(): Int
    fun nl_capabilities(): Int
    fun nl_ble_start(identity: Pointer?, identityLength: Int, advertisement: Pointer?, advertisementLength: Int): Int
    fun nl_ble_update(
        identity: Pointer?,
        identityLength: Int,
        advertisement: Pointer?,
        advertisementLength: Int
    ): Int
    fun nl_ble_poll(output: Pointer?, capacity: Int, timeoutMillis: Int): Int
    fun nl_ble_respond(value: Pointer?, length: Int): Int
    fun nl_ble_stop()
    fun nl_lan_info(ipv4: Pointer?, ipv4Capacity: Int, ssid: Pointer?, ssidCapacity: Int): Int
    fun nl_protect(input: Pointer?, inputLength: Int, output: Pointer?, outputCapacity: Int): Int
    fun nl_unprotect(input: Pointer?, inputLength: Int, output: Pointer?, outputCapacity: Int): Int
    fun nl_last_error(output: Pointer?, capacity: Int): Int
    fun nl_shutdown()
}

class JnaWindowsNativeApi private constructor(
    private val library: NoteLinkNativeLibrary
) : WindowsNativeApi {
    private val initialized = AtomicBoolean(false)
    private val bleRunning = AtomicBoolean(false)
    private var bleDeviceId = ""
    private var bleDeviceName = "NoteLink"
    private val commandReassembler = BleTransportReassembler()
    private var lastCommandWasFramed = true

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
            dataProtection = flags and CAP_DPAPI != 0
        )
    }

    @Synchronized
    override fun startBle(
        deviceId: String, deviceName: String, imageCount: Int, textCount: Int, pdfCount: Int
    ) {
        require(deviceId.isNotBlank())
        initialize()
        if (bleRunning.get()) stopBle()
        bleDeviceId = deviceId
        bleDeviceName = deviceName
        val identity = NoteLinkIdentityCodec.encode(DeviceId(deviceId), deviceName, imageCount, textCount, pdfCount)
        val advertisement = advertisement(imageCount, textCount, pdfCount)
        checkCall(library.nl_ble_start(identity.memory(), identity.size, advertisement.memory(), advertisement.size), "Unable to start BLE")
        bleRunning.set(true)
    }

    override fun updateBleCounts(imageCount: Int, textCount: Int, pdfCount: Int) {
        check(bleRunning.get()) { "BLE is not running" }
        val identity = NoteLinkIdentityCodec.encode(
            DeviceId(bleDeviceId), bleDeviceName, imageCount, textCount, pdfCount
        )
        val advertisement = advertisement(imageCount, textCount, pdfCount)
        checkCall(
            library.nl_ble_update(
                identity.memory(), identity.size,
                advertisement.memory(), advertisement.size
            ),
            "Unable to update BLE queue counts"
        )
    }

    override fun pollBleCommand(timeoutMillis: Int): ByteArray? {
        check(bleRunning.get()) { "BLE is not running" }
        require(timeoutMillis >= 0)
        val deadlineNanos = System.nanoTime() + timeoutMillis.toLong() * 1_000_000L
        while (true) {
            val remainingMillis = if (timeoutMillis == 0) 0 else
                ((deadlineNanos - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val packet = pollNativePacket(remainingMillis) ?: return null
            if (!BleTransportFrameCodec.isFrame(packet)) {
                commandReassembler.reset()
                lastCommandWasFramed = false
                return packet
            }
            val complete = try {
                commandReassembler.add(packet)
            } catch (_: IllegalArgumentException) {
                // A stopped GATT service can still deliver tail writes from its old connection.
                // Discard that abandoned message and wait for the next complete first frame.
                commandReassembler.reset()
                if (System.nanoTime() >= deadlineNanos) return null
                continue
            }
            if (complete != null) {
                lastCommandWasFramed = true
                return complete
            }
            if (System.nanoTime() >= deadlineNanos) return null
        }
    }

    override fun respondBle(value: ByteArray) {
        check(bleRunning.get()) { "BLE is not running" }
        if (!lastCommandWasFramed) {
            checkCall(library.nl_ble_respond(value.memory(), value.size), "Unable to send BLE response")
        } else {
            BleTransportFrameCodec.fragment(value, NEXT_MESSAGE_ID.getAndIncrement())
                .forEach { frame ->
                    checkCall(library.nl_ble_respond(frame.memory(), frame.size), "Unable to send BLE response frame")
                }
        }
    }

    @Synchronized
    override fun stopBle() {
        if (bleRunning.getAndSet(false)) library.nl_ble_stop()
        commandReassembler.reset()
        lastCommandWasFramed = true
    }

    override fun lanInfo(): WindowsLanInfo {
        initialize()
        val address = Memory(LAN_ADDRESS_BYTES.toLong())
        val ssid = Memory(SSID_BYTES.toLong())
        val ssidLength = library.nl_lan_info(address, LAN_ADDRESS_BYTES, ssid, SSID_BYTES)
        if (ssidLength < 0) throw failure("Unable to read the active LAN")
        return WindowsLanInfo(
            address.getString(0, Charsets.UTF_8.name()),
            ssid.getByteArray(0, ssidLength).decodeToString()
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

    private fun advertisement(
        imageCount: Int, textCount: Int, pdfCount: Int
    ): ByteArray = NoteLinkAdvertisementCodec.encode(
        // A 128-bit service UUID plus this eight-byte payload fits in one
        // legacy connectable advertisement. The full name remains available
        // from the GATT identity characteristic after connection.
        TransferCrypto.sha256(bleDeviceId.encodeToByteArray()), "", imageCount, textCount, pdfCount
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
        private val NEXT_MESSAGE_ID = AtomicInteger(1)
        private const val CAP_BLE_PERIPHERAL = 1
        private const val CAP_LAN = 2
        private const val CAP_DPAPI = 4
        private const val MAX_CONTROL_BYTES = 64 * 1024
        private const val MAX_PROTECTED_BYTES = 1024 * 1024
        private const val LAN_ADDRESS_BYTES = 64
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

    private fun pollNativePacket(timeoutMillis: Int): ByteArray? {
        val output = Memory(MAX_CONTROL_BYTES.toLong())
        val result = library.nl_ble_poll(output, MAX_CONTROL_BYTES, timeoutMillis)
        if (result == 0) return null
        if (result > 0) return output.getByteArray(0, result)
        val required = -result
        require(required in 1..MAX_CONTROL_BYTES) { "Invalid BLE command size $required" }
        // The C ABI removes an oversized command from its event queue. Keeping the maximum
        // buffer in this adapter makes this branch an ABI-corruption guard, not a retry path.
        throw WindowsNativeException("BLE command exceeded the native event buffer")
    }
}
