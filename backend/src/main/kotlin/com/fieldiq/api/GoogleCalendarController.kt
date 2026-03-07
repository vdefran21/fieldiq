package com.fieldiq.api

import com.fieldiq.api.dto.CalendarIntegrationStatusResponse
import com.fieldiq.security.authenticatedUserId
import com.fieldiq.service.GoogleCalendarService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI

/**
 * REST controller for Google Calendar OAuth integration.
 *
 * Handles the Google OAuth 2.0 authorization code flow:
 * 1. [authorize] — redirects the user to Google's consent screen.
 * 2. [callback] — handles Google's redirect with the auth code, exchanges it for tokens.
 * 3. [status] — returns whether the user has connected their calendar.
 * 4. [disconnect] — removes the user's calendar integration.
 *
 * **Auth requirements:**
 * - [authorize] requires JWT auth (user must be logged in before connecting calendar).
 * - [callback] is public (Google redirects here, no JWT in the redirect URL).
 *   The user ID is passed via the OAuth `state` parameter.
 * - [status] and [disconnect] require JWT auth.
 *
 * @property googleCalendarService Business logic for OAuth flow and token management.
 * @see GoogleCalendarService for the underlying business logic.
 * @see com.fieldiq.security.TokenEncryptionConverter for token encryption.
 */
@RestController
@RequestMapping("/auth/google")
class GoogleCalendarController(
    private val googleCalendarService: GoogleCalendarService,
) {

    private val logger = LoggerFactory.getLogger(GoogleCalendarController::class.java)

    /**
     * Initiates the Google OAuth flow by redirecting to Google's consent screen.
     *
     * The user must be authenticated (JWT required) so we can pass their user ID
     * as the OAuth `state` parameter. Google will redirect back to [callback] with
     * this state value, allowing us to link the tokens to the right user.
     *
     * @return 302 redirect to Google's OAuth consent screen.
     */
    @GetMapping("/authorize")
    fun authorize(): ResponseEntity<Void> {
        val userId = authenticatedUserId()
        val authorizeUrl = googleCalendarService.buildAuthorizeUrl(userId)
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(authorizeUrl))
            .build()
    }

    /**
     * Handles the OAuth callback from Google after user grants permission.
     *
     * Google redirects here with `?code=...&state=<userId>`. The code is exchanged
     * for access + refresh tokens, which are encrypted and stored.
     *
     * This endpoint is public (no JWT) because Google's redirect cannot include
     * an Authorization header. The user ID is verified from the `state` parameter.
     *
     * After successful token exchange, returns a simple success message. In the
     * mobile app flow, this page would deep-link back to the app.
     *
     * @param code The authorization code from Google.
     * @param state The user ID passed during [authorize], used to link tokens to the user.
     * @param error Optional error code if the user denied consent.
     * @return 200 with success message, or 400 if an error occurred.
     */
    @GetMapping("/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
    ): ResponseEntity<Map<String, String>> {
        if (error != null) {
            logger.warn("Google OAuth denied: error={}", error)
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "OAUTH_DENIED", "message" to "User denied Google Calendar access"))
        }

        if (code == null || state == null) {
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "BAD_REQUEST", "message" to "Missing code or state parameter"))
        }

        val userId = try {
            java.util.UUID.fromString(state)
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest()
                .body(mapOf("error" to "BAD_REQUEST", "message" to "Invalid state parameter"))
        }

        googleCalendarService.exchangeCodeForTokens(code, userId)

        // TODO: Enqueue SYNC_CALENDAR SQS task here once agent layer is ready

        return ResponseEntity.ok(
            mapOf("status" to "connected", "message" to "Google Calendar connected successfully"),
        )
    }

    /**
     * Returns the user's Google Calendar integration status.
     *
     * Used by the mobile app's Settings screen to show whether the calendar
     * is connected and when the last sync occurred.
     *
     * @return Integration status including connection state and last sync time.
     */
    @GetMapping("/status")
    fun status(): ResponseEntity<CalendarIntegrationStatusResponse> {
        val userId = authenticatedUserId()
        val integration = googleCalendarService.getIntegrationStatus(userId)

        return ResponseEntity.ok(
            if (integration != null) {
                CalendarIntegrationStatusResponse(
                    connected = true,
                    provider = integration.provider,
                    lastSyncedAt = integration.lastSyncedAt,
                    expiresAt = integration.expiresAt,
                )
            } else {
                CalendarIntegrationStatusResponse(connected = false)
            },
        )
    }

    /**
     * Disconnects the user's Google Calendar integration.
     *
     * Deletes stored tokens. Does not revoke the token with Google.
     *
     * @return 204 No Content on success.
     */
    @DeleteMapping("/disconnect")
    fun disconnect(): ResponseEntity<Void> {
        val userId = authenticatedUserId()
        googleCalendarService.disconnect(userId)
        return ResponseEntity.noContent().build()
    }
}
