package com.fieldiq.repository

import com.fieldiq.domain.OtpRateLimit
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * Spring Data JPA repository for [OtpRateLimit] entities.
 *
 * Provides queries for OTP rate limit audit data. The primary rate limiting
 * enforcement uses Redis for low-latency checks; this repository handles the
 * persistent audit trail and fallback reconstruction if Redis is flushed.
 *
 * @see OtpRateLimit for the entity managed by this repository.
 * @see com.fieldiq.service.OtpRateLimitService for the Redis-backed enforcement logic.
 */
interface OtpRateLimitRepository : JpaRepository<OtpRateLimit, UUID> {

    /**
     * Finds rate limit records for an identifier within a time window.
     *
     * Used to reconstruct rate limit state from the database if Redis state
     * is lost. Also used for audit queries — e.g., "how many OTP requests
     * has this phone number made in the last 24 hours?"
     *
     * @param identifier The phone number (E.164) or email being rate-limited.
     * @param after The start of the time window to query.
     * @return Rate limit records for the identifier after the given timestamp.
     */
    fun findByIdentifierAndWindowStartAfter(identifier: String, after: Instant): List<OtpRateLimit>
}
