package com.lumora.plugin.js

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The hand-rolled PBKDF2-HMAC-SHA512 fallback (used on API 25, where SecretKeyFactory has no
 * "PBKDF2WithHmacSHA512") must produce byte-identical output to the platform implementation,
 * which the JVM test runtime does have.
 */
class Pbkdf2HmacSha512Test {

    @Test
    fun `manual pbkdf2 matches the platform implementation`() {
        val password = "correct horse battery staple".toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16) { it.toByte() }
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(PBEKeySpec(String(password, Charsets.UTF_8).toCharArray(), salt, 4096, 48 * 8))
            .encoded
        assertArrayEquals(expected, pbkdf2HmacSha512(password, salt, 4096, 48))
    }

    @Test
    fun `single iteration and a multi-block key length round-trip`() {
        val password = "pw".toByteArray(Charsets.UTF_8)
        val salt = byteArrayOf(1, 2, 3, 4)
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(PBEKeySpec("pw".toCharArray(), salt, 1, 100 * 8))
            .encoded
        assertArrayEquals(expected, pbkdf2HmacSha512(password, salt, 1, 100))
    }
}
