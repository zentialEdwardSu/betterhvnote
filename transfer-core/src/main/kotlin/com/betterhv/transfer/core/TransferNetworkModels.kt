package com.betterhv.transfer.core

import java.net.Inet4Address
import java.net.InetAddress
import java.text.Normalizer
import java.util.UUID

enum class TransferMode(val bit: Int) {
    LAN(1),
    WIFI_DIRECT(2)
}

object TransferModes {
    const val NONE = 0
    const val LAN = 1
    const val WIFI_DIRECT = 2
    const val ALL = LAN or WIFI_DIRECT

    fun requireValid(flags: Int): Int = flags.also {
        require(it >= 0 && it and ALL.inv() == 0) { "Invalid transfer mode flags: $it" }
    }

    fun contains(flags: Int, mode: TransferMode): Boolean = flags and mode.bit != 0

    fun describe(flags: Int): String = buildList {
        if (contains(flags, TransferMode.LAN)) add(TransferMode.LAN.name)
        if (contains(flags, TransferMode.WIFI_DIRECT)) add(TransferMode.WIFI_DIRECT.name)
    }.takeIf { it.isNotEmpty() }?.joinToString("+") ?: "NONE"
}

enum class SsidMatch { MATCH, MISMATCH, UNKNOWN }

data class NetworkEndpoint(val host: String, val port: Int = SharedFileTransfer.PORT) {
    init {
        require(port in 1..65535) { "Invalid endpoint port: $port" }
        require(host.isNotBlank()) { "Endpoint host is blank" }
    }

    fun validated(): NetworkEndpoint {
        val address = runCatching { InetAddress.getByName(host) }
            .getOrElse { throw IllegalArgumentException("Invalid endpoint host: $host", it) }
        require(address is Inet4Address) { "Only IPv4 endpoints are supported" }
        require(!address.isAnyLocalAddress && !address.isMulticastAddress) { "Unsafe endpoint address: $host" }
        val raw = address.address
        require(!(raw[0].toInt() and 0xff == 255 && raw.drop(1).all { (it.toInt() and 0xff) == 255 })) {
            "Broadcast endpoint is not allowed"
        }
        require((raw.last().toInt() and 0xff) != 255) { "Broadcast endpoint is not allowed" }
        return copy(host = address.hostAddress)
    }
}

data class HighBandwidthEndpoint(
    val deviceAddress: String,
    val ownerIp: String,
    val networkName: String,
    val passphrase: String,
    val port: Int = SharedFileTransfer.PORT
) {
    init {
        require(deviceAddress.isNotBlank())
        require(networkName.isNotBlank())
        require(passphrase.isNotBlank())
        NetworkEndpoint(ownerIp, port).validated()
    }
}

data class DeviceCapabilities(
    val protocolVersion: Int = BleQueueProtocol.VERSION,
    val modes: Int,
    val lanEndpoint: NetworkEndpoint? = null,
    val ssidFingerprint: ByteArray? = null,
    val wifiDirectEndpoint: HighBandwidthEndpoint? = null,
    val extensions: Int = 0
) {
    init {
        require(protocolVersion == BleQueueProtocol.VERSION) { "Unsupported capability version $protocolVersion" }
        TransferModes.requireValid(modes)
        require(ssidFingerprint == null || ssidFingerprint.size == SSID_FINGERPRINT_BYTES)
        require(!TransferModes.contains(modes, TransferMode.LAN) || lanEndpoint != null) {
            "LAN capability requires an endpoint"
        }
        // Join-capable peers advertise WIFI_DIRECT without hosting an endpoint.
    }

    override fun equals(other: Any?): Boolean = other is DeviceCapabilities &&
        protocolVersion == other.protocolVersion && modes == other.modes && lanEndpoint == other.lanEndpoint &&
        ssidFingerprint.contentEqualsNullable(other.ssidFingerprint) &&
        wifiDirectEndpoint == other.wifiDirectEndpoint && extensions == other.extensions

    override fun hashCode(): Int = 31 * modes + (ssidFingerprint?.contentHashCode() ?: 0)

    companion object { const val SSID_FINGERPRINT_BYTES = 8 }
}

data class CapabilityNegotiation(
    val remote: DeviceCapabilities,
    val ssidMatch: SsidMatch
)

enum class TransferErrorCode {
    UNSUPPORTED_PROTOCOL,
    UNSUPPORTED_MODE,
    INVALID_ENDPOINT,
    NETWORK_UNKNOWN,
    SSID_MISMATCH,
    CONNECTION_TIMEOUT,
    CONNECTION_REFUSED,
    UNREACHABLE,
    AUTHENTICATION_FAILED,
    INTEGRITY_FAILED,
    CANCELLED,
    BUSY,
    INTERNAL
}

data class TransferFailure(
    val code: TransferErrorCode,
    val message: String,
    val recoverable: Boolean,
    val causeMode: TransferMode? = null
)

object TransferNetworkSecurity {
    private val UNKNOWN_SSIDS = setOf("<unknown ssid>", "unknown ssid", "0x")

    fun normalizeSsid(raw: String?): String? {
        var value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value = value.substring(1, value.lastIndex)
        }
        if (value.isBlank() || value.lowercase() in UNKNOWN_SSIDS) return null
        return Normalizer.normalize(value, Normalizer.Form.NFC)
    }

    fun ssidFingerprint(pairingKey: ByteArray, rawSsid: String?): ByteArray? =
        normalizeSsid(rawSsid)?.let { normalized ->
            TransferCrypto.hmacSha256(pairingKey, "ssid:v2:$normalized".encodeToByteArray())
                .copyOf(DeviceCapabilities.SSID_FINGERPRINT_BYTES)
        }

    fun compare(local: ByteArray?, remote: ByteArray?): SsidMatch = when {
        local == null || remote == null -> SsidMatch.UNKNOWN
        local.contentEquals(remote) -> SsidMatch.MATCH
        else -> SsidMatch.MISMATCH
    }

    fun sessionKey(pairingKey: ByteArray, operationId: UUID, sessionNonce: ByteArray, purpose: String): ByteArray {
        require(sessionNonce.size == SESSION_NONCE_BYTES)
        require(purpose == "probe" || purpose == "file")
        return TransferCrypto.hkdfSha256(
            pairingKey,
            sessionNonce,
            "notelink:v2:$purpose:$operationId".encodeToByteArray(),
            32
        )
    }

    const val SESSION_NONCE_BYTES = 16
}

object TransferRouteSelector {
    /** Ordered candidates; callers may attempt the second route only after the LAN attempt fails. */
    fun candidates(local: DeviceCapabilities, negotiation: CapabilityNegotiation): List<TransferMode> = buildList {
        val remote = negotiation.remote
        if (negotiation.ssidMatch == SsidMatch.MATCH && local.lanEndpoint != null && remote.lanEndpoint != null &&
            TransferModes.contains(local.modes, TransferMode.LAN) && TransferModes.contains(remote.modes, TransferMode.LAN)
        ) add(TransferMode.LAN)
        if (remote.wifiDirectEndpoint != null && TransferModes.contains(local.modes, TransferMode.WIFI_DIRECT) &&
            TransferModes.contains(remote.modes, TransferMode.WIFI_DIRECT)
        ) add(TransferMode.WIFI_DIRECT)
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> contentEquals(other)
}
