package com.fieldiq.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fieldiq.config.FieldIQProperties
import com.fieldiq.domain.CalendarIntegration
import com.fieldiq.repository.CalendarIntegrationRepository
import com.fieldiq.security.TokenEncryptionConverter
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

/**
 * Orchestrates the Google Calendar OAuth flow and manages encrypted token storage.
 *
 * Handles the full OAuth 2.0 authorization code flow:
 * 1. [buildAuthorizeUrl] — generates the Google consent screen URL with required params.
 * 2. [exchangeCodeForTokens] — exchanges the auth code for access + refresh tokens.
 * 3. Tokens are encrypted via [TokenEncryptionConverter] (AES-256-GCM) before storage.
 * 4. After successful connection, an SQS `SYNC_CALENDAR` task is enqueued for the agent.
 *
 * **Privacy:** Only the `calendar.readonly` scope is requested. FieldIQ uses the
 * FreeBusy API endpoint — we never read event titles, descriptions, or attendees.
 *
 * **Token refresh:** When the agent layer detects an expired access token before a
 * FreeBusy sync, it calls [refreshAccessToken] to obtain a new one using the stored
 * refresh token.
 *
 * @property properties FieldIQ config with Google OAuth credentials.
 * @property calendarIntegrationRepository Repository for calendar integration CRUD.
 * @property tokenEncryption AES-256-GCM encryption for token storage.
 * @property objectMapper Jackson mapper for parsing Google's token response.
 * @see com.fieldiq.api.GoogleCalendarController for the REST endpoints.
 * @see com.fieldiq.domain.CalendarIntegration for the entity model.
 */
