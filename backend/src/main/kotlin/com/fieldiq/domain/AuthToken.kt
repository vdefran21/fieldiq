package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Represents a one-time passwordless authentication token (OTP or magic link).
 *
 * When a user requests an OTP via SMS or email, the backend generates a 6-digit code,
 * hashes it with SHA-256, and stores the hash in this table. The raw code is sent to
 * the user via the requested channel. On verification, the submitted code is hashed
 * and compared against this record.
 *
 * **Lifecycle:**
 * 1. Created when `POST /auth/request-otp` is called.
 * 2. Marked as used ([usedAt] set) when `POST /auth/verify-otp` succeeds.
 * 3. Expired tokens (past [expiresAt]) are rejected during verification and
 *    periodically cleaned up by a scheduled job.
 *
 * **Security:** The raw OTP is never stored. Only a SHA-256 hash of the token is
 * persisted, so a database breach does not expose valid OTPs. The [tokenHash] column
 * has a UNIQUE constraint to prevent duplicate token collisions.
 *
 * The [userId] field is nullable because a token may be created before the user
 * exists (first-time sign-up flow — the user record is created on successful
 * verification, not on OTP request).
 *
 * @property id Unique identifier, auto-generated UUID.
 * @property userId Foreign key to the [User] who requested this token. Null for
 *   first-time users who don't have an account yet.
 * @property tokenHash SHA-256 hash of the OTP code or magic-link token. UNIQUE
 *   constraint prevents collisions.
 * @property channel Authentication channel: "sms" for phone OTP, "email" for
 *   magic-link. Enforced by CHECK constraint.
 * @property expiresAt Timestamp after which this token is no longer valid.
 *   Typically 10 minutes after creation for OTPs.
 * @property usedAt Timestamp when this token was successfully verified. Null if
 *   not yet used. A non-null value prevents token reuse.
 * @property createdAt Timestamp of token creation. Immutable.
 * @see User for the user this token authenticates.
 */
@Entity
@Table(name = "auth_tokens")
data class AuthToken(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id")
    val userId: UUID? = null,

    @Column(name = "token_hash", nullable = false, unique = true)
    val tokenHash: String,

    @Column(nullable = false)
    val channel: String,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "used_at")
    val usedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
