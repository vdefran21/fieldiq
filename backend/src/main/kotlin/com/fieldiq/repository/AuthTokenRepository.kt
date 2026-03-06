package com.fieldiq.repository

import com.fieldiq.domain.AuthToken
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * Spring Data JPA repository for [AuthToken] entities.
 *
 * Provides queries for the OTP authentication flow. During OTP verification,
 * the submitted code is hashed and looked up by channel + hash. Expired tokens
 * are periodically cleaned up to prevent table bloat.
 *
 * @see AuthToken for the entity managed by this repository.
 * @see com.fieldiq.service.AuthService for the authentication logic that uses these queries.
 */
interface AuthTokenRepository : JpaRepository<AuthToken, UUID> {

    /**
     * Finds a valid (unused) auth token by channel and token hash.
     *
     * Used during OTP verification: the submitted OTP is hashed with SHA-256
     * and looked up by channel (sms/email) + hash. Only tokens that have not
     * been used ([AuthToken.usedAt] is null) are returned.
     *
     * @param channel The authentication channel ("sms" or "email").
     * @param tokenHash The SHA-256 hash of the submitted OTP code.
     * @return The matching [AuthToken], or null if no unused token matches.
     */
    fun findByChannelAndTokenHashAndUsedAtIsNull(channel: String, tokenHash: String): AuthToken?

    /**
     * Deletes all expired tokens created before the given timestamp.
     *
     * Used by cleanup jobs to prevent table bloat. Tokens older than their
     * expiry are no longer useful for verification and can be safely removed.
     *
     * @param before The cutoff timestamp — tokens expiring before this are deleted.
     */
    fun deleteByExpiresAtBefore(before: Instant)
}
