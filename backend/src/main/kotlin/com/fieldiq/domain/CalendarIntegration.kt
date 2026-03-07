package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Represents a user's connected Google Calendar integration.
 *
 * Stores the encrypted OAuth tokens needed to access Google Calendar's FreeBusy API
 * on behalf of the user. Each user can have at most one calendar integration
 * (UNIQUE constraint on [userId] in the database).
 *
 * **Token encryption:** The [accessToken] and [refreshToken] fields are stored
 * encrypted at rest using AES-256-GCM via [com.fieldiq.security.TokenEncryptionConverter].
 * The converter is applied as a JPA `@Convert` annotation — encryption/decryption
 * is transparent to service-layer code.
 *
 * **Lifecycle:**
 * 1. Created when a user completes the Google OAuth flow (`/auth/google/callback`).
 * 2. Updated when the access token is refreshed (using the stored refresh token).
 * 3. [lastSyncedAt] updated after each successful FreeBusy sync by the agent layer.
 * 4. Deleted if the user disconnects their Google Calendar in settings.
 *
 * **Privacy:** FieldIQ only requests `calendar.readonly` scope and only uses the
 * FreeBusy endpoint — we never read event titles, descriptions, or attendees.
 *
 * @property id Unique identifier, auto-generated UUID.
 * @property userId Foreign key to [User]. Each user has at most one integration (UNIQUE).
 * @property provider Calendar provider identifier. Always "google" in Phase 1.
 * @property accessToken Google OAuth access token, encrypted at rest via AES-256-GCM.
 *   Short-lived (typically 1 hour). Refreshed using [refreshToken] before each sync.
 * @property refreshToken Google OAuth refresh token, encrypted at rest via AES-256-GCM.
 *   Long-lived. Used to obtain new access tokens without re-prompting the user.
 * @property expiresAt Expiration time of the current [accessToken]. The agent layer
 *   checks this before making API calls and refreshes if expired.
 * @property scope OAuth scope granted by the user. Expected: "https://www.googleapis.com/auth/calendar.readonly".
 * @property lastSyncedAt Timestamp of the last successful FreeBusy sync. Null if never synced.
 *   Used to determine when the next sync should run (every 4 hours).
 * @property createdAt Timestamp of integration creation (OAuth flow completion).
 * @see com.fieldiq.security.TokenEncryptionConverter for the AES-256-GCM encryption.
 * @see com.fieldiq.service.GoogleCalendarService for the OAuth flow orchestration.
 */
@Entity
@Table(name = "calendar_integrations")
data class CalendarIntegration(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, unique = true)
    val userId: UUID,

    @Column(nullable = false)
    val provider: String = "google",

    @Column(name = "access_token", nullable = false)
    val accessToken: String,

    @Column(name = "refresh_token", nullable = false)
    val refreshToken: String,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column
    val scope: String? = null,

    @Column(name = "last_synced_at")
    val lastSyncedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
