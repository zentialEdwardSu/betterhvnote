package com.betterhv.transfer.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val database = PairingDatabase(appContext).apply {
        setWriteAheadLoggingEnabled(true)
    }
    private var pendingKeyPair: KeyPair? = null
    private var pendingOffer: PairingOffer? = null

    init {
        migrateLegacyPairing()
    }

    override val localDeviceId: String get() = getOrCreateLocalDeviceId()

    override val pairedClients: List<PairedDevice>
        get() = database.readableDatabase.query(
            TABLE, COLUMNS, null, null, null, null, "last_used_at DESC, paired_at DESC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(PairedDevice(
                        id = cursor.getString(0),
                        name = cursor.getString(1),
                        pairedAt = cursor.getLong(2),
                        identityHash = cursor.getString(3),
                        lastUsedAt = cursor.getLong(4),
                        legacy = cursor.getInt(5) != 0
                    ))
                }
            }
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
        val value = PairedDevice(
            confirmation.remoteDeviceId,
            confirmation.remoteDeviceName,
            now,
            identityHash = identityHash(confirmation.remoteDeviceId),
            lastUsedAt = now
        )
        upsert(value, encrypt(confirmation.sharedKey))
        pendingKeyPair = null
        pendingOffer = null
        return value
    }

    override fun unpair(deviceId: String?) {
        val writable = database.writableDatabase
        if (deviceId == null) writable.delete(TABLE, null, null)
        else writable.delete(TABLE, "device_id=?", arrayOf(deviceId))
    }

    override fun rename(deviceId: String, name: String): PairedDevice {
        val normalized = name.trim()
        require(normalized.isNotBlank()) { "设备名称不能为空" }
        val current = requireNotNull(pairedClient(deviceId)) { "配对设备不存在" }
        val values = ContentValues().apply { put("device_name", normalized) }
        check(database.writableDatabase.update(TABLE, values, "device_id=?", arrayOf(deviceId)) == 1)
        return current.copy(name = normalized)
    }

    override fun markLastUsed(deviceId: String): PairedDevice {
        val current = requireNotNull(pairedClient(deviceId)) { "配对设备不存在" }
        val now = System.currentTimeMillis()
        val values = ContentValues().apply { put("last_used_at", now) }
        check(database.writableDatabase.update(TABLE, values, "device_id=?", arrayOf(deviceId)) == 1)
        return current.copy(lastUsedAt = now)
    }

    /** Updates only the user-facing peer name while preserving the device ID and long-term key. */
    fun updatePairedDeviceName(name: String): PairedDevice? = pairedDevice?.let { rename(it.id, name) }

    fun updatePairedDeviceName(deviceId: String, name: String): PairedDevice = rename(deviceId, name)

    override fun sharedKey(deviceId: String): ByteArray? = database.readableDatabase.query(
        TABLE, arrayOf("encrypted_secret"), "device_id=?", arrayOf(deviceId), null, null, null
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else decrypt(cursor.getString(0))
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

    /** Replaces a migrated legacy alias after the old key authenticates the sender's full GATT identity. */
    fun resolveLegacyIdentity(oldDeviceId: String, identity: BleIdentity): PairedDevice {
        val old = requireNotNull(pairedClient(oldDeviceId)) { "旧配对设备不存在" }
        require(old.legacy) { "配对设备不需要身份迁移" }
        val secret = database.readableDatabase.query(
            TABLE, arrayOf("encrypted_secret"), "device_id=?", arrayOf(oldDeviceId), null, null, null
        ).use { cursor -> require(cursor.moveToFirst()); cursor.getString(0) }
        val resolved = old.copy(
            id = identity.deviceId,
            name = identity.deviceName,
            identityHash = identityHash(identity.deviceId),
            lastUsedAt = System.currentTimeMillis(),
            legacy = false
        )
        val writable = database.writableDatabase
        writable.beginTransaction()
        try {
            writable.delete(TABLE, "device_id=?", arrayOf(oldDeviceId))
            insert(writable, resolved, secret)
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        return resolved
    }

    private fun upsert(device: PairedDevice, encryptedSecret: String) {
        insert(database.writableDatabase, device, encryptedSecret, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insert(
        writable: SQLiteDatabase,
        device: PairedDevice,
        encryptedSecret: String,
        conflictAlgorithm: Int = SQLiteDatabase.CONFLICT_ABORT
    ) {
        val values = ContentValues().apply {
            put("device_id", device.id)
            put("device_name", device.name)
            put("paired_at", device.pairedAt)
            put("identity_hash", device.identityHash)
            put("last_used_at", device.lastUsedAt)
            put("legacy", if (device.legacy) 1 else 0)
            put("encrypted_secret", encryptedSecret)
        }
        check(writable.insertWithOnConflict(TABLE, null, values, conflictAlgorithm) != -1L)
    }

    private fun migrateLegacyPairing() {
        if (prefs.getBoolean(KEY_MULTI_CLIENT_MIGRATED, false)) return
        val id = prefs.getString(KEY_PEER_ID, null)
        val secret = prefs.getString(KEY_SECRET, null)
        if (id != null && secret != null && pairedClient(id) == null) {
            val pairedAt = prefs.getLong(KEY_PAIRED_AT, System.currentTimeMillis())
            insert(
                database.writableDatabase,
                PairedDevice(
                    id,
                    prefs.getString(KEY_PEER_NAME, id) ?: id,
                    pairedAt,
                    identityHash = id.takeUnless { it == LEGACY_PHONE_ID }?.let(::identityHash),
                    lastUsedAt = pairedAt,
                    legacy = id == LEGACY_PHONE_ID
                ),
                secret
            )
        }
        prefs.edit()
            .remove(KEY_PEER_ID)
            .remove(KEY_PEER_NAME)
            .remove(KEY_PAIRED_AT)
            .remove(KEY_SECRET)
            .putBoolean(KEY_MULTI_CLIENT_MIGRATED, true)
            .apply()
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

    private class PairingDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE $TABLE(
                device_id TEXT PRIMARY KEY,
                device_name TEXT NOT NULL,
                paired_at INTEGER NOT NULL,
                identity_hash TEXT,
                last_used_at INTEGER NOT NULL,
                legacy INTEGER NOT NULL DEFAULT 0,
                encrypted_secret TEXT NOT NULL
            )""".trimIndent())
            db.execSQL("CREATE INDEX paired_clients_last_used ON $TABLE(last_used_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        const val LEGACY_PHONE_ID = "betterhv-phone"
        const val PREFS = "betterhv_transfer_pairing"
        const val KEY_LOCAL_ID = "local_id"
        const val KEY_PEER_ID = "peer_id"
        const val KEY_PEER_NAME = "peer_name"
        const val KEY_PAIRED_AT = "paired_at"
        const val KEY_SECRET = "secret"
        private const val KEY_MULTI_CLIENT_MIGRATED = "multi_client_migrated_v1"
        private const val KEYSTORE_ALIAS = "betterhv-transfer-master-v1"
        private const val DATABASE = "notelink_pairings.db"
        private const val TABLE = "paired_clients"
        private val COLUMNS = arrayOf(
            "device_id", "device_name", "paired_at", "identity_hash", "last_used_at", "legacy"
        )
        val INFO = "betterhv-transfer-pairing-v1".toByteArray(StandardCharsets.UTF_8)

        fun identityHash(deviceId: String): String = TransferCrypto.sha256(deviceId.encodeToByteArray())
            .copyOf(5)
            .joinToString("") { "%02x".format(it) }
    }
}
