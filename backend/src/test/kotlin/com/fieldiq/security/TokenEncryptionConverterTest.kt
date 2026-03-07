package com.fieldiq.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TokenEncryptionConverter] — AES-256-GCM encryption for OAuth tokens.
 *
 * Tests cover:
 * 1. **Round-trip:** encrypt then decrypt returns the original plaintext.
 * 2. **Uniqueness:** encrypting the same plaintext twice produces different ciphertexts
 *    (because of random IV generation).
 * 3. **Tampering detection:** modified ciphertext fails decryption with an exception.
 * 4. **Wrong key detection:** ciphertext encrypted with one key cannot be decrypted with another.
 * 5. **Key derivation:** config strings are properly padded/truncated to 32 bytes.
 *
 * @see TokenEncryptionConverter for the service under test.
 */
class TokenEncryptionConverterTest {

    private lateinit var converter: TokenEncryptionConverter

    private val testKey = "test-encryption-key-exactly-32b!"

    /**
     * Creates the converter with a known test key before each test.
     */
    @BeforeEach
    fun setup() {
        converter = TokenEncryptionConverter(testKey.toByteArray().copyOfRange(0, 32))
    }

    @Nested
    @DisplayName("encrypt and decrypt")
    inner class EncryptDecrypt {

        /**
         * The fundamental test: encrypting then decrypting must return the original value.
         */
        @Test
        @DisplayName("round-trips a token correctly")
        fun roundTrip() {
            val token = "ya29.a0AfH6SMBx..." // mock Google access token format
            val encrypted = converter.encrypt(token)
            val decrypted = converter.decrypt(encrypted)
            assertEquals(token, decrypted)
        }

        /**
         * Must work with long tokens (refresh tokens can be 500+ chars).
         */
        @Test
        @DisplayName("handles long tokens")
        fun longToken() {
            val token = "a".repeat(1000)
            val encrypted = converter.encrypt(token)
            val decrypted = converter.decrypt(encrypted)
            assertEquals(token, decrypted)
        }

        /**
         * Must work with empty strings (edge case).
         */
        @Test
        @DisplayName("handles empty string")
        fun emptyString() {
            val encrypted = converter.encrypt("")
            val decrypted = converter.decrypt(encrypted)
            assertEquals("", decrypted)
        }

        /**
         * Must work with special characters (tokens contain slashes, plus signs, etc.).
         */
        @Test
        @DisplayName("handles special characters in tokens")
        fun specialChars() {
            val token = "1//0dx...abc+def/ghi=jkl"
            val encrypted = converter.encrypt(token)
            val decrypted = converter.decrypt(encrypted)
            assertEquals(token, decrypted)
        }
    }

    @Nested
    @DisplayName("IV randomness")
    inner class IvRandomness {

        /**
         * Each encryption must produce a different ciphertext because the IV is random.
         * This is critical for security — without random IVs, an attacker could detect
         * when the same token is stored twice.
         */
        @Test
        @DisplayName("encrypting same plaintext twice produces different ciphertexts")
        fun differentCiphertexts() {
            val token = "ya29.a0AfH6SMBx..."
            val encrypted1 = converter.encrypt(token)
            val encrypted2 = converter.encrypt(token)
            assertNotEquals(encrypted1, encrypted2)
            // Both must still decrypt to the same value
            assertEquals(token, converter.decrypt(encrypted1))
            assertEquals(token, converter.decrypt(encrypted2))
        }
    }

    @Nested
    @DisplayName("tamper detection")
    inner class TamperDetection {

        /**
         * GCM's authentication tag should detect any modification to the ciphertext.
         */
        @Test
        @DisplayName("rejects tampered ciphertext")
        fun tampered() {
            val token = "ya29.a0AfH6SMBx..."
            val encrypted = converter.encrypt(token)
            // Flip a bit in the middle of the Base64 string
            val chars = encrypted.toCharArray()
            val midpoint = chars.size / 2
            chars[midpoint] = if (chars[midpoint] == 'A') 'B' else 'A'
            val tampered = String(chars)

            assertThrows(Exception::class.java) {
                converter.decrypt(tampered)
            }
        }

        /**
         * Truncated ciphertext should fail.
         */
        @Test
        @DisplayName("rejects truncated ciphertext")
        fun truncated() {
            val token = "ya29.a0AfH6SMBx..."
            val encrypted = converter.encrypt(token)
            val truncated = encrypted.substring(0, 10)

            assertThrows(Exception::class.java) {
                converter.decrypt(truncated)
            }
        }
    }

    @Nested
    @DisplayName("wrong key")
    inner class WrongKey {

        /**
         * Ciphertext encrypted with one key must not be decryptable with a different key.
         */
        @Test
        @DisplayName("rejects decryption with wrong key")
        fun wrongKey() {
            val token = "ya29.a0AfH6SMBx..."
            val encrypted = converter.encrypt(token)

            val wrongConverter = TokenEncryptionConverter(
                "different-key-exactly-32-bytes!".toByteArray().copyOf(32),
            )

            assertThrows(Exception::class.java) {
                wrongConverter.decrypt(encrypted)
            }
        }
    }

    @Nested
    @DisplayName("key derivation")
    inner class KeyDerivation {

        /**
         * Short keys should be padded to 32 bytes without errors.
         */
        @Test
        @DisplayName("pads short keys to 32 bytes")
        fun shortKey() {
            val key = TokenEncryptionConverter.deriveKeyBytes("short")
            assertEquals(32, key.size)
        }

        /**
         * Long keys should be truncated to 32 bytes without errors.
         */
        @Test
        @DisplayName("truncates long keys to 32 bytes")
        fun longKey() {
            val key = TokenEncryptionConverter.deriveKeyBytes("a".repeat(100))
            assertEquals(32, key.size)
        }

        /**
         * 32-byte keys should be used as-is.
         */
        @Test
        @DisplayName("uses exact 32-byte keys as-is")
        fun exactKey() {
            val keyString = "abcdefghijklmnopqrstuvwxyz123456"
            val key = TokenEncryptionConverter.deriveKeyBytes(keyString)
            assertEquals(32, key.size)
            assertArrayEquals(keyString.toByteArray(), key)
        }
    }
}
