package com.fieldiq.security

import com.fieldiq.config.FieldIQProperties
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests for [HmacService] — the cryptographic foundation of FieldIQ's
 * cross-instance negotiation authentication.
 *
 * Tests cover:
 * 1. **Key derivation:** Deterministic HMAC-SHA256 key generation from instance secret + invite token.
 * 2. **Signing:** Consistent hex signature production from session ID + timestamp + body.
 * 3. **Validation:** Timestamp drift rejection, signature mismatch detection, and
 *    constant-time comparison correctness.
 *
 * **Testing approach:** [FieldIQProperties] is mocked via MockK to provide a known
 * instance secret. All crypto operations are tested with deterministic inputs to verify
 * reproducibility — the same inputs must always produce the same outputs.
 *
 * @see HmacService for the service under test.
 * @see HmacAuthenticationFilter for where validation is called on incoming requests.
 * @see com.fieldiq.service.CrossInstanceRelayClient for where signing is called on outbound requests.
 */
class HmacServiceTest {

    private val properties: FieldIQProperties = mockk()
    private val instanceProperties: FieldIQProperties.InstanceProperties = mockk()

    private lateinit var hmacService: HmacService

    private val testSecret = "test-instance-secret-32-bytes!!"
    private val testInviteToken = "invite-token-abc123"
    private val testSessionId = "550e8400-e29b-41d4-a716-446655440000"
    private val testBody = """{"action":"propose","roundNumber":1}"""

    /**
     * Creates the service with a mocked instance secret before each test.
     */
    @BeforeEach
    fun setup() {
        every { properties.instance } returns instanceProperties
        every { instanceProperties.secret } returns testSecret
        hmacService = HmacService(properties)
    }

    @Nested
    @DisplayName("deriveSessionKey")
    inner class DeriveSessionKey {

        /**
         * The same secret + invite token must always produce the same key.
         * This is critical: both instances must derive identical keys independently.
         */
        @Test
        @DisplayName("produces deterministic key from same inputs")
        fun deterministicKey() {
            val key1 = hmacService.deriveSessionKey(testInviteToken)
            val key2 = hmacService.deriveSessionKey(testInviteToken)
            assertArrayEquals(key1, key2)
        }

        /**
         * Different invite tokens must produce different session keys.
         * Each negotiation session gets its own unique key.
         */
        @Test
        @DisplayName("produces different keys for different invite tokens")
        fun differentTokensDifferentKeys() {
            val key1 = hmacService.deriveSessionKey("token-aaa")
            val key2 = hmacService.deriveSessionKey("token-bbb")
            assertFalse(key1.contentEquals(key2))
        }

        /**
         * HMAC-SHA256 always produces 32-byte (256-bit) output regardless of input size.
         */
        @Test
        @DisplayName("produces 32-byte key (HMAC-SHA256 output size)")
        fun keyLength() {
            val key = hmacService.deriveSessionKey(testInviteToken)
            assertEquals(32, key.size)
        }
    }

    @Nested
    @DisplayName("sign")
    inner class Sign {

        /**
         * The same inputs must always produce the same signature. This ensures
         * that the sender and receiver compute matching signatures.
         */
        @Test
        @DisplayName("produces deterministic signature from same inputs")
        fun deterministicSignature() {
            val key = hmacService.deriveSessionKey(testInviteToken)
            val timestamp = "2026-04-05T14:00:00Z"
            val sig1 = hmacService.sign(key, testSessionId, timestamp, testBody)
            val sig2 = hmacService.sign(key, testSessionId, timestamp, testBody)
            assertEquals(sig1, sig2)
        }

        /**
         * Signatures must be lowercase hex strings of length 64 (32 bytes * 2 hex chars).
         */
        @Test
        @DisplayName("produces 64-character lowercase hex string")
        fun hexFormat() {
            val key = hmacService.deriveSessionKey(testInviteToken)
            val sig = hmacService.sign(key, testSessionId, "2026-04-05T14:00:00Z", testBody)
            assertEquals(64, sig.length)
            assertTrue(sig.matches(Regex("[0-9a-f]{64}")))
        }

        /**
         * Changing any component of the message must produce a different signature.
         * This prevents an attacker from reusing a signature with a modified payload.
         */
        @Test
        @DisplayName("different bodies produce different signatures")
        fun differentBodies() {
            val key = hmacService.deriveSessionKey(testInviteToken)
            val timestamp = "2026-04-05T14:00:00Z"
            val sig1 = hmacService.sign(key, testSessionId, timestamp, testBody)
            val sig2 = hmacService.sign(key, testSessionId, timestamp, """{"action":"cancel"}""")
            assertNotEquals(sig1, sig2)
        }

        /**
         * Changing the timestamp must produce a different signature.
         */
        @Test
        @DisplayName("different timestamps produce different signatures")
        fun differentTimestamps() {
            val key = hmacService.deriveSessionKey(testInviteToken)
            val sig1 = hmacService.sign(key, testSessionId, "2026-04-05T14:00:00Z", testBody)
            val sig2 = hmacService.sign(key, testSessionId, "2026-04-05T15:00:00Z", testBody)
            assertNotEquals(sig1, sig2)
        }

        /**
         * Changing the session ID must produce a different signature.
         */
        @Test
        @DisplayName("different session IDs produce different signatures")
        fun differentSessionIds() {
            val key = hmacService.deriveSessionKey(testInviteToken)
            val timestamp = "2026-04-05T14:00:00Z"
            val sig1 = hmacService.sign(key, testSessionId, timestamp, testBody)
            val sig2 = hmacService.sign(key, "different-session-id", timestamp, testBody)
            assertNotEquals(sig1, sig2)
        }
    }

