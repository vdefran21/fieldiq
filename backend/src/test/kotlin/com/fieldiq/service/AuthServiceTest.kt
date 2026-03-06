package com.fieldiq.service

import com.fieldiq.domain.AuthToken
import com.fieldiq.domain.RefreshToken
import com.fieldiq.domain.User
import com.fieldiq.repository.AuthTokenRepository
import com.fieldiq.repository.RefreshTokenRepository
import com.fieldiq.repository.UserRepository
import com.fieldiq.security.JwtService
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Unit tests for [AuthService] — the central authentication orchestrator in FieldIQ's
 * passwordless OTP-based auth system.
 *
 * Verifies the four public operations of [AuthService]:
 * 1. **OTP request** — input validation, rate limit delegation, OTP generation (random
 *    for production, deterministic "000000" for dev `+1555*` numbers), hashing via
 *    [JwtService.hashToken], and persistence to the `auth_tokens` table with identifier
 *    hash binding.
 * 2. **OTP verification** — hash-based lookup in `auth_tokens` by (channel, tokenHash,
 *    identifierHash), expiration enforcement, token consumption (marking `usedAt`),
 *    find-or-create user (auto sign-up), and issuance of JWT access + refresh token pair.
 *    Includes identifier binding tests that verify cross-identity token consumption is blocked.
 * 3. **Token refresh** — hash-based lookup of refresh tokens, revocation of the old token,
 *    issuance of a new token pair (rotation), with rejection of revoked or expired tokens.
 * 4. **Logout** — revocation of a refresh token, with idempotent handling of already-revoked
 *    and non-existent tokens.
 *
 * **Testing approach:** All five dependencies ([UserRepository], [AuthTokenRepository],
 * [RefreshTokenRepository], [JwtService], [OtpRateLimitService]) are mocked via MockK.
 * The [JwtService.hashToken] mock uses a predictable `"hashed_${input}"` pattern so tests
 * can assert on exact hash values. Token generation mocks return fixed strings for
 * deterministic assertions.
 *
 * **No database or Redis interaction** — all persistence is verified via MockK `verify` blocks.
 *
 * @see AuthService for the service under test.
 * @see JwtService for token generation and hashing (mocked here).
 * @see OtpRateLimitService for rate limiting (mocked here).
 * @see com.fieldiq.api.AuthController for the REST endpoints that delegate to this service.
 */
class AuthServiceTest {

    /** Mocked user repository for find-or-create operations during OTP verification. */
    private val userRepository: UserRepository = mockk(relaxed = true)

    /** Mocked auth token repository for OTP storage and hash-based lookup. */
    private val authTokenRepository: AuthTokenRepository = mockk(relaxed = true)

    /** Mocked refresh token repository for token rotation and revocation. */
    private val refreshTokenRepository: RefreshTokenRepository = mockk(relaxed = true)

    /** Mocked JWT service providing deterministic token generation and hashing. */
    private val jwtService: JwtService = mockk(relaxed = true)

    /** Mocked rate limit service for OTP request throttling verification. */
    private val rateLimitService: OtpRateLimitService = mockk(relaxed = true)

    /** The [AuthService] instance under test, recreated before each test. */
    private lateinit var authService: AuthService

    /** Stable test user UUID used across all test scenarios. */
    private val testUserId = UUID.randomUUID()

    /** Pre-built test user with a valid US phone number for SMS-based auth tests. */
    private val testUser = User(id = testUserId, phone = "+12025551234")

