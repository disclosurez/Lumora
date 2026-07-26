package com.lumora.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts/decrypts provider credentials using AES-256-GCM via Android KeyStore.
 * Falls back to a file-based key if KeyStore is unavailable (Fire OS / older devices).
 */
object CredentialEncryption {

    private const val KEYSTORE_ALIAS = "lumora_credential_key"
    private const val PREFS_NAME = "lumora_secure_prefs"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    private var fallbackKey: ByteArray? = null

    fun encrypt(context: Context, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = getOrCreateKey(context)
        val iv = ByteArray(IV_LENGTH).apply { SecureRandom().nextBytes(this) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(context: Context, encrypted: String): String? {
        return try {
            val combined = Base64.decode(encrypted, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val key = getOrCreateKey(context)
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun getOrCreateKey(context: Context): SecretKey {
        return try {
            // Android KeyStore
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                (keyStore.getEntry(KEYSTORE_ALIAS, null) as java.security.KeyStore.SecretKeyEntry).secretKey
            } else {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                keyGenerator.init(
                    KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            // Fallback: file-based key
            getOrCreateFallbackKey(context)
        }
    }

    private class RawSecretKey(bytes: ByteArray) : SecretKeySpec(bytes, "AES")

    private fun getOrCreateFallbackKey(context: Context): SecretKey {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString("fallback_key", null)
        return if (stored != null) {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            RawSecretKey(raw)
        } else {
            val key = ByteArray(32).apply { SecureRandom().nextBytes(this) }
            prefs.edit().putString("fallback_key", Base64.encodeToString(key, Base64.NO_WRAP)).apply()
            fallbackKey = key
            RawSecretKey(key)
        }
    }
}
