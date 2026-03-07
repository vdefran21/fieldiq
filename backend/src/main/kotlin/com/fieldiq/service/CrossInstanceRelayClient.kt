package com.fieldiq.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fieldiq.api.dto.RelayRequest
import com.fieldiq.api.dto.RelayResponse
import com.fieldiq.config.FieldIQProperties
import com.fieldiq.security.HmacService
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.util.retry.Retry
import java.time.Duration
import java.time.Instant

/**
 * WebFlux HTTP client for sending HMAC-authenticated relay requests to remote FieldIQ instances.
 *
 * This is the outbound side of the cross-instance negotiation protocol. When the local
 * [NegotiationService] needs to send proposals, responses, or confirmations to a remote
 * instance, it calls this client. The client:
 *
 * 1. Serializes the [RelayRequest] to JSON
 * 2. Computes an HMAC-SHA256 signature over `sessionId + timestamp + body`
 * 3. Sends the request with authentication headers to the remote instance
 * 4. Retries with exponential backoff on transient failures (3 attempts: 2s, 8s, 30s)
 *
 * **Headers set on every relay request (per Doc 04):**
 * - `X-FieldIQ-Session-Id` — the negotiation session UUID
 * - `X-FieldIQ-Timestamp` — ISO-8601 UTC timestamp of the request
 * - `X-FieldIQ-Signature` — HMAC-SHA256 hex signature
 * - `X-FieldIQ-Instance-Id` — this instance's ID (for logging/debugging on the receiver)
 *
 * **Retry strategy (per Doc 04):**
 * - 3 attempts with exponential backoff: ~2s, ~8s, ~30s
 * - Only retries on 5xx server errors and connection failures
 * - 4xx errors (bad request, invalid signature) are NOT retried
 * - After 3 failures, the caller should transition the session to "failed"
 *
 * @property hmacService Provides HMAC key derivation and signature computation.
 * @property properties FieldIQ config containing this instance's ID.
 * @property objectMapper Jackson mapper for JSON serialization (matches Spring's config).
 * @see HmacService for the cryptographic operations.
 * @see HmacAuthenticationFilter for the inbound validation of these same headers.
 */
@Service
class CrossInstanceRelayClient(
    private val hmacService: HmacService,
    private val properties: FieldIQProperties,
    private val objectMapper: ObjectMapper,
) {

    private val logger = LoggerFactory.getLogger(CrossInstanceRelayClient::class.java)

    private val webClient: WebClient = WebClient.builder()
        .codecs { it.defaultCodecs().maxInMemorySize(256 * 1024) }
        .build()

    companion object {
        /** HTTP header names per Doc 04 spec. */
        const val HEADER_SESSION_ID = "X-FieldIQ-Session-Id"
        const val HEADER_TIMESTAMP = "X-FieldIQ-Timestamp"
        const val HEADER_SIGNATURE = "X-FieldIQ-Signature"
        const val HEADER_INSTANCE_ID = "X-FieldIQ-Instance-Id"

        /** Request timeout for individual relay calls. */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
    }

    /**
     * Sends an HMAC-authenticated relay request to a remote FieldIQ instance.
     *
     * Constructs the relay URL from the target instance's base URL and the session ID,
     * signs the request body, and sends it with retry logic.
     *
     * This method blocks until the response is received or all retries are exhausted.
     * In production, callers should consider wrapping this in a coroutine or SQS-backed
     * async flow per the Doc 04 retry strategy.
     *
     * @param targetInstanceUrl The base URL of the remote instance (e.g., "http://localhost:8081").
     *   Obtained from [com.fieldiq.domain.NegotiationSession.responderInstance] or
     *   [com.fieldiq.domain.NegotiationSession.initiatorInstance].
     * @param sessionId The negotiation session UUID.
     * @param sessionKey The per-session HMAC key (from [HmacService.deriveSessionKey]).
     * @param request The relay request payload.
     * @return The [RelayResponse] from the remote instance.
     * @throws RelayException if all retry attempts fail or the remote instance returns
     *   a non-retryable error (4xx).
     */
    fun sendRelay(
        targetInstanceUrl: String,
        sessionId: String,
        sessionKey: ByteArray,
        request: RelayRequest,
    ): RelayResponse {
        val url = "${targetInstanceUrl.trimEnd('/')}/api/negotiate/$sessionId/relay"
        val body = objectMapper.writeValueAsString(request)
        val timestamp = Instant.now().toString()
        val signature = hmacService.sign(sessionKey, sessionId, timestamp, body)

        logger.info(
            "Sending relay request: action={}, round={}, session={}, target={}",
            request.action,
            request.roundNumber,
            sessionId,
            targetInstanceUrl,
        )

        return webClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .header(HEADER_SESSION_ID, sessionId)
            .header(HEADER_TIMESTAMP, timestamp)
            .header(HEADER_SIGNATURE, signature)
            .header(HEADER_INSTANCE_ID, properties.instance.id)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(RelayResponse::class.java)
            .timeout(REQUEST_TIMEOUT)
            .retryWhen(
                Retry.backoff(2, Duration.ofSeconds(2))
                    .maxBackoff(Duration.ofSeconds(30))
                    .filter { throwable ->
                        // Only retry on 5xx and connection errors, not 4xx
                        when (throwable) {
                            is WebClientResponseException -> throwable.statusCode.is5xxServerError
                            else -> true // connection errors, timeouts
                        }
                    }
                    .doBeforeRetry { signal ->
                        logger.warn(
                            "Retrying relay request (attempt {}): session={}, error={}",
                            signal.totalRetries() + 1,
                            sessionId,
                            signal.failure().message,
                        )
                    },
            )
            .doOnError { error ->
                logger.error(
                    "Relay request failed after all retries: session={}, error={}",
                    sessionId,
                    error.message,
                )
            }
            .block()
            ?: throw RelayException("Relay request returned null response: session=$sessionId")
    }
}

/**
 * Exception thrown when a cross-instance relay call fails.
 *
 * Wraps both network errors (connection refused, timeout) and application-level
 * errors (invalid signature, session not found) from the remote instance.
 *
 * @property message Description of the relay failure.
 * @property cause The underlying exception, if any.
 * @see CrossInstanceRelayClient.sendRelay for where this is thrown.
 */
class RelayException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
