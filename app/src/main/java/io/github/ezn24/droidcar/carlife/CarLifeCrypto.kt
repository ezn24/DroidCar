/*
 * Ported from FT (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Modified for DroidCar.
 */
package io.github.ezn24.droidcar.carlife

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class CarLifeCrypto(
    private val log: (String) -> Unit = {},
) {
    @Volatile var started = false

    @Volatile private var key: SecretKeySpec? = null

    @Volatile private var incoming = false

    @Volatile private var outgoing = false
    private var decryptFailureLogged = false

    val active: Boolean get() = outgoing

    @Synchronized
    fun reset() {
        started = false
        key = null
        incoming = false
        outgoing = false
        decryptFailureLogged = false
    }

    @Synchronized
    fun aesKeyRequest(rsaPublicKeyBase64: String): String? {
        key = null
        incoming = false
        outgoing = false
        decryptFailureLogged = false
        return runCatching {
            val spki =
                java.util.Base64
                    .getMimeDecoder()
                    .decode(rsaPublicKeyBase64.trim())
            val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki))
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val rnd = SecureRandom()
            val k = String(CharArray(16) { alphabet[rnd.nextInt(alphabet.length)] })
            val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            c.init(Cipher.ENCRYPT_MODE, pub)
            val wrapped = c.doFinal(k.toByteArray(Charsets.UTF_8))
            val request =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(wrapped)
            key = SecretKeySpec(k.toByteArray(Charsets.UTF_8), "AES")
            request
        }.getOrNull()
    }

    @Synchronized
    fun armIncoming() {
        incoming = true
    }

    @Synchronized
    fun enableOutgoing(): Boolean {
        if (key == null || !incoming || outgoing) return false
        outgoing = true
        return true
    }

    fun encryptOut(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) return payload
        val k = synchronized(this) { if (outgoing) key else null } ?: return payload
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, k)
        return c.doFinal(payload)
    }

    fun decryptIn(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) return payload
        val k = synchronized(this) { if (incoming) key else null } ?: return payload
        return try {
            val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, k)
            c.doFinal(payload)
        } catch (e: java.security.GeneralSecurityException) {
            val report =
                synchronized(this) {
                    if (key !== k || decryptFailureLogged) {
                        false
                    } else {
                        decryptFailureLogged = true
                        true
                    }
                }
            // Handshake replies can still be plaintext after incoming AES is armed.
            if (report) log("incoming AES did not decode ${payload.size} bytes (${e.javaClass.simpleName}); trying plaintext")
            payload
        }
    }
}
