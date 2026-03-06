package com.fieldiq.repository

import com.fieldiq.domain.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [RefreshToken] entities.
 *
 * Provides queries for JWT refresh token management. Tokens are looked up by
 * their SHA-256 hash during the refresh flow, and by user ID for session
 * management (e.g., "revoke all sessions" functionality).
 *
 * **Token rotation:** When a refresh token is used, the old token's [RefreshToken.revokedAt]
 * is set and a new token is created with [RefreshToken.rotatedFrom] pointing to
 * the old token's ID. This chain enables detection of token theft.
 *
 * @see RefreshToken for the entity managed by this repository.
 * @see com.fieldiq.service.AuthService for the token rotation logic.
 */
interface RefreshTokenRepository : JpaRepository<RefreshToken, UUID> {

    /**
     * Finds a refresh token by its SHA-256 hash.
     *
     * Used during the token refresh flow: the client sends the raw refresh token,
     * the service hashes it with SHA-256, and looks up the record to verify
     * validity (not revoked, not expired).
     *
     * @param tokenHash The SHA-256 hash of the raw refresh token.
     * @return The matching [RefreshToken], or null if no token has this hash.
     */
    fun findByTokenHash(tokenHash: String): RefreshToken?

    /**
     * Finds all refresh tokens for a user.
     *
     * Used for session management — revoking all active sessions for a user
     * (e.g., password change, suspicious activity). Returns both active and
     * revoked tokens; callers filter by [RefreshToken.revokedAt] as needed.
     *
     * @param userId The UUID of the user.
     * @return All refresh tokens for the user, empty list if none.
     */
    fun findByUserId(userId: UUID): List<RefreshToken>
}
