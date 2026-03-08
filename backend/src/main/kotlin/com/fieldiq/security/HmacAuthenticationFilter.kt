package com.fieldiq.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.fieldiq.api.dto.RelayErrorResponse
import com.fieldiq.repository.NegotiationSessionRepository
import com.fieldiq.service.CrossInstanceRelayClient.Companion.HEADER_INSTANCE_ID
import com.fieldiq.service.CrossInstanceRelayClient.Companion.HEADER_SESSION_ID
import com.fieldiq.service.CrossInstanceRelayClient.Companion.HEADER_SIGNATURE
import com.fieldiq.service.CrossInstanceRelayClient.Companion.HEADER_TIMESTAMP
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletRequestWrapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * Servlet filter that validates HMAC-SHA256 signatures on incoming cross-instance relay requests.
 *
 * This filter only applies to requests matching `/api/negotiate/` paths. It enforces the
 * authentication scheme defined in Doc 04:
 *
 * 1. **Header extraction:** Reads `X-FieldIQ-Session-Id`, `X-FieldIQ-Timestamp`,
 *    `X-FieldIQ-Signature`, and `X-FieldIQ-Instance-Id` from the request.
 * 2. **Session lookup:** Loads the negotiation session to retrieve the invite token
 *    for HMAC key derivation.
 * 3. **Timestamp validation:** Rejects if clock drift exceeds ±5 minutes.
 * 4. **Signature validation:** Recomputes the expected HMAC and compares using
 *    constant-time equality.
 * 5. **Replay prevention:** Hashes the signature and stores it as a nonce in Redis
 *    with a 5-minute TTL (`SET fieldiq:nonce:<hash> 1 EX 300 NX`). If the nonce
 *    already exists, the request is a replay.
 *
 * **Why this is a servlet filter (not a Spring Security filter):**
 * Cross-instance relay uses a fundamentally different auth model than user JWT auth.
 * Mixing them in the Spring Security filter chain would complicate both. Instead,
 * relay endpoints are `permitAll()` in [SecurityConfig] and protected by this
 * dedicated filter.
 *
 * **Request body preservation:** The filter buffers the raw body bytes so the HMAC can
 * be validated and then forwards a wrapper that replays the same bytes to Spring MVC.
 * Relay controllers therefore receive the original JSON payload even after this
 * filter has inspected it.
 *
 * @property hmacService Provides signature computation and validation.
 * @property sessionRepository Loads negotiation sessions for key derivation.
 * @property redisTemplate Redis client for nonce-based replay prevention.
 * @property objectMapper Jackson mapper for error response serialization.
 * @see HmacService for the cryptographic operations.
 * @see CrossInstanceRelayClient for the outbound side that generates these signatures.
 */