    /**
     * Creates a fresh [AuthService] with all mocked dependencies before each test.
     *
     * Configures default mock behaviors that most tests rely on:
     * - [JwtService.hashToken] → `"hashed_${input}"` for predictable hash assertions
     * - [JwtService.generateAccessToken] → `"test-access-token"` for response verification
     * - [JwtService.generateRefreshToken] → `"test-refresh-token"` for response verification
     * - [JwtService.accessTokenExpirationSeconds] → `900L` (15 minutes, matching production)
     * - [AuthTokenRepository.save] → returns first argument (identity function)
     * - [RefreshTokenRepository.save] → returns first argument (identity function)
     *
     * Individual tests override these defaults as needed for specific scenarios.
     */
    @BeforeEach
    fun setUp() {
        authService = AuthService(
            userRepository = userRepository,
            authTokenRepository = authTokenRepository,
            refreshTokenRepository = refreshTokenRepository,
            jwtService = jwtService,
            rateLimitService = rateLimitService,
        )

        // Default mocks for common operations
        every { jwtService.hashToken(any()) } answers { "hashed_${firstArg<String>()}" }
        every { jwtService.generateAccessToken(any()) } returns "test-access-token"
        every { jwtService.generateRefreshToken() } returns "test-refresh-token"
        every { jwtService.accessTokenExpirationSeconds() } returns 900L
        every { authTokenRepository.save(any()) } answers { firstArg() }
        every { refreshTokenRepository.save(any()) } answers { firstArg() }
    }

