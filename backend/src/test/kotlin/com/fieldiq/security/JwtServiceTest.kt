package com.fieldiq.security

import com.fieldiq.config.FieldIQProperties
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [JwtService] — the central token operations service in FieldIQ's auth system.
 *
 * Verifies the four core responsibilities of [JwtService]:
 * 1. **Access token generation** — HS256 JWTs with user UUID in the `sub` claim.
 * 2. **Access token validation** — signature verification, expiration checks, and graceful
 *    handling of tampered, malformed, or cross-instance tokens.
 * 3. **Refresh token generation** — cryptographically random 32-byte Base64-URL strings.
 * 4. **Token hashing** — deterministic SHA-256 hex-encoded hashing for secure DB storage
 *    of OTP codes and refresh tokens.
 *
 * These tests use a real [JwtService] instance (no mocking) backed by deterministic
 * [FieldIQProperties] with a known test secret. This ensures the actual JJWT library
 * behavior is exercised, including signing, parsing, and expiration enforcement.
 *
 * **No database or Redis interaction** — [JwtService] is a pure computation service.
 *
 * @see JwtService for the service under test.
 * @see JwtAuthenticationFilter for the filter that consumes tokens validated by this service.
 * @see com.fieldiq.service.AuthService for the orchestrator that uses this service.
 */
class JwtServiceTest {

    /** The [JwtService] instance under test, initialized in [setUp] with deterministic config. */
    private lateinit var jwtService: JwtService

    /** Test HMAC secret — must be at least 256 bits (32 bytes) for HS256 security. */
    private val testSecret = "this-is-a-test-secret-that-is-at-least-32-bytes-long"

    /**
     * Creates a fresh [JwtService] with known configuration before each test.
     *
     * Uses a 15-minute access token expiration (900,000ms) matching production defaults.
     * The [FieldIQProperties] is constructed directly rather than mocked, so the real
     * config binding behavior is preserved.
     */
    @BeforeEach
    fun setUp() {
        val properties = FieldIQProperties(
            instance = FieldIQProperties.InstanceProperties(
                id = "test-instance",
                secret = "instance-secret",
                baseUrl = "http://localhost:8080",
            ),
            jwt = FieldIQProperties.JwtProperties(
                secret = testSecret,
                expirationMs = 900_000, // 15 minutes
            ),
            aws = FieldIQProperties.AwsProperties(),
        )
        jwtService = JwtService(properties)
    }

    /**
     * Tests for [JwtService.generateAccessToken].
     *
     * Verifies that generated tokens are well-formed JWTs (three dot-separated segments)
     * and that different user UUIDs produce distinct tokens (via unique `sub` claims
     * and `iat` timestamps).
     */
    @Nested
    @DisplayName("generateAccessToken")
    inner class GenerateAccessToken {

        /**
         * Validates the basic structure of a generated JWT.
         *
         * A valid JWT consists of three Base64-URL-encoded segments separated by dots:
         * header.payload.signature. This test ensures the JJWT builder produces
         * correctly formatted output.
         */
        @Test
        fun `should generate a non-empty JWT string`() {
            val userId = UUID.randomUUID()
            val token = jwtService.generateAccessToken(userId)
            assertNotNull(token)
            assertTrue(token.isNotBlank())
            // JWT has 3 parts separated by dots
            assertEquals(3, token.split(".").size)
        }

        /**
         * Ensures uniqueness across users.
         *
         * Even if tokens are generated in rapid succession, different user UUIDs
         * must produce different tokens because the `sub` claim differs.
         */
        @Test
        fun `should generate different tokens for different users`() {
            val userA = UUID.randomUUID()
            val userB = UUID.randomUUID()
            val tokenA = jwtService.generateAccessToken(userA)
            val tokenB = jwtService.generateAccessToken(userB)
            assertNotEquals(tokenA, tokenB)
        }
    }