@Component
class HmacAuthenticationFilter(
    private val hmacService: HmacService,
    private val sessionRepository: NegotiationSessionRepository,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(HmacAuthenticationFilter::class.java)

    companion object {
        /** Redis key prefix for replay prevention nonces. */
        const val NONCE_PREFIX = "fieldiq:nonce:"

        /** TTL for replay prevention nonces — matches the timestamp drift window. */
        val NONCE_TTL: Duration = Duration.ofMinutes(5)

        /**
         * Redis key prefix for cached HMAC session keys.
         *
         * After the responder joins a negotiation (consuming the invite token),
         * the derived session key is cached in Redis so that subsequent relay calls
         * can still validate HMAC signatures. The key format is:
         * `fieldiq:sessionkey:<sessionId>` and the value is Base64-encoded.
         *
         * Written by [com.fieldiq.service.NegotiationService.joinSession].
         * Read by this filter when `session.inviteToken` is null (post-join).
         */
        const val SESSION_KEY_PREFIX = "fieldiq:sessionkey:"

        /**
         * TTL for cached session keys — 72 hours.
         *
         * Covers the 48h invite token TTL plus additional negotiation duration.
         * After this TTL expires, relay calls for this session will fail with 401.
         */
        val SESSION_KEY_TTL: Duration = Duration.ofHours(72)
    }

    /**
     * Only apply this filter to cross-instance relay endpoints, excluding `/api/negotiate/incoming`.
     *
     * The `/incoming` endpoint is excluded because it bootstraps a shadow session on Instance B
     * during the join handshake. At that point, no local session exists on Instance B, so the
     * HMAC filter cannot look up a session for key derivation. The invite token in the request
     * body serves as a bearer credential — it is 36 bytes of cryptographic randomness generated
     * by Instance A and shared only with session participants.
     *
     * All other requests (user-facing REST API, health checks) skip this filter entirely.
     * JWT authentication for user endpoints is handled by [JwtAuthenticationFilter].
     *
     * @param request The incoming HTTP request.
     * @return `true` if the request should NOT be filtered (i.e., not a relay endpoint, or is `/incoming`).
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        return !request.requestURI.startsWith("/api/negotiate/")
            || request.requestURI == "/api/negotiate/incoming"
    }

    /**
     * Validates HMAC authentication for incoming relay requests.
     *
     * On validation failure, writes an error response directly and does NOT call
     * [filterChain.doFilter] — the request is rejected before reaching the controller.
     *
     * On success, stores the session ID as a request attribute so downstream controllers
     * can access it without re-parsing headers.
     *
     * @param request The incoming HTTP request.
     * @param response The HTTP response (used for error writing on failure).
     * @param filterChain The filter chain to continue if validation succeeds.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val cachedBody = request.inputStream.readAllBytes()
        val wrappedRequest = CachedBodyHttpServletRequest(request, cachedBody)

        // Extract required headers
        val sessionId = wrappedRequest.getHeader(HEADER_SESSION_ID)
        val timestamp = wrappedRequest.getHeader(HEADER_TIMESTAMP)
        val signature = wrappedRequest.getHeader(HEADER_SIGNATURE)
        val instanceId = wrappedRequest.getHeader(HEADER_INSTANCE_ID)

        if (sessionId == null || timestamp == null || signature == null) {
            writeError(response, 401, "invalid_signature", "Missing required HMAC headers")
            return
        }

        // Look up the session to derive the HMAC key
        val sessionUuid = try {
            UUID.fromString(sessionId)
        } catch (e: IllegalArgumentException) {
            writeError(response, 400, "session_not_found", "Invalid session ID format")
            return
        }

        val session = sessionRepository.findById(sessionUuid).orElse(null)
        if (session == null) {
            writeError(response, 404, "session_not_found", "Negotiation session not found")
            return
        }

        // Derive the session key from the invite token or retrieve from Redis cache.
        // Before join: invite token is available, derive key directly.
        // After join: invite token is consumed (null), look up cached key in Redis.
        // NegotiationService.joinSession() caches the derived key at join time.
        val sessionKey: ByteArray
        val inviteToken = session.inviteToken
        if (inviteToken != null) {
            // Pre-join: derive key from invite token (first relay or join-time call)
            sessionKey = hmacService.deriveSessionKey(inviteToken)
        } else {
            // Post-join: retrieve cached key from Redis
            val cachedKey = redisTemplate.opsForValue()
                .get("$SESSION_KEY_PREFIX$sessionId")
            if (cachedKey == null) {
                writeError(
                    response,
                    401,
                    "invalid_signature",
                    "Session key not cached — session may have expired",
                )
                return
            }
            sessionKey = java.util.Base64.getDecoder().decode(cachedKey)
        }

        val body = cachedBody.toString(StandardCharsets.UTF_8)

        // Validate timestamp + signature
        if (!hmacService.validate(sessionKey, sessionId, timestamp, body, signature)) {
            log.warn(
                "HMAC validation failed: session={}, instanceId={}, timestamp={}",
                sessionId,
                instanceId,
                timestamp,
            )
            writeError(response, 401, "invalid_signature", "HMAC signature validation failed")
            return
        }

        // Replay prevention: hash the signature and check Redis nonce
        val nonceHash = hashSignature(signature)
        val nonceKey = "$NONCE_PREFIX$nonceHash"
        val isNew = redisTemplate.opsForValue()
            .setIfAbsent(nonceKey, "1", NONCE_TTL)

        if (isNew != true) {
            log.warn("Replay detected: session={}, nonceHash={}", sessionId, nonceHash)
            writeError(response, 401, "replay_detected", "Duplicate relay request detected")
            return
        }

        // Validation passed — store session ID as request attribute for downstream use
        wrappedRequest.setAttribute("relaySessionId", sessionUuid)
        wrappedRequest.setAttribute("relayInstanceId", instanceId)

        log.info(
            "Relay request authenticated: session={}, from={}",
            sessionId,
            instanceId,
        )

        filterChain.doFilter(wrappedRequest, response)
    }

    /**
     * Writes a JSON error response for relay authentication failures.
     *
     * Uses the [RelayErrorResponse] format defined in Doc 04 (different from the
     * user-facing [com.fieldiq.api.dto.ErrorResponse] format).
     *
     * @param response The HTTP response to write to.
     * @param status The HTTP status code (401, 404, etc.).
     * @param error Machine-readable error code.
     * @param message Human-readable error description.
     */
    private fun writeError(
        response: HttpServletResponse,
        status: Int,
        error: String,
        message: String,
    ) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(
            response.outputStream,
            RelayErrorResponse(error, message),
        )
    }

    /**
     * Hashes an HMAC signature for use as a Redis nonce key.
     *
     * Stores the hash (not the raw signature) to minimize Redis memory usage and
     * avoid exposing signature material in Redis key names.
     *
     * @param signature The HMAC-SHA256 hex signature from the request.
     * @return The SHA-256 hash of the signature as a hex string.
     */
    private fun hashSignature(signature: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Request wrapper that replays a previously buffered HTTP body.
 *
 * [HmacAuthenticationFilter] reads the relay payload before Spring MVC performs
 * `@RequestBody` binding. This wrapper makes the same bytes available again to
 * downstream filters and controllers.
 *
 * @property cachedBody Raw request body bytes captured before authentication.
 */
private class CachedBodyHttpServletRequest(
    request: HttpServletRequest,
    private val cachedBody: ByteArray,
) : HttpServletRequestWrapper(request) {

    /**
     * Returns a fresh input stream over the cached request body.
     *
     * @return [ServletInputStream] backed by the original relay JSON bytes.
     */
    override fun getInputStream(): ServletInputStream {
        val buffer = ByteArrayInputStream(cachedBody)
        return object : ServletInputStream() {
            override fun read(): Int = buffer.read()

            override fun isFinished(): Boolean = buffer.available() == 0

            override fun isReady(): Boolean = true

            override fun setReadListener(readListener: ReadListener?) = Unit
        }
    }

    /**
     * Returns a UTF-8 reader over the cached request body.
     *
     * @return [BufferedReader] for the original relay JSON.
     */
    override fun getReader(): BufferedReader {
        return BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
    }
}
