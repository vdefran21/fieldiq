package com.fieldiq.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.fieldiq.domain.NegotiationSession
import com.fieldiq.repository.NegotiationSessionRepository
import com.fieldiq.service.CrossInstanceRelayClient.Companion.HEADER_INSTANCE_ID
import com.fieldiq.service.CrossInstanceRelayClient.Companion.HEADER_SESSION_ID
import com.fieldiq.service.CrossInstanceRelayClient.Companion.HEADER_SIGNATURE
import com.fieldiq.service.CrossInstanceRelayClient.Companion.HEADER_TIMESTAMP
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Unit tests for [HmacAuthenticationFilter] — the inbound security gate for
 * cross-instance relay requests.
 *
 * Tests cover:
 * 1. **Path filtering:** Only relay endpoint requests are processed.
 * 2. **Header validation:** Missing headers produce 401 errors.
 * 3. **Session lookup:** Unknown session IDs produce 404 errors.
 * 4. **HMAC validation:** Invalid signatures produce 401 errors.
 * 5. **Replay prevention:** Duplicate signatures (same nonce) produce 401 errors.
 * 6. **Happy path:** Valid requests pass through to the filter chain.
 *
 * **Testing approach:** All dependencies are mocked via MockK. The filter is tested
 * via the public [doFilter] method (inherited from [OncePerRequestFilter]), using
 * [MockHttpServletRequest] and [MockHttpServletResponse] from Spring Test.
 *
 * @see HmacAuthenticationFilter for the filter under test.
 * @see HmacService for the signature validation it delegates to.
 */
class HmacAuthenticationFilterTest {

    private val hmacService: HmacService = mockk()
    private val sessionRepository: NegotiationSessionRepository = mockk()
    private val redisTemplate: StringRedisTemplate = mockk()
    private val valueOps: ValueOperations<String, String> = mockk()
    private val objectMapper = ObjectMapper()
    private val filterChain: FilterChain = mockk(relaxed = true)

    private lateinit var filter: HmacAuthenticationFilter

    private val sessionId = UUID.randomUUID()
    private val inviteToken = "test-invite-token"
    private val sessionKey = ByteArray(32) { it.toByte() }
    private val testBody = """{"action":"propose","roundNumber":1}"""

    /**
     * Creates the filter and sets up common mock behavior before each test.
     */
    @BeforeEach
    fun setup() {
        every { redisTemplate.opsForValue() } returns valueOps
        filter = HmacAuthenticationFilter(hmacService, sessionRepository, redisTemplate, objectMapper)
    }

    @Nested
    @DisplayName("path filtering")
    inner class PathFiltering {

        /**
         * Non-relay requests should pass through untouched — filter is skipped.
         * We verify by checking that the filterChain.doFilter is called without
         * any HMAC validation (no session lookup, no signature check).
         */
        @Test
        @DisplayName("skips non-relay endpoints")
        fun skipsNonRelay() {
            val request = MockHttpServletRequest("GET", "/teams/123")
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, filterChain)

            // Filter chain should be called (request passes through)
            verify { filterChain.doFilter(any(), any()) }
            // Response status should be default (200), not an HMAC error
            assertEquals(200, response.status)
        }

        /**
         * Auth endpoints should be skipped by HMAC filter.
         */
        @Test
        @DisplayName("skips auth endpoints")
        fun skipsAuth() {
            val request = MockHttpServletRequest("POST", "/auth/request-otp")
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, filterChain)