    @Nested
    @DisplayName("validate")
    inner class Validate {

        /**
         * A correctly signed request with a recent timestamp should pass validation.
         */
        @Test
        @DisplayName("accepts valid signature with recent timestamp")
        fun validSignature() {
            val key = hmacService.deriveSessionKey(testInviteToken)
            val timestamp = Instant.now().toString()
            val signature = hmacService.sign(key, testSessionId, timestamp, testBody)

            assertTrue(hmacService.validate(key, testSessionId, timestamp, testBody, signature))
        }

        /**
         * A tampered body should fail validation even if the signature was valid for
         * the original body.
         */
        @Test
        @DisplayName("rejects tampered body")
        fun tamperedBody() {
            val key = hmacService.deriveSessionKey(testInviteToken)
            val timestamp = Instant.now().toString()
            val signature = hmacService.sign(key, testSessionId, timestamp, testBody)

            assertFalse(hmacService.validate(key, testSessionId, timestamp, "tampered-body", signature))
        }

        /**
         * A wrong signature should fail even if all other parameters are correct.
         */
        @Test
        @DisplayName("rejects wrong signature")
        fun wrongSignature() {
            val key = hmacService.deriveSessionKey(testInviteToken)
            val timestamp = Instant.now().toString()

            assertFalse(hmacService.validate(key, testSessionId, timestamp, testBody, "0".repeat(64)))
        }

        /**
         * Timestamps older than 5 minutes should be rejected to limit replay window.
         */
        @Test
        @DisplayName("rejects timestamp more than 5 minutes in the past")
        fun expiredTimestamp() {
            val key = hmacService.deriveSessionKey(testInviteToken)
            val oldTimestamp = Instant.now().minusSeconds(301).toString() // 5min + 1sec
            val signature = hmacService.sign(key, testSessionId, oldTimestamp, testBody)

            assertFalse(hmacService.validate(key, testSessionId, oldTimestamp, testBody, signature))
        }

        /**
         * Timestamps more than 5 minutes in the future should also be rejected.
         */
        @Test
        @DisplayName("rejects timestamp more than 5 minutes in the future")
        fun futureTimestamp() {
            val key = hmacService.deriveSessionKey(testInviteToken)
            val futureTimestamp = Instant.now().plusSeconds(301).toString()
            val signature = hmacService.sign(key, testSessionId, futureTimestamp, testBody)

            assertFalse(hmacService.validate(key, testSessionId, futureTimestamp, testBody, signature))
        }

        /**
         * Timestamps within the 5-minute window should be accepted.
         */
        @Test
        @DisplayName("accepts timestamp within 5-minute window")
        fun withinWindow() {
            val key = hmacService.deriveSessionKey(testInviteToken)
            val recentTimestamp = Instant.now().minusSeconds(120).toString() // 2 minutes ago
            val signature = hmacService.sign(key, testSessionId, recentTimestamp, testBody)

            assertTrue(hmacService.validate(key, testSessionId, recentTimestamp, testBody, signature))
        }

        /**
         * Malformed timestamp strings should fail validation (not throw exceptions).
         */
        @Test
        @DisplayName("rejects malformed timestamp")
        fun malformedTimestamp() {
            val key = hmacService.deriveSessionKey(testInviteToken)
            val signature = hmacService.sign(key, testSessionId, "not-a-timestamp", testBody)

            assertFalse(hmacService.validate(key, testSessionId, "not-a-timestamp", testBody, signature))
        }

        /**
         * A signature generated with a different session key should fail validation.
         * This simulates an attacker using a key from a different session.
         */
        @Test
        @DisplayName("rejects signature from different session key")
        fun differentKey() {
            val key1 = hmacService.deriveSessionKey("token-1")
            val key2 = hmacService.deriveSessionKey("token-2")
            val timestamp = Instant.now().toString()
            val signature = hmacService.sign(key1, testSessionId, timestamp, testBody)

            assertFalse(hmacService.validate(key2, testSessionId, timestamp, testBody, signature))
        }
    }
}
