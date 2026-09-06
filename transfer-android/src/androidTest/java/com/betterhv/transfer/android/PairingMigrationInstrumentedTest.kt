@file:Suppress("ApplySharedPref")

package com.betterhv.transfer.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingMigrationInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        context.deleteDatabase("notelink_pairings.db")
        context.getSharedPreferences(AndroidPairingController.PREFS, Context.MODE_PRIVATE).edit(commit = true) {clear()}
    }

    @After fun cleanUp() = reset()

    @Test fun legacySinglePairingMigratesWithoutChangingSecret() {
        val secret = ByteArray(32) { (it * 3).toByte() }
        context.getSharedPreferences(AndroidPairingController.PREFS, Context.MODE_PRIVATE).edit(commit = true) {
                putString(AndroidPairingController.KEY_LOCAL_ID, "note-id")
                .putString(AndroidPairingController.KEY_PEER_ID, AndroidPairingController.LEGACY_PHONE_ID)
                .putString(AndroidPairingController.KEY_PEER_NAME, "Old NoteLink")
                .putLong(AndroidPairingController.KEY_PAIRED_AT, 1234L)
                .putString(AndroidPairingController.KEY_SECRET, encryptLikeLegacyApp(secret))
            }

        val controller = AndroidPairingController(context)
        val migrated = controller.pairedClients.single()
        assertEquals(AndroidPairingController.LEGACY_PHONE_ID, migrated.id)
        assertEquals(1234L, migrated.pairedAt)
        assertTrue(migrated.legacy)
        assertArrayEquals(secret, controller.sharedKey(migrated.id))
    }

    private fun encryptLikeLegacyApp(value: ByteArray): String {
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                "betterhv-transfer-master-v1",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value), Base64.NO_WRAP)
    }
}
