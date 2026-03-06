package com.fieldiq.service

import com.fieldiq.domain.OtpRateLimit
import com.fieldiq.repository.OtpRateLimitRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * Enforces OTP request rate limiting to prevent SMS pumping and brute-force attacks.
 *
 * Rate limiting is enforced in two layers:
 * 1. **Redis** (real-time): Low-latency checks on every OTP request. Redis counters
 *    are the primary enforcement mechanism.
 * 2. **Database** (audit): Persistent records in the [OtpRateLimit] table for
 *    compliance auditing and recovery if Redis is flushed.
 *
 * Rate limit thresholds:
 * - 3 OTP requests per 15-minute window per identifier
 * - 10 OTP requests per 24-hour window per identifier
 *
 * Dev bypass: Identifiers starting with "+1555" (test phone numbers) are exempt
 * from all rate limiting to enable automated testing and dev workflows.
 *
 * @property redisTemplate Redis client for counter operations.
 * @property otpRateLimitRepository Repository for persistent audit records.
 * @see com.fieldiq.service.AuthService for the OTP flow that calls this service.
 */
@Service
class OtpRateLimitService(
    private val redisTemplate: StringRedisTemplate,
    private val otpRateLimitRepository: OtpRateLimitRepository,
) {
    private val logger = LoggerFactory.getLogger(OtpRateLimitService::class.java)

    companion object {
        private const val SHORT_WINDOW_MAX = 3
        private val SHORT_WINDOW_DURATION = Duration.ofMinutes(15)
        private const val LONG_WINDOW_MAX = 10
        private val LONG_WINDOW_DURATION = Duration.ofHours(24)
        private const val REDIS_KEY_PREFIX = "otp:rate:"
    }

    /**
     * Checks whether the given identifier has exceeded OTP rate limits.
     *
     * Queries Redis counters for both the 15-minute and 24-hour windows.
     * If either limit is exceeded, throws [RateLimitExceededException].
     *
     * Dev bypass: identifiers starting with "+1555" always pass.
     *
     * @param identifier The phone number (E.164) or email requesting an OTP.
     * @throws RateLimitExceededException If the identifier has exceeded rate limits.
     */
    fun checkRateLimit(identifier: String) {
        if (isDevBypass(identifier)) {
            logger.debug("Dev bypass: skipping rate limit for {}", identifier)
            return
        }

        val shortKey = "${REDIS_KEY_PREFIX}15m:$identifier"
        val longKey = "${REDIS_KEY_PREFIX}24h:$identifier"

        val shortCount = redisTemplate.opsForValue().get(shortKey)?.toLongOrNull() ?: 0
        val longCount = redisTemplate.opsForValue().get(longKey)?.toLongOrNull() ?: 0

        if (shortCount >= SHORT_WINDOW_MAX) {
            logger.warn("Rate limit exceeded (15min) for identifier: {}", identifier)
            throw RateLimitExceededException(
                "Too many OTP requests. Please wait before trying again."
            )
        }

        if (longCount >= LONG_WINDOW_MAX) {
            logger.warn("Rate limit exceeded (24h) for identifier: {}", identifier)
            throw RateLimitExceededException(
                "Daily OTP limit reached. Please try again tomorrow."
            )
        }
    }

    /**
     * Records an OTP request attempt for rate limiting.
     *
     * Increments both the 15-minute and 24-hour Redis counters, setting TTLs
     * on first creation. Also persists a record to the database for audit.
     *
     * @param identifier The phone number (E.164) or email that requested an OTP.
     */
    fun recordAttempt(identifier: String) {
        if (isDevBypass(identifier)) return

        val shortKey = "${REDIS_KEY_PREFIX}15m:$identifier"
        val longKey = "${REDIS_KEY_PREFIX}24h:$identifier"

        val shortOps = redisTemplate.opsForValue()
        val shortVal = shortOps.increment(shortKey)
        if (shortVal == 1L) {
            redisTemplate.expire(shortKey, SHORT_WINDOW_DURATION)
        }

        val longVal = shortOps.increment(longKey)
        if (longVal == 1L) {
            redisTemplate.expire(longKey, LONG_WINDOW_DURATION)
        }

        // Persist to DB for audit
        otpRateLimitRepository.save(
            OtpRateLimit(
                identifier = identifier,
                attempts = shortVal?.toInt() ?: 1,
                windowStart = Instant.now(),
            )
        )
    }

    /**
     * Checks if the identifier is a dev test number exempt from rate limiting.
     *
     * Phone numbers starting with "+1555" are reserved for testing per CLAUDE.md.
     *
     * @param identifier The phone or email to check.
     * @return True if the identifier is exempt from rate limiting.
     */
    private fun isDevBypass(identifier: String): Boolean =
        identifier.startsWith("+1555")
}

/**
 * Thrown when an OTP request exceeds the configured rate limits.
 *
 * Caught by [com.fieldiq.api.GlobalExceptionHandler] and returned as 429 Too Many Requests.
 *
 * @property message Human-readable description of the rate limit violation.
 */
class RateLimitExceededException(message: String) : RuntimeException(message)
