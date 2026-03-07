package com.fieldiq.security

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption service for sensitive tokens stored at rest.
 *
 * Used to encrypt/decrypt Google OAuth access tokens and refresh tokens in the
 * `calendar_integrations` table. AES-256-GCM provides both confidentiality and
 * integrity (authenticated encryption), preventing both reading and tampering
 * with stored tokens.
 *
 * **Encryption format:** The encrypted output is a Base64 string containing
 * `IV (12 bytes) + ciphertext + GCM auth tag (16 bytes)`. The IV is randomly
 * generated for each encryption operation, ensuring that encrypting the same
 * plaintext twice produces different ciphertexts.
 *
 * **Key management:** The encryption key is loaded from `fieldiq.encryption.token-key`
 * in application config. In production, this MUST be a 32-byte (256-bit) key
 * stored in AWS Secrets Manager or similar. The dev default is NOT secure.
 *
 * **Note:** This is NOT a JPA AttributeConverter because the encryption key comes
 * from Spring-managed config. Instead, [GoogleCalendarService] calls encrypt/decrypt
 * explicitly before saving/loading tokens. This avoids the complexity of injecting
 * Spring beans into JPA converters.
 *
 * @property key The AES-256 encryption key, derived from config. Padded/truncated
 *   to exactly 32 bytes for AES-256.
 * @see com.fieldiq.domain.CalendarIntegration for the entity with encrypted token fields.
 * @see com.fieldiq.service.GoogleCalendarService for where encrypt/decrypt is called.
 */
@Component
class TokenEncryptionConverter(
    private val key: ByteArray,
) {

    companion object {
        /** AES-GCM cipher algorithm identifier. */
        private const val ALGORITHM = "AES/GCM/NoPadding"

        /** GCM initialization vector size in bytes. 12 bytes is the recommended IV length for GCM. */
        private const val IV_LENGTH = 12

        /** GCM authentication tag size in bits. 128 bits provides full security. */
        private const val TAG_LENGTH_BITS = 128

        /** AES key size in bytes (256 bits). */
        private const val KEY_LENGTH = 32

        /**
         * Derives a fixed-length AES key from a config string.
         *
         * Pads short keys with zeros or truncates long keys to exactly 32 bytes.
         * In production, the key should already be 32 bytes and this is a no-op.
         *
         * @param keyString The raw key string from config.
         * @return A 32-byte key suitable for AES-256.
         */
        fun deriveKeyBytes(keyString: String): ByteArray {
            val keyBytes = keyString.toByteArray()
            return if (keyBytes.size >= KEY_LENGTH) {
                keyBytes.copyOfRange(0, KEY_LENGTH)
            } else {
                keyBytes.copyOf(KEY_LENGTH) // pads with zeros
            }
        }
    }

    /**
     * Creates a [TokenEncryptionConverter] with a key from config.
     *
     * The key string is padded or truncated to exactly 32 bytes for AES-256.
     * In production, the key should already be 32 bytes.
     *
     * @param properties FieldIQ config containing the encryption key.
     */
    @Autowired
    constructor(properties: com.fieldiq.config.FieldIQProperties) : this(
        deriveKeyBytes(properties.encryption.tokenKey),
    )

    /**
     * Encrypts a plaintext token string using AES-256-GCM.
     *
     * Each call generates a fresh random IV, so encrypting the same plaintext
     * twice produces different ciphertexts. The output format is:
     * `Base64(IV + ciphertext + auth_tag)`.
     *
     * @param plaintext The token to encrypt (e.g., a Google OAuth access token).
     * @return Base64-encoded ciphertext containing IV + encrypted data + auth tag.
     * @throws javax.crypto.AEADBadTagException if the encryption key is corrupted.
     */
    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray())
        val combined = iv + encrypted
        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypts an AES-256-GCM encrypted token back to plaintext.
     *
     * Extracts the IV from the first 12 bytes of the decoded input, then
     * decrypts the remaining ciphertext + auth tag.
     *
     * @param ciphertext Base64-encoded encrypted token (from [encrypt]).
     * @return The original plaintext token string.
     * @throws javax.crypto.AEADBadTagException if the ciphertext was tampered with
     *   or the wrong encryption key is used.
     * @throws IllegalArgumentException if the ciphertext format is invalid.
     */
    fun decrypt(ciphertext: String): String {
        val combined = Base64.getDecoder().decode(ciphertext)
        require(combined.size > IV_LENGTH) { "Ciphertext too short to contain IV" }

        val iv = combined.copyOfRange(0, IV_LENGTH)
        val encrypted = combined.copyOfRange(IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(encrypted))
    }
}
