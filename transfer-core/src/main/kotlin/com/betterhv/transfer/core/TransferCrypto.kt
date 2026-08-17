package com.betterhv.transfer.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object TransferCrypto {
    private val random = SecureRandom()

    fun generatePairingKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), random)
        generateKeyPair()
    }

    fun publicKey(encoded: ByteArray) = KeyFactory.getInstance("EC")
        .generatePublic(X509EncodedKeySpec(encoded))

    fun sharedSecret(local: KeyPair, remotePublicKey: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(local.private)
            doPhase(publicKey(remotePublicKey), true)
            generateSecret()
        }

    fun hkdfSha256(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32))
        val extract = hmac(if (salt.isEmpty()) ByteArray(32) else salt, secret)
        val out = ByteArrayOutputStream(length)
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            previous = hmac(extract, previous + info + counter.toByte())
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun verificationCode(sharedSecret: ByteArray, transcript: ByteArray): String {
        val value = ByteBuffer.wrap(hmac(sharedSecret, transcript), 0, 4).int.toLong() and 0xffffffffL
        return (value % 1_000_000L).toString().padStart(6, '0')
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            if (aad.isNotEmpty()) updateAAD(aad)
            doFinal(plaintext)
        }

    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            if (aad.isNotEmpty()) updateAAD(aad)
            doFinal(ciphertext)
        }

    fun nonce(prefix: Int, counter: Long): ByteArray = ByteBuffer.allocate(12)
        .putInt(prefix).putLong(counter).array()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(input)
    }
}

class ReplayWindow(private var lastCounter: Long = -1L) {
    @Synchronized fun accept(counter: Long) {
        require(counter > lastCounter) { "Replayed or out-of-order frame: $counter <= $lastCounter" }
        lastCounter = counter
    }
}