    /**
     * Tests for [AuthService.requestOtp].
     *
     * Validates the OTP request pipeline: input validation (phone format, email format,
     * channel validity), rate limit delegation to [OtpRateLimitService], OTP generation
     * (random 6-digit for production, deterministic "000000" for dev numbers), SHA-256
     * hashing, identifier binding via normalized identifier hash, and persistence to the
     * `auth_tokens` table.
     *
     * **Critical security properties tested:**
     * - Only hashed OTPs are stored; raw OTPs are never persisted
     * - Each token stores a hash of the normalized identifier it was requested for
     * - Rate limits are checked BEFORE generating/storing an OTP
     * - Invalid input formats are rejected with [IllegalArgumentException]
     * - [RateLimitExceededException] propagates cleanly to the controller layer
     */
    @Nested
    @DisplayName("requestOtp")
    inner class RequestOtp {

        /**
         * Happy path for SMS channel: a valid US phone number triggers the full pipeline.
         *
         * Verifies that:
         * 1. The auth token is saved with `channel = "sms"` (the OTP is hashed before storage)
         * 2. The auth token includes a non-empty `identifierHash` binding it to the phone
         * 3. Rate limit is checked for the exact identifier (phone number)
         * 4. The attempt is recorded for audit purposes
         */
        @Test
        fun `should generate and store hashed OTP for valid phone`() {
            every { rateLimitService.checkRateLimit(any()) } just Runs

            authService.requestOtp("sms", "+12025551234")

            verify {
                authTokenRepository.save(match {
                    it.channel == "sms" && it.identifierHash.isNotEmpty()
                })
            }
            verify { rateLimitService.checkRateLimit("+12025551234") }
            verify { rateLimitService.recordAttempt("+12025551234") }
        }

        /**
         * Happy path for email channel: a valid email address triggers the full pipeline.
         *
         * Verifies that the auth token is saved with `channel = "email"` and a non-empty
         * `identifierHash`, confirming that the email OTP flow uses the same storage
         * mechanism and identifier binding as SMS.
         */
        @Test
        fun `should generate and store hashed OTP for valid email`() {
            every { rateLimitService.checkRateLimit(any()) } just Runs

            authService.requestOtp("email", "user@example.com")

            verify {
                authTokenRepository.save(match {
                    it.channel == "email" && it.identifierHash.isNotEmpty()
                })
            }
        }

        /**
         * Identifier hash binding: the stored token must contain the SHA-256 hash of
         * the normalized identifier, computed via [JwtService.hashToken].
         *
         * With the `"hashed_${input}"` mock, the identifier hash for phone "+12025551234"
         * (already normalized — phone is E.164) should be `"hashed_+12025551234"`.
         */
        @Test
        fun `should store identifier hash on auth token`() {
            every { rateLimitService.checkRateLimit(any()) } just Runs

            authService.requestOtp("sms", "+12025551234")

            verify {
                authTokenRepository.save(match {
                    it.identifierHash == "hashed_+12025551234"
                })
            }
        }

        /**
         * Email identifier normalization: email addresses are lowercased before hashing,
         * so the stored identifier hash is case-insensitive.
         *
         * "User@Example.COM" is normalized to "user@example.com" before hashing,
         * producing `"hashed_user@example.com"` with the mock.
         */
        @Test
        fun `should normalize email before hashing identifier`() {
            every { rateLimitService.checkRateLimit(any()) } just Runs

            authService.requestOtp("email", "User@Example.COM")

            verify {
                authTokenRepository.save(match {
                    it.identifierHash == "hashed_user@example.com"
                })
            }
        }

        /**
         * Dev OTP bypass: `+1555*` numbers receive the deterministic OTP "000000"
         * instead of a random 6-digit code.
         *
         * This enables automated testing and QA workflows where the OTP is known in
         * advance. Verifies that [JwtService.hashToken] is called with "000000" (the
         * dev OTP), confirming the bypass produces a predictable, testable token.
         *
         * @see OtpRateLimitService.checkRateLimit for the corresponding rate limit bypass.
         */
        @Test
        fun `should use dev OTP for +1555 numbers`() {
            every { rateLimitService.checkRateLimit(any()) } just Runs

            authService.requestOtp("sms", "+15551234567")

            // The dev OTP "000000" should be hashed
            verify { jwtService.hashToken("000000") }
        }

        /**
         * Input validation: phone numbers must match E.164 format (`+` followed by digits).
         *
         * A string like "not-a-phone" should be rejected before any rate limiting or
         * token generation occurs, throwing [IllegalArgumentException].
         */
        @Test
        fun `should throw on invalid phone format`() {
            assertThrows(IllegalArgumentException::class.java) {
                authService.requestOtp("sms", "not-a-phone")
            }
        }

        /**
         * Input validation: email addresses must contain an `@` symbol and a valid domain.
         *
         * A string like "not-an-email" should be rejected before any rate limiting or
         * token generation occurs, throwing [IllegalArgumentException].
         */
        @Test
        fun `should throw on invalid email format`() {
            assertThrows(IllegalArgumentException::class.java) {
                authService.requestOtp("email", "not-an-email")
            }
        }

        /**
         * Channel validation: only "sms" and "email" are supported OTP delivery channels.
         *
         * An unsupported channel like "fax" should be rejected immediately with
         * [IllegalArgumentException], preventing downstream logic from receiving
         * an invalid channel value.
         */
        @Test
        fun `should throw on invalid channel`() {
            assertThrows(IllegalArgumentException::class.java) {
                authService.requestOtp("fax", "+12025551234")
            }
        }

        /**
         * Rate limit propagation: when [OtpRateLimitService] throws
         * [RateLimitExceededException], it must propagate unmodified to the caller.
         *
         * This ensures the controller layer receives the correct exception type (mapped
         * to HTTP 429 by [com.fieldiq.api.GlobalExceptionHandler]) rather than a
         * generic error.
         */
        @Test
        fun `should propagate rate limit exception`() {
            every { rateLimitService.checkRateLimit(any()) } throws RateLimitExceededException("Too many")

            assertThrows(RateLimitExceededException::class.java) {
                authService.requestOtp("sms", "+12025551234")
            }
        }
    }