            verify { filterChain.doFilter(any(), any()) }
            assertEquals(200, response.status)
        }

        /**
         * Relay endpoints should be processed by this filter — missing headers means 401.
         */
        @Test
        @DisplayName("intercepts relay endpoints")
        fun interceptsRelay() {
            val request = MockHttpServletRequest("POST", "/api/negotiate/$sessionId/relay")
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, filterChain)

            // Should get a 401 because no HMAC headers are present
            assertEquals(401, response.status)
        }
    }

    @Nested
    @DisplayName("header validation")
    inner class HeaderValidation {

        /**
         * Missing all HMAC headers should produce a 401 error.
         */
        @Test
        @DisplayName("rejects request with missing headers")
        fun missingHeaders() {
            val request = MockHttpServletRequest("POST", "/api/negotiate/$sessionId/relay")
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, filterChain)

            assertEquals(401, response.status)
            assertTrue(response.contentAsString.contains("invalid_signature"))
        }

        /**
         * Missing just the signature header should produce a 401 error.
         */
        @Test
        @DisplayName("rejects request with missing signature header")
        fun missingSignature() {
            val request = MockHttpServletRequest("POST", "/api/negotiate/$sessionId/relay").apply {
                addHeader(HEADER_SESSION_ID, sessionId.toString())
                addHeader(HEADER_TIMESTAMP, Instant.now().toString())
            }
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, filterChain)

            assertEquals(401, response.status)
        }
    }

    @Nested
    @DisplayName("session lookup")
    inner class SessionLookup {

        /**
         * An invalid UUID format in the session ID header should produce a 400 error.
         */
        @Test
        @DisplayName("rejects invalid session ID format")
        fun invalidFormat() {
            val request = MockHttpServletRequest("POST", "/api/negotiate/not-a-uuid/relay").apply {
                addHeader(HEADER_SESSION_ID, "not-a-uuid")
                addHeader(HEADER_TIMESTAMP, Instant.now().toString())
                addHeader(HEADER_SIGNATURE, "a".repeat(64))
            }
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, filterChain)

            assertEquals(400, response.status)
            assertTrue(response.contentAsString.contains("session_not_found"))
        }

        /**
         * A valid UUID that doesn't match any session should produce a 404 error.
         */
        @Test
        @DisplayName("rejects unknown session ID")
        fun unknownSession() {
            val unknownId = UUID.randomUUID()
            every { sessionRepository.findById(unknownId) } returns Optional.empty()

            val request = MockHttpServletRequest("POST", "/api/negotiate/$unknownId/relay").apply {
                addHeader(HEADER_SESSION_ID, unknownId.toString())
                addHeader(HEADER_TIMESTAMP, Instant.now().toString())
                addHeader(HEADER_SIGNATURE, "a".repeat(64))
            }
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, filterChain)

            assertEquals(404, response.status)
            assertTrue(response.contentAsString.contains("session_not_found"))
        }
    }

    @Nested
    @DisplayName("HMAC validation")
    inner class HmacValidation {

        /**
         * A request with a valid HMAC signature should pass through to the filter chain.
         */
        @Test
        @DisplayName("passes valid request through to filter chain")
        fun validRequest() {
            val timestamp = Instant.now().toString()
            val signature = "valid-signature-hex"

            val session = mockk<NegotiationSession>()
            every { session.inviteToken } returns inviteToken
            every { sessionRepository.findById(sessionId) } returns Optional.of(session)
            every { hmacService.deriveSessionKey(inviteToken) } returns sessionKey
            every { hmacService.validate(sessionKey, sessionId.toString(), timestamp, any(), signature) } returns true
            every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns true

            val request = MockHttpServletRequest("POST", "/api/negotiate/$sessionId/relay").apply {
                addHeader(HEADER_SESSION_ID, sessionId.toString())
                addHeader(HEADER_TIMESTAMP, timestamp)
                addHeader(HEADER_SIGNATURE, signature)
                addHeader(HEADER_INSTANCE_ID, "instance-b")
                setContent(testBody.toByteArray())
            }
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, filterChain)

            verify { filterChain.doFilter(any(), response) }
        }

        /**
         * A request with an invalid HMAC signature should be rejected with 401.
         */
        @Test
        @DisplayName("rejects invalid signature")
        fun invalidSignature() {
            val timestamp = Instant.now().toString()

            val session = mockk<NegotiationSession>()
            every { session.inviteToken } returns inviteToken
            every { sessionRepository.findById(sessionId) } returns Optional.of(session)
            every { hmacService.deriveSessionKey(inviteToken) } returns sessionKey
            every { hmacService.validate(sessionKey, sessionId.toString(), timestamp, any(), "bad-sig") } returns false

            val request = MockHttpServletRequest("POST", "/api/negotiate/$sessionId/relay").apply {
                addHeader(HEADER_SESSION_ID, sessionId.toString())
                addHeader(HEADER_TIMESTAMP, timestamp)
                addHeader(HEADER_SIGNATURE, "bad-sig")
                addHeader(HEADER_INSTANCE_ID, "instance-b")
                setContent(testBody.toByteArray())
            }
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, filterChain)

            assertEquals(401, response.status)
            assertTrue(response.contentAsString.contains("invalid_signature"))
        }

        /**
         * A session with a consumed invite token (null) should return 401 since
         * the session key cannot be derived.
         */
        @Test
        @DisplayName("rejects request when invite token is consumed")
        fun consumedToken() {
            val session = mockk<NegotiationSession>()
            every { session.inviteToken } returns null
            every { sessionRepository.findById(sessionId) } returns Optional.of(session)

            val request = MockHttpServletRequest("POST", "/api/negotiate/$sessionId/relay").apply {
                addHeader(HEADER_SESSION_ID, sessionId.toString())
                addHeader(HEADER_TIMESTAMP, Instant.now().toString())
                addHeader(HEADER_SIGNATURE, "a".repeat(64))
                setContent(testBody.toByteArray())
            }
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, filterChain)

            assertEquals(401, response.status)
        }
    }

    @Nested
    @DisplayName("replay prevention")
    inner class ReplayPrevention {

        /**
         * A duplicate signature (nonce already in Redis) should be rejected as a replay.
         */
        @Test
        @DisplayName("rejects duplicate signature as replay attack")
        fun replayDetected() {
            val timestamp = Instant.now().toString()
            val signature = "replayed-signature"

            val session = mockk<NegotiationSession>()
            every { session.inviteToken } returns inviteToken
            every { sessionRepository.findById(sessionId) } returns Optional.of(session)
            every { hmacService.deriveSessionKey(inviteToken) } returns sessionKey
            every { hmacService.validate(sessionKey, sessionId.toString(), timestamp, any(), signature) } returns true
            // Redis returns false = nonce already exists
            every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns false

            val request = MockHttpServletRequest("POST", "/api/negotiate/$sessionId/relay").apply {
                addHeader(HEADER_SESSION_ID, sessionId.toString())
                addHeader(HEADER_TIMESTAMP, timestamp)
                addHeader(HEADER_SIGNATURE, signature)
                addHeader(HEADER_INSTANCE_ID, "instance-b")
                setContent(testBody.toByteArray())
            }
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, filterChain)

            assertEquals(401, response.status)
            assertTrue(response.contentAsString.contains("replay_detected"))
        }
    }
}