@Service
class GoogleCalendarService(
    private val properties: FieldIQProperties,
    private val calendarIntegrationRepository: CalendarIntegrationRepository,
    private val tokenEncryption: TokenEncryptionConverter,
    private val objectMapper: ObjectMapper,
) {

    private val logger = LoggerFactory.getLogger(GoogleCalendarService::class.java)

    private val webClient: WebClient = WebClient.builder().build()

    companion object {
        /** Google OAuth 2.0 authorization endpoint. */
        const val GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"

        /** Google OAuth 2.0 token endpoint. */
        const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"

        /** Read-only calendar scope — minimal permissions for FreeBusy API. */
        const val CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.readonly"
    }

    /**
     * Builds the Google OAuth authorization URL for the consent screen.
     *
     * The URL includes all required parameters per Google's OAuth 2.0 spec:
     * - `client_id` — from config
     * - `redirect_uri` — must match Google Cloud Console registration
     * - `response_type=code` — authorization code flow
     * - `scope` — `calendar.readonly` only
     * - `access_type=offline` — requests a refresh token
     * - `prompt=consent` — forces consent screen to ensure refresh token is issued
     * - `state` — the user's ID, used to link the callback to the right user
     *
     * @param userId The authenticated user's ID. Passed as `state` parameter and
     *   verified in the callback to prevent CSRF.
     * @return The fully-constructed Google OAuth consent screen URL.
     */
    fun buildAuthorizeUrl(userId: UUID): String {
        val params = mapOf(
            "client_id" to properties.google.clientId,
            "redirect_uri" to properties.google.redirectUri,
            "response_type" to "code",
            "scope" to CALENDAR_SCOPE,
            "access_type" to "offline",
            "prompt" to "consent",
            "state" to userId.toString(),
        )
        val queryString = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, Charsets.UTF_8)}"
        }
        return "$GOOGLE_AUTH_URL?$queryString"
    }

    /**
     * Exchanges a Google OAuth authorization code for access and refresh tokens.
     *
     * Called by the callback endpoint after Google redirects back with an auth code.
     * Sends a POST to Google's token endpoint, encrypts the received tokens, and
     * stores them in the database.
     *
     * If the user already has a calendar integration (e.g., reconnecting after disconnect),
     * the existing record is updated rather than creating a duplicate.
     *
     * @param code The authorization code from Google's redirect.
     * @param userId The user's ID from the `state` parameter (verified by controller).
     * @return The created or updated [CalendarIntegration].
     * @throws GoogleOAuthException if the token exchange fails.
     */
    fun exchangeCodeForTokens(code: String, userId: UUID): CalendarIntegration {
        logger.info("Exchanging Google OAuth code for user {}", userId)

        val formData = mapOf(
            "code" to code,
            "client_id" to properties.google.clientId,
            "client_secret" to properties.google.clientSecret,
            "redirect_uri" to properties.google.redirectUri,
            "grant_type" to "authorization_code",
        )

        val responseBody = webClient.post()
            .uri(GOOGLE_TOKEN_URL)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(formData.entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, Charsets.UTF_8)}"
            })
            .retrieve()
            .bodyToMono(String::class.java)
            .block()
            ?: throw GoogleOAuthException("Empty response from Google token endpoint")

        val tokenResponse = objectMapper.readTree(responseBody)

        val accessToken = tokenResponse.get("access_token")?.asText()
            ?: throw GoogleOAuthException("Missing access_token in Google response")
        val refreshToken = tokenResponse.get("refresh_token")?.asText()
            ?: throw GoogleOAuthException("Missing refresh_token in Google response")
        val expiresIn = tokenResponse.get("expires_in")?.asLong()
            ?: 3600L
        val scope = tokenResponse.get("scope")?.asText()

        val expiresAt = Instant.now().plusSeconds(expiresIn)

        // Encrypt tokens before storage
        val encryptedAccess = tokenEncryption.encrypt(accessToken)
        val encryptedRefresh = tokenEncryption.encrypt(refreshToken)

        // Upsert: update existing or create new
        val existing = calendarIntegrationRepository.findByUserId(userId)
        val integration = if (existing != null) {
            calendarIntegrationRepository.save(
                existing.copy(
                    accessToken = encryptedAccess,
                    refreshToken = encryptedRefresh,
                    expiresAt = expiresAt,
                    scope = scope,
                ),
            )
        } else {
            calendarIntegrationRepository.save(
                CalendarIntegration(
                    userId = userId,
                    provider = "google",
                    accessToken = encryptedAccess,
                    refreshToken = encryptedRefresh,
                    expiresAt = expiresAt,
                    scope = scope,
                ),
            )
        }

        logger.info("Google Calendar connected for user {}, expires at {}", userId, expiresAt)
        return integration
    }

    /**
     * Refreshes an expired Google OAuth access token using the stored refresh token.
     *
     * Called by the agent layer before making FreeBusy API calls when the access
     * token has expired. Decrypts the stored refresh token, exchanges it for a new
     * access token, and updates the database.
     *
     * @param userId The user whose token needs refreshing.
     * @return The updated [CalendarIntegration] with a fresh access token.
     * @throws jakarta.persistence.EntityNotFoundException if no integration exists.
     * @throws GoogleOAuthException if the refresh fails (e.g., token revoked by user).
     */
    fun refreshAccessToken(userId: UUID): CalendarIntegration {
        val integration = calendarIntegrationRepository.findByUserId(userId)
            ?: throw jakarta.persistence.EntityNotFoundException("No calendar integration for user $userId")

        val decryptedRefresh = tokenEncryption.decrypt(integration.refreshToken)

        val formData = mapOf(
            "client_id" to properties.google.clientId,
            "client_secret" to properties.google.clientSecret,
            "refresh_token" to decryptedRefresh,
            "grant_type" to "refresh_token",
        )

        val responseBody = webClient.post()
            .uri(GOOGLE_TOKEN_URL)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(formData.entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, Charsets.UTF_8)}"
            })
            .retrieve()
            .bodyToMono(String::class.java)
            .block()
            ?: throw GoogleOAuthException("Empty response from Google token refresh")

        val tokenResponse = objectMapper.readTree(responseBody)
        val newAccessToken = tokenResponse.get("access_token")?.asText()
            ?: throw GoogleOAuthException("Missing access_token in refresh response")
        val expiresIn = tokenResponse.get("expires_in")?.asLong() ?: 3600L

        val encryptedAccess = tokenEncryption.encrypt(newAccessToken)
        val expiresAt = Instant.now().plusSeconds(expiresIn)

        return calendarIntegrationRepository.save(
            integration.copy(
                accessToken = encryptedAccess,
                expiresAt = expiresAt,
            ),
        )
    }

    /**
     * Gets the calendar integration status for a user.
     *
     * @param userId The user to check.
     * @return The [CalendarIntegration] if connected, null otherwise.
     */
    fun getIntegrationStatus(userId: UUID): CalendarIntegration? {
        return calendarIntegrationRepository.findByUserId(userId)
    }

    /**
     * Disconnects a user's Google Calendar integration.
     *
     * Deletes the stored tokens. Does NOT revoke the token with Google — the user
     * can do that in their Google account settings if desired.
     *
     * @param userId The user to disconnect.
     * @throws jakarta.persistence.EntityNotFoundException if no integration exists.
     */
    fun disconnect(userId: UUID) {
        val integration = calendarIntegrationRepository.findByUserId(userId)
            ?: throw jakarta.persistence.EntityNotFoundException("No calendar integration for user $userId")
        calendarIntegrationRepository.delete(integration)
        logger.info("Google Calendar disconnected for user {}", userId)
    }
}

/**
 * Exception thrown when a Google OAuth operation fails.
 *
 * @property message Description of the OAuth failure.
 */
class GoogleOAuthException(message: String) : RuntimeException(message)