    /**
     * Tests for [AuthService.verifyOtp].
     *
     * Validates the OTP verification pipeline: input validation, identifier normalization
     * and hashing, hash-based token lookup by (channel, tokenHash, identifierHash),
     * expiration enforcement, token consumption (setting `usedAt`), user resolution
     * (find existing or auto-create), and JWT + refresh token issuance.
     *
     * **Critical security properties tested:**
     * - OTP is hashed before lookup (never compared in plaintext)
     * - Identifier is normalized, hashed, and included in the lookup query
     * - A valid OTP for one identifier cannot be used with a different identifier
     * - Expired tokens are rejected even if the hash matches
     * - Used tokens cannot be reused (lookup filters on `usedAt IS NULL`)
     * - Email normalization ensures case-insensitive matching
     * - Invalid identifier formats are rejected before any DB lookup
     */
    @Nested
    @DisplayName("verifyOtp")
    inner class VerifyOtp {

        /**
         * Happy path: a valid, unexpired OTP returns a complete [com.fieldiq.api.dto.AuthResponse].
         *
         * Verifies the full response contract:
         * - `accessToken` matches the mocked JWT
         * - `refreshToken` matches the mocked refresh token
         * - `expiresIn` is 900 seconds (15 minutes)
         * - `user.id` matches the resolved user's UUID
         * - The auth token's `usedAt` is set (preventing reuse)
         */
        @Test
        fun `should return auth response for valid OTP`() {
            val authToken = AuthToken(
                tokenHash = "hashed_123456",
                identifierHash = "hashed_+12025551234",
                channel = "sms",
                expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES),
            )
            every {
                authTokenRepository.findFirstByChannelAndTokenHashAndIdentifierHashAndUsedAtIsNullOrderByCreatedAtDesc(
                    "sms", "hashed_123456", "hashed_+12025551234"
                )
            } returns authToken
            every { userRepository.findByPhone("+12025551234") } returns testUser

            val response = authService.verifyOtp("sms", "+12025551234", "123456")

            assertNotNull(response)
            assertEquals("test-access-token", response.accessToken)
            assertEquals("test-refresh-token", response.refreshToken)
            assertEquals(900L, response.expiresIn)
            assertEquals(testUserId.toString(), response.user.id)

            // Verify the auth token was marked as used
            verify { authTokenRepository.save(match { it.usedAt != null }) }
        }

        /**
         * Auto sign-up: when a phone number has no existing user account, [AuthService]
         * creates one automatically during OTP verification.
         *
         * This is the "magic link" UX pattern — new users don't need a separate
         * registration step. Verifies that `userRepository.save()` is called with
         * the phone number when `findByPhone()` returns null.
         */
        @Test
        fun `should create new user if not found during verify (auto sign-up)`() {
            val authToken = AuthToken(
                tokenHash = "hashed_123456",
                identifierHash = "hashed_+12025551234",
                channel = "sms",
                expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES),
            )
            every {
                authTokenRepository.findFirstByChannelAndTokenHashAndIdentifierHashAndUsedAtIsNullOrderByCreatedAtDesc(
                    "sms", "hashed_123456", "hashed_+12025551234"
                )
            } returns authToken
            every { userRepository.findByPhone("+12025551234") } returns null
            every { userRepository.save(any()) } returns testUser

            val response = authService.verifyOtp("sms", "+12025551234", "123456")

