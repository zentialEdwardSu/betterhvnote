package com.betterhv.transfer.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.betterhv.transfer.core.PairedDevice
import com.betterhv.transfer.core.TransferCrypto
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-level pairing. It intentionally does not create an Android Bluetooth bond. */
class AndroidPairingController(context: Context) : PairingController {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var pendingKeyPair: KeyPair? = null
    private var pendingOffer: PairingOffer? = null
    override val localDeviceId: String get() = getOrCreateLocalDeviceId()

    override val pairedDevice: PairedDevice?
        get() {
            val id = prefs.getString(KEY_PEER_ID, null) ?: return null
            return PairedDevice(
                id, prefs.getString(KEY_PEER_NAME, id) ?: id,
                prefs.getLong(KEY_PAIRED_AT, 0L)
            )
        }

    override fun begin(localDeviceName: String): PairingOffer {
        val pair = TransferCrypto.generatePairingKeyPair()
        pendingKeyPair = pair
        return PairingOffer(
            deviceId = localDeviceId, deviceName = localDeviceName,
            publicKey = pair.public.encoded, nonce = TransferCrypto.randomBytes(16)
        ).also { pendingOffer = it }
    }

    override fun accept(offer: PairingOffer, remoteDeviceName: String): PairingConfirmation {
        val pair = pendingKeyPair ?: TransferCrypto.generatePairingKeyPair().also { pendingKeyPair = it }
        val local = pendingOffer ?: PairingOffer(
            localDeviceId, remoteDeviceName, pair.public.encoded, TransferCrypto.randomBytes(16)
        ).also { pendingOffer = it }
        val secret = TransferCrypto.sharedSecret(pair, offer.publicKey)
        val first = if (local.deviceId <= offer.deviceId) local else offer
        val second = if (first === local) offer else local
        val transcript = first.deviceId.encodeToByteArray() + first.nonce + first.publicKey +
            second.deviceId.encodeToByteArray() + second.nonce + second.publicKey
        val key = TransferCrypto.hkdfSha256(secret, first.nonce + second.nonce, INFO, 32)
        return PairingConfirmation(
            local.deviceId, offer.deviceId, offer.deviceName.ifBlank { remoteDeviceName },
            TransferCrypto.verificationCode(key, transcript), key
        )
    }

    override fun confirm(confirmation: PairingConfirmation): PairedDevice {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_PEER_ID, confirmation.remoteDeviceId)
            .putString(KEY_PEER_NAME, confirmation.remoteDeviceName)
            .putLong(KEY_PAIRED_AT, now)
            .putString(KEY_SECRET, encrypt(confirmation.sharedKey))
            .apply()
        pendingKeyPair = null
        pendingOffer = null
        return PairedDevice(confirmation.remoteDeviceId, confirmation.remoteDeviceName, now)
    }

    override fun unpair() {
        prefs.edit().remove(KEY_PEER_ID).remove(KEY_PEER_NAME).remove(KEY_PAIRED_AT).remove(KEY_SECRET).apply()
    }

    /** Updates only the user-facing peer name while preserving the device ID and long-term key. */
    fun updatePairedDeviceName(name: String): PairedDevice? {
        val current = pairedDevice ?: return null
        require(name.isNotBlank()) { "设备名称不能为空" }
        prefs.edit().putString(KEY_PEER_NAME, name).apply()
        return current.copy(name = name)
    }

    override fun sharedKey(deviceId: String): ByteArray? {
        if (prefs.getString(KEY_PEER_ID, null) != deviceId) return null
        return prefs.getString(KEY_SECRET, null)?.let(::decrypt)
    }

    /** Numeric fallback for devices whose vendor BLE stack cannot complete GATT pairing. */
    fun confirmManual(remoteDeviceId: String, remoteDeviceName: String, sixDigitCode: String): PairedDevice {
        require(sixDigitCode.length == 6 && sixDigitCode.all(Char::isDigit)) { "配对码必须是六位数字" }
        val key = TransferCrypto.hkdfSha256(
            sixDigitCode.encodeToByteArray(), "BetterHv-manual-v1".encodeToByteArray(),
            "paired-device".encodeToByteArray(), 32
        )
        return confirm(PairingConfirmation(localDeviceId, remoteDeviceId, remoteDeviceName, sixDigitCode, key))
    }

    private fun getOrCreateLocalDeviceId(): String = prefs.getString(KEY_LOCAL_ID, null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_LOCAL_ID, it).apply() }

    private fun masterKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private fun encrypt(bytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(bytes), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): ByteArray {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return cipher.doFinal(packed.copyOfRange(12, packed.size))
    }

    private companion object {
        const val PREFS = "betterhv_transfer_pairing"
        const val KEY_LOCAL_ID = "local_id"
        const val KEY_PEER_ID = "peer_id"
        const val KEY_PEER_NAME = "peer_name"
        const val KEY_PAIRED_AT = "paired_at"
        const val KEY_SECRET = "secret"
        const val KEYSTORE_ALIAS = "betterhv-transfer-master-v1"
        val INFO = "betterhv-transfer-pairing-v1".toByteArray(StandardCharsets.UTF_8)
    }
}
