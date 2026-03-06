package com.fieldiq.service

import com.fieldiq.repository.OtpRateLimitRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

/**
 * Unit tests for [OtpRateLimitService] — the dual-window rate limiter protecting the OTP
 * request flow from brute-force and abuse.
 *
 * Verifies the three core responsibilities of [OtpRateLimitService]:
 * 1. **Rate limit checking** — enforces both the 15-minute (3 attempts) and 24-hour
 *    (10 attempts) sliding window limits, throwing [RateLimitExceededException] when
 *    either threshold is breached.
 * 2. **Attempt recording** — increments Redis counters for both windows, sets TTL on
 *    first increment to auto-expire keys, and persists an audit trail to the database.
 * 3. **Dev bypass** — `+1555*` phone numbers (FieldIQ's dev/test numbers) are exempt
 *    from all rate limiting, allowing unlimited OTP requests in development and testing.
 *
 * **Testing approach:** Both [StringRedisTemplate] and [OtpRateLimitRepository] are mocked
 * via MockK. Redis [ValueOperations] are stubbed to simulate counter states (under limit,
 * at limit, null keys). This isolates the service's decision logic from actual Redis I/O.
 *
 * **Important mock setup:** The [OtpRateLimitRepository.save] method is explicitly stubbed
 * with `answers { firstArg() }` to avoid a `ClassCastException` caused by MockK's relaxed
 * mode returning `Object` instead of `OtpRateLimit` for JPA's generic `save(S): S` method.
 *
 * @see OtpRateLimitService for the service under test.
 * @see RateLimitExceededException for the exception thrown when limits are exceeded.
 * @see AuthService.requestOtp for the caller that delegates to this service.
 * @see com.fieldiq.domain.OtpRateLimit for the audit entity persisted to the database.
 */
class OtpRateLimitServiceTest {

    /** Mocked Redis template providing the [ValueOperations] used for counter reads/writes. */
    private val redisTemplate: StringRedisTemplate = mockk(relaxed = true)

    /** Mocked repository for persisting [com.fieldiq.domain.OtpRateLimit] audit records. */
    private val otpRateLimitRepository: OtpRateLimitRepository = mockk(relaxed = true)

    /**
     * Mocked [ValueOperations] returned by [redisTemplate.opsForValue].
     *
     * Stubbed separately to allow fine-grained control over `get()` and `increment()`
     * return values for each test scenario.
     */
    private val valueOps: ValueOperations<String, String> = mockk(relaxed = true)

    /** The [OtpRateLimitService] instance under test, recreated before each test. */
    private lateinit var service: OtpRateLimitService

