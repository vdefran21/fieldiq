package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Represents a refresh token for JWT session management.
 *
 * FieldIQ uses short-lived JWT access tokens (15 minutes) paired with long-lived
 * refresh tokens (30 days). When an access token expires, the client sends the
 * refresh token to `POST /auth/refresh` to obtain a new access/refresh token pair.
 *
 * **Rotation model:** Refresh tokens are single-use. When a token is used to obtain
 * a new pair, the old token is immediately revoked ([revokedAt] set) and a new token
 * is issued. The [rotatedFrom] field creates a chain linking each token to its
 * predecessor, enabling detection of token theft — if a revoked token is presented,
 * the entire chain can be invalidated.
 *
 * **Security:** Only a SHA-256 hash of the token is stored. The raw token is returned
 * to the client once and never stored server-side. A database breach does not expose
 * valid refresh tokens.
 *
 * @property id Unique identifier, auto-generated UUID.
 * @property userId Foreign key to the [User] who owns this session. NOT NULL —
 *   refresh tokens always belong to an authenticated user.
 * @property tokenHash SHA-256 hash of the refresh token. UNIQUE constraint ensures
 *   no two tokens share the same hash.
 * @property expiresAt Timestamp after which this token cannot be used for refresh.
 *   Typically 30 days after creation.
 * @property revokedAt Timestamp when this token was revoked. Null if still active.
 *   Set during token rotation or explicit logout.
 * @property rotatedFrom UUID of the previous [RefreshToken] in the rotation chain.
 *   Null for the first token issued in a session. Used for chain invalidation
 *   on suspected token theft.
 * @property deviceInfo Optional device description (e.g., "iPhone 15, iOS 19").
 *   Stored for audit purposes and future "active sessions" UI.
 * @property createdAt Timestamp of token creation. Immutable.
 * @see User for the user who owns this session.
 */
@Entity
@Table(name = "refresh_tokens")
data class RefreshToken(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "token_hash", nullable = false, unique = true)
    val tokenHash: String,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "revoked_at")
    val revokedAt: Instant? = null,

    @Column(name = "rotated_from")
    val rotatedFrom: UUID? = null,

    @Column(name = "device_info")
    val deviceInfo: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
