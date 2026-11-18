package com.lumo.tv.data

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement des identifiants qui passent par la synchro (AES-256-GCM).
 * La clé ne quitte jamais les appareils : elle vient du compte principal (ou du code d'appairage).
 */
object Crypto {
    private fun key(secret: String): SecretKeySpec =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(("lumo-enc|$secret").toByteArray()), "AES")

    fun encrypt(plain: String, secret: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(secret), GCMParameterSpec(128, iv))
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + out, Base64.NO_WRAP)
    }

    fun decrypt(data: String, secret: String): String? = try {
        val raw = Base64.decode(data, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(secret), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (_: Exception) { null }
}