    /**
     * Initializes the service with mocked dependencies before each test.
     *
     * Wires [valueOps] as the return value of `redisTemplate.opsForValue()` and explicitly
     * stubs [OtpRateLimitRepository.save] to return its first argument (avoiding the
     * `ClassCastException` from MockK's relaxed mode with JPA's generic `save(S): S`).
     */
    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForValue() } returns valueOps
        every { otpRateLimitRepository.save(any()) } answers { firstArg() }
        service = OtpRateLimitService(redisTemplate, otpRateLimitRepository)
    }

    /**
     * Tests for [OtpRateLimitService.checkRateLimit].
     *
     * Validates the dual-window enforcement logic: a 15-minute window (max 3 attempts)
     * and a 24-hour window (max 10 attempts). Also verifies the dev bypass for `+1555*`
     * phone numbers, which allows unlimited OTP requests during development.
     *
     * Each test configures Redis counters via [valueOps.get] stubs and then asserts
     * whether [checkRateLimit] passes silently or throws [RateLimitExceededException].
     */
    @Nested
    @DisplayName("checkRateLimit")
    inner class CheckRateLimit {

        /**
         * Happy path: both counters are well below their respective thresholds.
         *
         * When the 15-minute counter is "0" and the 24-hour counter is "0", the
         * user should be allowed to request another OTP without any exception.
         */
        @Test
        fun `should pass when under both limits`() {
            every { valueOps.get(any()) } returns "0"
            assertDoesNotThrow { service.checkRateLimit("+12025551234") }
        }

        /**
         * First-request scenario: no Redis keys exist yet for this identifier.
         *
         * A null return from `valueOps.get()` means the user has never requested an
         * OTP (or the TTL expired). This is equivalent to zero attempts and should pass.
         */
        @Test
        fun `should pass when no Redis keys exist`() {
            every { valueOps.get(any()) } returns null
            assertDoesNotThrow { service.checkRateLimit("+12025551234") }
        }

        /**
         * 15-minute window breach: the short-burst limit prevents rapid-fire OTP
         * requests that could be used for brute-forcing or SMS bombing.
         *
         * When the 15-minute counter reaches 3 (the threshold), the service must throw
         * [RateLimitExceededException] with a message containing "wait" to inform the
         * caller to retry after the window expires.
         */
        @Test
        fun `should throw when 15-min limit exceeded`() {
            every { valueOps.get(any()) } answers {
                val key = firstArg<String>()
                if (key.contains("15m")) "3" else "3"
            }

            val ex = assertThrows(RateLimitExceededException::class.java) {
                service.checkRateLimit("+12025551234")
            }
            assertTrue(ex.message!!.contains("wait"))
        }

        /**
         * 24-hour window breach: the daily limit prevents sustained abuse even if
         * individual bursts stay within the 15-minute window.
         *
         * When the 15-minute counter is "0" (under short limit) but the 24-hour
         * counter is "10" (at daily threshold), the service must throw
         * [RateLimitExceededException] with a message containing "Daily".
         */
        @Test
        fun `should throw when 24-hour limit exceeded`() {
            every { valueOps.get(any()) } answers {
                val key = firstArg<String>()
                if (key.contains("15m")) "0" else "10"
            }

            val ex = assertThrows(RateLimitExceededException::class.java) {
                service.checkRateLimit("+12025551234")
            }
            assertTrue(ex.message!!.contains("Daily"))
        }

        /**
         * Dev bypass: `+1555*` phone numbers are FieldIQ's designated test numbers
         * (matching the NANPA 555 fictitious prefix) and are exempt from all rate limits.
         *
         * Even with counters at an absurdly high value ("999"), dev numbers must pass
         * through without throwing. This enables automated testing and QA workflows
         * without triggering rate limit blocks.
         *
         * @see AuthService.requestOtp for the corresponding dev OTP bypass ("000000").
         */
        @Test
        fun `should bypass rate limit for dev phone numbers`() {
            // Even with limits exceeded, dev numbers should pass
            every { valueOps.get(any()) } returns "999"
            assertDoesNotThrow { service.checkRateLimit("+15551234567") }
        }

        /**
         * Negative test for dev bypass scope: ensures that non-dev US phone numbers
         * (e.g., `+1202...`) are NOT exempted just because they start with `+1`.
         *
         * Only the `+1555` prefix triggers the bypass. All other `+1` numbers must
         * be subject to normal rate limiting.
         */
        @Test
        fun `should not bypass for non-dev phone numbers starting with +1`() {
            every { valueOps.get(any()) } returns "3"

            assertThrows(RateLimitExceededException::class.java) {
                service.checkRateLimit("+12025551234")
            }
        }
    }

    /**
     * Tests for [OtpRateLimitService.recordAttempt].
     *
     * Validates the three-part recording logic:
     * 1. **Redis increment** — both the 15-minute and 24-hour counters are atomically
     *    incremented via `INCR`.
     * 2. **TTL management** — `EXPIRE` is set on the first increment (count = 1) to
     *    start the sliding window, but NOT on subsequent increments (to avoid resetting
     *    the window clock).
     * 3. **DB audit** — an [com.fieldiq.domain.OtpRateLimit] record is persisted for
     *    long-term audit and abuse investigation.
     *
     * Also verifies that dev bypass numbers skip all recording entirely.
     */
    @Nested
    @DisplayName("recordAttempt")
    inner class RecordAttempt {

        /**
         * Verifies that both Redis counters (15-minute and 24-hour) are incremented.
         *
         * The `INCR` command is called at least twice — once for each window. The exact
         * number may vary if there's additional logic, so `atLeast = 2` is used.
         */
        @Test
        fun `should increment both Redis counters`() {
            every { valueOps.increment(any()) } returns 1L

            service.recordAttempt("+12025551234")

            verify(atLeast = 2) { valueOps.increment(any()) }
        }

        /**
         * First-increment TTL: when `INCR` returns 1 (new key), `EXPIRE` must be set
         * to start the sliding window countdown.
         *
         * Exactly 2 `expire()` calls are expected — one for the 15-minute key and one
         * for the 24-hour key. This ensures both windows start ticking from the first
         * request, not from some arbitrary earlier time.
         */
        @Test
        fun `should set TTL on first increment`() {
            every { valueOps.increment(any()) } returns 1L

            service.recordAttempt("+12025551234")

            verify(exactly = 2) { redisTemplate.expire(any(), any<java.time.Duration>()) }
        }

        /**
         * Subsequent-increment TTL preservation: when `INCR` returns > 1 (existing key),
         * `EXPIRE` must NOT be called, because resetting the TTL would extend the window
         * and make the rate limit less effective.
         *
         * A return value of 2 simulates the second request in a window.
         */
        @Test
        fun `should not set TTL on subsequent increments`() {
            every { valueOps.increment(any()) } returns 2L

            service.recordAttempt("+12025551234")

            verify(exactly = 0) { redisTemplate.expire(any(), any<java.time.Duration>()) }
        }

        /**
         * Database audit: every real (non-dev) OTP request must be persisted to the
         * `otp_rate_limits` table for long-term abuse tracking and investigation.
         *
         * This record includes the phone number, attempt timestamp, and window state,
         * enabling ops teams to identify patterns that Redis counters (with TTL expiry)
         * would lose.
         */
        @Test
        fun `should persist audit record to database`() {
            every { valueOps.increment(any()) } returns 1L

            service.recordAttempt("+12025551234")

            verify { otpRateLimitRepository.save(any()) }
        }

        /**
         * Dev bypass in recording: `+1555*` numbers skip ALL recording — no Redis
         * increments, no database persistence.
         *
         * This prevents dev/test traffic from polluting rate limit counters and audit
         * tables, keeping the data clean for real abuse detection.
         */
        @Test
        fun `should skip recording for dev bypass numbers`() {
            service.recordAttempt("+15551234567")

            verify(exactly = 0) { valueOps.increment(any()) }
            verify(exactly = 0) { otpRateLimitRepository.save(any()) }
        }
    }
}
