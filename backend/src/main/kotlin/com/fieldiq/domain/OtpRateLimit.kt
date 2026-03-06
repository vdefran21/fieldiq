package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Persists OTP rate limiting data for audit and recovery.
 *
 * Rate limiting for OTP requests is enforced in real-time by Redis (low-latency
 * checks on every request). This table serves as a persistent audit trail — if
 * Redis is flushed or restarted, the backend can reconstruct rate limit state
 * from these records.
 *
 * **Rate limit thresholds:**
 * - 3 OTP requests per 15-minute window per identifier
 * - 10 OTP requests per 24-hour window per identifier
 * - After 5 failed OTP verification attempts: block identifier for 1 hour
 *
 * **Dev bypass:** Identifiers starting with "+1555" (test phone numbers) are
 * exempt from rate limiting to enable automated testing and dev workflows.
 *
 * **Identifier:** The phone number (E.164) or email used to request the OTP.
 * This is the rate limit key — all requests for the same identifier share
 * the same counters regardless of which user account it resolves to.
 *
 * @property id Unique identifier, auto-generated UUID.
 * @property identifier The phone number or email being rate-limited.
 * @property attempts Number of OTP requests within the current window.
 * @property windowStart Start of the rate limit window. Combined with [identifier],
 *   this forms the indexed lookup key.
 * @property blockedUntil If set, the identifier is blocked from OTP requests until
 *   this timestamp. Null if not currently blocked.
 * @property createdAt Timestamp of record creation. Immutable.
 * @see com.fieldiq.service.OtpRateLimitService for the Redis-backed enforcement logic.
 */
@Entity
@Table(name = "otp_rate_limits")
data class OtpRateLimit(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val identifier: String,

    @Column(nullable = false)
    val attempts: Int = 1,

    @Column(name = "window_start", nullable = false)
    val windowStart: Instant = Instant.now(),

    @Column(name = "blocked_until")
    val blockedUntil: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