    /**
     * Tests for [JwtService.validateAccessToken].
     *
     * Covers the full validation surface: valid round-trip, signature tampering,
     * malformed input, cross-secret rejection, and expiration enforcement.
     * All failure cases must return null (not throw) per the service contract.
     */
    @Nested
    @DisplayName("validateAccessToken")
    inner class ValidateAccessToken {

        /**
         * Happy path: a freshly generated token should validate and return the
         * original user UUID from the `sub` claim.
         */
        @Test
        fun `should return userId for a valid token`() {
            val userId = UUID.randomUUID()
            val token = jwtService.generateAccessToken(userId)
            val result = jwtService.validateAccessToken(token)
            assertEquals(userId, result)
        }

        /**
         * Signature integrity: modifying the last 5 characters of a JWT corrupts
         * the signature, and JJWT must reject it as tampered.
         */
        @Test
        fun `should return null for a tampered token`() {
            val userId = UUID.randomUUID()
            val token = jwtService.generateAccessToken(userId)
            val tampered = token.dropLast(5) + "XXXXX"
            assertNull(jwtService.validateAccessToken(tampered))
        }

        /**
         * Malformed input: a string that is not a JWT at all (no dots, no Base64)
         * must return null, not throw a parse exception.
         */
        @Test
        fun `should return null for a completely invalid string`() {
            assertNull(jwtService.validateAccessToken("not-a-jwt"))
        }

        /**
         * Empty input edge case: the filter may pass an empty string if the
         * Authorization header is malformed. Must return null gracefully.
         */
        @Test
        fun `should return null for an empty string`() {
            assertNull(jwtService.validateAccessToken(""))
        }

        /**
         * Cross-instance rejection: a token signed by Instance B's secret must not
         * validate on Instance A. This is critical for the two-instance dev setup
         * where each instance has its own JWT secret (unless shared for SSO).
         */
        @Test
        fun `should return null for a token signed with different secret`() {
            val otherProperties = FieldIQProperties(
                instance = FieldIQProperties.InstanceProperties(
                    id = "other", secret = "other-secret", baseUrl = "http://localhost:8081",
                ),
                jwt = FieldIQProperties.JwtProperties(
                    secret = "a-completely-different-secret-that-is-at-least-32-bytes",
                    expirationMs = 900_000,
                ),
                aws = FieldIQProperties.AwsProperties(),
            )
            val otherService = JwtService(otherProperties)
            val userId = UUID.randomUUID()
            val token = otherService.generateAccessToken(userId)
            assertNull(jwtService.validateAccessToken(token))
        }

        /**
         * Expiration enforcement: a token generated with 0ms expiration is expired
         * the instant it is created. Validation must detect the expired `exp` claim
         * and return null.
         *
         * This test creates a separate [JwtService] with `expirationMs = 0` to
         * produce an immediately-expired token, then validates it against the
         * standard service.
         */
        @Test
        fun `should return null for an expired token`() {
            val expiredProperties = FieldIQProperties(
                instance = FieldIQProperties.InstanceProperties(
                    id = "test", secret = "test-secret", baseUrl = "http://localhost:8080",
                ),
                jwt = FieldIQProperties.JwtProperties(
                    secret = testSecret,
                    expirationMs = 0, // expires immediately
                ),
                aws = FieldIQProperties.AwsProperties(),
            )
            val expiredService = JwtService(expiredProperties)
            val userId = UUID.randomUUID()
            val token = expiredService.generateAccessToken(userId)
            // Token should be expired by the time we validate
            assertNull(jwtService.validateAccessToken(token))
        }
    }

    /**
     * Tests for negotiation-scoped WebSocket token generation and validation.
     *
     * These tokens are intentionally narrower than access tokens: they expire quickly
     * and are valid for exactly one negotiation session.
     */
    @Nested
    @DisplayName("negotiation socket tokens")
    inner class NegotiationSocketTokens {

        /**
         * Happy path: a generated socket token validates for the same negotiation ID
         * and returns the original user UUID.
         */
        @Test
        fun `should validate a socket token for the matching negotiation`() {
            val userId = UUID.randomUUID()
            val negotiationId = UUID.randomUUID()

            val token = jwtService.generateNegotiationSocketToken(userId, negotiationId)

            assertEquals(userId, jwtService.validateNegotiationSocketToken(token, negotiationId))
        }

        /**
         * Session scoping: a socket token minted for one negotiation must be rejected
         * when presented against a different negotiation path.
         */
        @Test
        fun `should reject a socket token for a different negotiation`() {
            val userId = UUID.randomUUID()
            val token = jwtService.generateNegotiationSocketToken(userId, UUID.randomUUID())

            assertNull(jwtService.validateNegotiationSocketToken(token, UUID.randomUUID()))
        }

        /**
         * Token-type separation: a normal REST access token must not be accepted by
         * the WebSocket handshake validator.
         */
        @Test
        fun `should reject an access token in the socket validator`() {
            val userId = UUID.randomUUID()
            val negotiationId = UUID.randomUUID()
            val accessToken = jwtService.generateAccessToken(userId)

            assertNull(jwtService.validateNegotiationSocketToken(accessToken, negotiationId))
        }
    }

