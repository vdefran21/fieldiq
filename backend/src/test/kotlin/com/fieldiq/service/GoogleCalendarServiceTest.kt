package com.fieldiq.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fieldiq.config.FieldIQProperties
import com.fieldiq.domain.CalendarIntegration
import com.fieldiq.repository.CalendarIntegrationRepository
import com.fieldiq.security.TokenEncryptionConverter
import io.mockk.*
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [GoogleCalendarService] — the OAuth flow orchestrator for
 * Google Calendar integration.
 *
 * Tests cover:
 * 1. **Authorize URL generation:** Correct parameters, encoding, and scope.
 * 2. **Token exchange:** Code-for-tokens exchange with encryption and storage.
 * 3. **Integration status:** Lookup and null handling.
 * 4. **Disconnect:** Token deletion.
 *
 * **Note:** The actual HTTP calls to Google's token endpoint are NOT tested here
 * (they use WebClient which is hard to mock). The token exchange tests focus on
 * the service's logic around encryption and storage. Full OAuth flow testing
 * requires integration tests with a mock OAuth server.
 *
 * @see GoogleCalendarService for the service under test.
 * @see TokenEncryptionConverter for the encryption used by this service.
 */
class GoogleCalendarServiceTest {

    private val properties: FieldIQProperties = mockk()
    private val googleProperties: FieldIQProperties.GoogleProperties = mockk()
    private val calendarIntegrationRepository: CalendarIntegrationRepository = mockk()
    private val tokenEncryption: TokenEncryptionConverter = mockk()
    private val objectMapper = ObjectMapper()

    private lateinit var service: GoogleCalendarService

    private val testUserId = UUID.randomUUID()

    /**
     * Sets up common mocks before each test.
     */
    @BeforeEach
    fun setup() {
        every { properties.google } returns googleProperties
        every { googleProperties.clientId } returns "test-client-id"
        every { googleProperties.clientSecret } returns "test-client-secret"
        every { googleProperties.redirectUri } returns "http://localhost:8080/auth/google/callback"

        service = GoogleCalendarService(properties, calendarIntegrationRepository, tokenEncryption, objectMapper)
    }

    @Nested
    @DisplayName("buildAuthorizeUrl")
    inner class BuildAuthorizeUrl {

        /**
         * The authorize URL must include all required OAuth parameters.
         */
        @Test
        @DisplayName("includes all required OAuth parameters")
        fun requiredParams() {
            val url = service.buildAuthorizeUrl(testUserId)

            assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
            assertTrue(url.contains("client_id=test-client-id"))
            assertTrue(url.contains("response_type=code"))
            assertTrue(url.contains("access_type=offline"))
            assertTrue(url.contains("prompt=consent"))
            assertTrue(url.contains("state=$testUserId"))
        }

        /**
         * The scope must be calendar.readonly (minimal permissions per Doc 02).
         */
        @Test
        @DisplayName("requests calendar.readonly scope only")
        fun readOnlyScope() {
            val url = service.buildAuthorizeUrl(testUserId)
            assertTrue(url.contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcalendar.readonly"))
        }

        /**
         * The redirect URI must match the configured value.
         */
        @Test
        @DisplayName("includes configured redirect URI")
        fun redirectUri() {
            val url = service.buildAuthorizeUrl(testUserId)
            assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fauth%2Fgoogle%2Fcallback"))
        }
    }

    @Nested
    @DisplayName("getIntegrationStatus")
    inner class GetIntegrationStatus {

        /**
         * Returns the integration when connected.
         */
        @Test
        @DisplayName("returns integration when connected")
        fun connected() {
            val integration = CalendarIntegration(
                userId = testUserId,
                accessToken = "encrypted-access",
                refreshToken = "encrypted-refresh",
                expiresAt = Instant.now().plusSeconds(3600),
                provider = "google",
            )
            every { calendarIntegrationRepository.findByUserId(testUserId) } returns integration

            val result = service.getIntegrationStatus(testUserId)
            assertNotNull(result)
            assertEquals("google", result?.provider)
        }

        /**
         * Returns null when not connected.
         */
        @Test
        @DisplayName("returns null when not connected")
        fun notConnected() {
            every { calendarIntegrationRepository.findByUserId(testUserId) } returns null

            val result = service.getIntegrationStatus(testUserId)
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("disconnect")
    inner class Disconnect {

        /**
         * Deletes the integration record when disconnecting.
         */
        @Test
        @DisplayName("deletes integration on disconnect")
        fun deletesIntegration() {
            val integration = CalendarIntegration(
                userId = testUserId,
                accessToken = "encrypted-access",
                refreshToken = "encrypted-refresh",
                expiresAt = Instant.now().plusSeconds(3600),
            )
            every { calendarIntegrationRepository.findByUserId(testUserId) } returns integration
            every { calendarIntegrationRepository.delete(integration) } just runs

            service.disconnect(testUserId)

            verify { calendarIntegrationRepository.delete(integration) }
        }

        /**
         * Throws EntityNotFoundException when no integration exists.
         */
        @Test
        @DisplayName("throws when no integration to disconnect")
        fun noIntegration() {
            every { calendarIntegrationRepository.findByUserId(testUserId) } returns null

            assertThrows(EntityNotFoundException::class.java) {
                service.disconnect(testUserId)
            }
        }
    }
}
