package com.fieldiq.api.dto

import java.time.Instant

/**
 * Response DTO for Google Calendar integration status.
 *
 * Returned by `GET /auth/google/status` to show whether the user has connected
 * their Google Calendar and when the last sync occurred.
 *
 * @property connected Whether the user has a linked Google Calendar integration.
 * @property provider The calendar provider ("google"). Null if not connected.
 * @property lastSyncedAt Timestamp of the last successful calendar sync. Null if
 *   never synced or not connected.
 * @property expiresAt When the current access token expires. Null if not connected.
 */
data class CalendarIntegrationStatusResponse(
    val connected: Boolean,
    val provider: String? = null,
    val lastSyncedAt: Instant? = null,
    val expiresAt: Instant? = null,
)

/**
 * Response DTO for the browser-safe Google OAuth handoff URL.
 *
 * Mobile clients cannot attach a bearer token directly to a browser redirect, so
 * they first call `GET /auth/google/authorize-url` to fetch the fully-qualified
 * Google consent URL for the authenticated user.
 *
 * @property authorizeUrl Google consent screen URL scoped to the current user.
 */
data class GoogleAuthorizeUrlResponse(
    val authorizeUrl: String,
)