    /**
     * Tests for [JwtService.generateRefreshToken].
     *
     * Verifies that refresh tokens are cryptographically random, correctly sized
     * (32 bytes = 43 Base64-URL characters without padding), and unique across
     * multiple invocations.
     */
    @Nested
    @DisplayName("generateRefreshToken")
    inner class GenerateRefreshToken {

        /**
         * Format validation: 32 bytes of [java.security.SecureRandom] data encoded
         * as Base64-URL without padding produces exactly 43 characters.
         */
        @Test
        fun `should generate a non-empty base64 string`() {
            val token = jwtService.generateRefreshToken()
            assertNotNull(token)
            assertTrue(token.isNotBlank())
            // Base64 URL encoded 32 bytes = 43 characters (no padding)
            assertEquals(43, token.length)
        }

        /**
         * Uniqueness guarantee: 10 consecutive refresh tokens must all be distinct.
         * A collision here would indicate a broken [java.security.SecureRandom] or
         * a coding error in the generation logic.
         */
        @Test
        fun `should generate unique tokens each time`() {
            val tokens = (1..10).map { jwtService.generateRefreshToken() }.toSet()
            assertEquals(10, tokens.size, "All 10 generated tokens should be unique")
        }
    }

    /**
     * Tests for [JwtService.hashToken].
     *
     * Verifies that the SHA-256 hashing is deterministic, collision-resistant for
     * different inputs, and produces the expected 64-character hex-encoded output.
     * This hashing is used for both OTP codes and refresh tokens before DB storage.
     */
    @Nested
    @DisplayName("hashToken")
    inner class HashToken {

        /**
         * Determinism: hashing the same input twice must produce identical output.
         * This is essential for the lookup-by-hash pattern used in OTP verification
         * and refresh token rotation.
         */
        @Test
        fun `should produce a consistent hash for the same input`() {
            val input = "test-token-value"
            val hash1 = jwtService.hashToken(input)
            val hash2 = jwtService.hashToken(input)
            assertEquals(hash1, hash2)
        }

        /**
         * Collision resistance: two different inputs must produce different hashes.
         * While SHA-256 doesn't guarantee zero collisions, any collision in practice
         * would indicate a broken implementation.
         */
        @Test
        fun `should produce different hashes for different inputs`() {
            val hash1 = jwtService.hashToken("token-a")
            val hash2 = jwtService.hashToken("token-b")
            assertNotEquals(hash1, hash2)
        }

        /**
         * Format validation: SHA-256 produces a 32-byte digest, which hex-encoded
         * yields exactly 64 lowercase hexadecimal characters (0-9, a-f).
         */
        @Test
        fun `should produce a 64-character hex string (SHA-256)`() {
            val hash = jwtService.hashToken("any-input")
            assertEquals(64, hash.length)
            assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    /**
     * Tests for [JwtService.accessTokenExpirationSeconds].
     *
     * Verifies the milliseconds-to-seconds conversion used in
     * [com.fieldiq.api.dto.AuthResponse.expiresIn] to inform the client how
     * long the access token remains valid.
     */
    @Nested
    @DisplayName("accessTokenExpirationSeconds")
    inner class AccessTokenExpirationSeconds {

        /**
         * Conversion check: 900,000ms (15 minutes) should yield 900 seconds.
         */
        @Test
        fun `should return expiration in seconds`() {
            // 900,000 ms = 900 seconds
            assertEquals(900L, jwtService.accessTokenExpirationSeconds())
        }
    }
}
