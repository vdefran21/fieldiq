package com.fieldiq.repository

import com.fieldiq.domain.AuthToken
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * Spring Data JPA repository for [AuthToken] entities.
 *
 * Provides queries for the OTP authentication flow. During OTP verification,
 * the submitted code is hashed and looked up by channel + hash + identifier hash.
 * The identifier hash binds each token to the phone/email it was requested for,
 * preventing cross-identity token consumption. Expired tokens are periodically
 * cleaned up to prevent table bloat.
 *
 * The verification query is backed by a partial index on
 * `(channel, token_hash, identifier_hash) WHERE used_at IS NULL` for efficient lookups.
 *
 * @see AuthToken for the entity managed by this repository.
 * @see com.fieldiq.service.AuthService for the authentication logic that uses these queries.
 */
interface AuthTokenRepository : JpaRepository<AuthToken, UUID> {

    /**
     * Finds the most recently created valid (unused) auth token by channel, token hash,
     * and identifier hash.
     *
     * Used during OTP verification: the submitted OTP is hashed with SHA-256, the
     * submitted identifier is normalized and hashed, and the lookup matches on all three
     * fields plus `usedAt IS NULL`. This ensures a token can only be consumed by the
     * same identifier (phone/email) that requested it.
     *
     * Returns the most recent match because duplicate OTP hashes are possible —
     * 6-digit OTPs have a small keyspace, and dev bypass numbers always produce
     * the same code. The most recent token is the one the user is trying to verify.
     *
     * @param channel The authentication channel ("sms" or "email").
     * @param tokenHash The SHA-256 hash of the submitted OTP code.
     * @param identifierHash The SHA-256 hash of the normalized identifier (phone or email).
     * @return The most recently created matching [AuthToken], or null if no unused token matches.
     */
    fun findFirstByChannelAndTokenHashAndIdentifierHashAndUsedAtIsNullOrderByCreatedAtDesc(
        channel: String,
        tokenHash: String,
        identifierHash: String,
    ): AuthToken?

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