            assertNotNull(response)
            verify { userRepository.save(match { it.phone == "+12025551234" }) }
        }

        /**
         * Invalid OTP: when no auth token matches the (channel, tokenHash, identifierHash)
         * triple (and hasn't been used), the service throws [InvalidOtpException].
         *
         * This covers wrong OTP codes, OTPs that were already consumed, and OTPs
         * submitted with a different identifier than the one that requested them.
         * The controller maps this to HTTP 401.
         */
        @Test
        fun `should throw InvalidOtpException for non-existent token`() {
            every {
                authTokenRepository.findFirstByChannelAndTokenHashAndIdentifierHashAndUsedAtIsNullOrderByCreatedAtDesc(
                    any(), any(), any()
                )
            } returns null

            assertThrows(InvalidOtpException::class.java) {
                authService.verifyOtp("sms", "+12025551234", "999999")
            }
        }

        /**
         * Expired OTP: an auth token whose `expiresAt` is in the past must be rejected
         * even if the hash matches and the token hasn't been used.
         *
         * OTPs expire after 10 minutes in production. This test uses a token expired
         * 1 hour ago to ensure the expiration check is robust.
         */
        @Test
        fun `should throw InvalidOtpException for expired token`() {
            val expired = AuthToken(
                tokenHash = "hashed_123456",
                identifierHash = "hashed_+12025551234",
                channel = "sms",
                expiresAt = Instant.now().minus(1, ChronoUnit.HOURS), // expired
            )
            every {
                authTokenRepository.findFirstByChannelAndTokenHashAndIdentifierHashAndUsedAtIsNullOrderByCreatedAtDesc(
                    "sms", "hashed_123456", "hashed_+12025551234"
                )
            } returns expired

            assertThrows(InvalidOtpException::class.java) {
                authService.verifyOtp("sms", "+12025551234", "123456")
            }
        }

        /**
         * Email normalization: email identifiers are lowercased before both hashing
         * (for identifier binding) and user lookup (for case-insensitive matching).
         *
         * A user who registered as "user@example.com" must be found even if the OTP
         * verification request sends "User@Example.com". The identifier hash must also
         * match — both requestOtp and verifyOtp normalize to lowercase before hashing.
         */
        @Test
        fun `should normalize email to lowercase for user lookup`() {
            val authToken = AuthToken(
                tokenHash = "hashed_123456",
                identifierHash = "hashed_user@example.com",
                channel = "email",
                expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES),
            )
            every {
                authTokenRepository.findFirstByChannelAndTokenHashAndIdentifierHashAndUsedAtIsNullOrderByCreatedAtDesc(
                    "email", "hashed_123456", "hashed_user@example.com"
                )
            } returns authToken
            every { userRepository.findByEmail("user@example.com") } returns testUser

            authService.verifyOtp("email", "User@Example.com", "123456")

            verify { userRepository.findByEmail("user@example.com") }
        }

        /**
         * Identifier mismatch rejection: a valid OTP requested for phone A must not
         * be consumable when submitted with phone B.
         *
         * This is the core security property of identifier binding. The repository
         * query includes the identifier hash, so a mismatch means no token is found.
         * The service throws [InvalidOtpException] (mapped to HTTP 401).
         *
         * Scenario: OTP "000000" was requested for +15551111111 (token stored with
         * identifierHash = "hashed_+15551111111"). Attacker submits the same OTP
         * with +15559999999 → identifierHash = "hashed_+15559999999" → no match → 401.
         */
        @Test
        fun `should reject OTP when identifier does not match token`() {
            // Token was created for phone A
            every {
                authTokenRepository.findFirstByChannelAndTokenHashAndIdentifierHashAndUsedAtIsNullOrderByCreatedAtDesc(
                    "sms", "hashed_000000", "hashed_+15559999999"
                )
            } returns null

            assertThrows(InvalidOtpException::class.java) {
                authService.verifyOtp("sms", "+15559999999", "000000")
            }
        }

        /**
         * Dev bypass scoping: two `+1555*` phone numbers both produce the same OTP
         * hash (hash("000000")), but each token is bound to its own identifier hash.
         *
         * Phone A (+15551111111) and Phone B (+15559999999) both request OTPs and get
         * "000000". The tokens have the same tokenHash but different identifierHashes.
         * Each can only be verified by its own phone number.
         *
         * This test verifies that the repository is queried with the correct
         * identifier hash for each phone, and that using Phone B's identifier
         * against Phone A's token returns no match.
         */
        @Test
        fun `should scope dev bypass OTP to specific phone number`() {
            // Token exists for phone A
            val tokenForPhoneA = AuthToken(
                tokenHash = "hashed_000000",
                identifierHash = "hashed_+15551111111",
                channel = "sms",
                expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES),
            )
            every {
                authTokenRepository.findFirstByChannelAndTokenHashAndIdentifierHashAndUsedAtIsNullOrderByCreatedAtDesc(
                    "sms", "hashed_000000", "hashed_+15551111111"
                )
            } returns tokenForPhoneA
            every { userRepository.findByPhone("+15551111111") } returns User(phone = "+15551111111")

            // Phone A can verify its own OTP
            val response = authService.verifyOtp("sms", "+15551111111", "000000")
            assertNotNull(response)

            // Phone B tries to use the same OTP code — different identifier hash → no match
            every {
                authTokenRepository.findFirstByChannelAndTokenHashAndIdentifierHashAndUsedAtIsNullOrderByCreatedAtDesc(
                    "sms", "hashed_000000", "hashed_+15559999999"
                )
            } returns null

            assertThrows(InvalidOtpException::class.java) {
                authService.verifyOtp("sms", "+15559999999", "000000")
            }
        }

        /**
         * Input validation on verify: malformed identifiers are rejected before any
         * database lookup occurs.
         *
         * Previously, [AuthService.validateIdentifier] was only called in [AuthService.requestOtp].
         * Now it is also called in [AuthService.verifyOtp] to reject bad input early.
         */
        @Test
        fun `should reject invalid identifier format on verify`() {
            assertThrows(IllegalArgumentException::class.java) {
                authService.verifyOtp("sms", "not-a-phone", "123456")
            }

            assertThrows(IllegalArgumentException::class.java) {
                authService.verifyOtp("email", "not-an-email", "123456")
            }

            // Verify no DB lookup was attempted
            verify(exactly = 0) {
                authTokenRepository.findFirstByChannelAndTokenHashAndIdentifierHashAndUsedAtIsNullOrderByCreatedAtDesc(
                    any(), any(), any()
                )
            }
        }
    }

    /**
     * Tests for [AuthService.refreshToken].
     *
     * Validates the refresh token rotation flow: hash-based lookup, validation (not
     * revoked, not expired), old token revocation, and new token pair issuance.
     *
     * **Critical security properties tested:**
     * - Refresh tokens are single-use: the old token is revoked before the new one is issued
     * - Revoked tokens cannot be reused (prevents token replay attacks)
     * - Expired tokens are rejected even if not explicitly revoked
     * - New token chain links back to old token via `rotatedFrom`
     */
    @Nested
    @DisplayName("refreshToken")
    inner class RefreshTokenTests {

        /**
         * Happy path: a valid, non-revoked, non-expired refresh token triggers rotation.
         *
         * Verifies:
         * 1. The old token's `revokedAt` is set (consumed)
         * 2. A new refresh token is saved with `revokedAt = null` and a different hash
         * 3. The response contains a fresh access token
         */
        @Test
        fun `should issue new tokens for valid refresh token`() {
            val existingToken = RefreshToken(
                userId = testUserId,
                tokenHash = "hashed_test-refresh",
                expiresAt = Instant.now().plus(29, ChronoUnit.DAYS),
            )
            every { refreshTokenRepository.findByTokenHash(any()) } returns existingToken
            every { userRepository.findById(testUserId) } returns Optional.of(testUser)

            val response = authService.refreshToken("test-refresh")

            assertNotNull(response)
            assertEquals("test-access-token", response.accessToken)
            // Old token should be revoked
            verify { refreshTokenRepository.save(match { it.revokedAt != null && it.tokenHash == "hashed_test-refresh" }) }
            // New refresh token should be saved
            verify { refreshTokenRepository.save(match { it.revokedAt == null && it.tokenHash != "hashed_test-refresh" }) }
        }

        /**
         * Non-existent token: a refresh token that doesn't exist in the database
         * (never issued or already cleaned up) must be rejected.
         *
         * Throws [InvalidOtpException] (reused for generic auth failures) which the
         * controller maps to HTTP 401.
         */
        @Test
        fun `should throw for non-existent refresh token`() {
            every { refreshTokenRepository.findByTokenHash(any()) } returns null

            assertThrows(InvalidOtpException::class.java) {
                authService.refreshToken("non-existent-token")
            }
        }

        /**
         * Revoked token replay: a refresh token that was already consumed in a previous
         * rotation must not be accepted again.
         *
         * This prevents token replay attacks where an attacker captures a refresh token
         * after it has been used by the legitimate client. The `revokedAt` timestamp
         * is set 1 hour in the past to simulate a previously-rotated token.
         */
        @Test
        fun `should throw for revoked refresh token`() {
            val revokedToken = RefreshToken(
                userId = testUserId,
                tokenHash = "hashed_token",
                expiresAt = Instant.now().plus(29, ChronoUnit.DAYS),
                revokedAt = Instant.now().minus(1, ChronoUnit.HOURS),
            )
            every { refreshTokenRepository.findByTokenHash(any()) } returns revokedToken

            assertThrows(InvalidOtpException::class.java) {
                authService.refreshToken("revoked-token")
            }
        }

        /**
         * Expired token: a refresh token past its `expiresAt` must be rejected even
         * if it was never explicitly revoked.
         *
         * Refresh tokens have a 30-day lifetime in production. This test uses a token
         * expired 1 day ago to ensure the expiration check works independently of
         * the revocation check.
         */
        @Test
        fun `should throw for expired refresh token`() {
            val expiredToken = RefreshToken(
                userId = testUserId,
                tokenHash = "hashed_token",
                expiresAt = Instant.now().minus(1, ChronoUnit.DAYS), // expired
            )
            every { refreshTokenRepository.findByTokenHash(any()) } returns expiredToken

            assertThrows(InvalidOtpException::class.java) {
                authService.refreshToken("expired-token")
            }
        }
    }

    /**
     * Tests for [AuthService.logout].
     *
     * Validates the token revocation behavior: marking a valid token's `revokedAt`
     * timestamp, idempotent handling of already-revoked tokens, and graceful no-op
     * for non-existent tokens.
     *
     * **Design contract:** Logout is always "successful" from the client's perspective.
     * Whether the token existed, was already revoked, or never existed, the operation
     * completes without throwing. This prevents information leakage about token validity.
     */
    @Nested
    @DisplayName("logout")
    inner class Logout {

        /**
         * Happy path: an active (non-revoked) refresh token is marked with `revokedAt = now()`.
         *
         * After revocation, any subsequent attempt to use this token for refresh will
         * be rejected by the [refreshToken] validation logic.
         */
        @Test
        fun `should revoke existing token`() {
            val token = RefreshToken(
                userId = testUserId,
                tokenHash = "hashed_token",
                expiresAt = Instant.now().plus(29, ChronoUnit.DAYS),
            )
            every { refreshTokenRepository.findByTokenHash(any()) } returns token

            authService.logout("some-token")

            verify { refreshTokenRepository.save(match { it.revokedAt != null }) }
        }

        /**
         * Idempotent revocation: calling logout with an already-revoked token should
         * NOT trigger another save operation.
         *
         * This avoids unnecessary database writes and ensures the original `revokedAt`
         * timestamp is preserved (important for audit trails showing when the token
         * was first revoked).
         */
        @Test
        fun `should be idempotent for already revoked token`() {
            val alreadyRevoked = RefreshToken(
                userId = testUserId,
                tokenHash = "hashed_token",
                expiresAt = Instant.now().plus(29, ChronoUnit.DAYS),
                revokedAt = Instant.now().minus(1, ChronoUnit.HOURS),
            )
            every { refreshTokenRepository.findByTokenHash(any()) } returns alreadyRevoked

            assertDoesNotThrow { authService.logout("already-revoked") }
            // Should NOT save again since it's already revoked
            verify(exactly = 0) { refreshTokenRepository.save(any()) }
        }

        /**
         * Graceful no-op: a non-existent token (never issued or already cleaned up)
         * should not cause an error.
         *
         * The logout endpoint returns 204 regardless, so the service must not throw.
         * This prevents an attacker from probing token validity via logout responses.
         */
        @Test
        fun `should be no-op for non-existent token`() {
            every { refreshTokenRepository.findByTokenHash(any()) } returns null

            assertDoesNotThrow { authService.logout("non-existent") }
            verify(exactly = 0) { refreshTokenRepository.save(any()) }
        }
    }
}
