package com.fieldiq.api

import com.fieldiq.api.dto.IncomingNegotiationRequest
import com.fieldiq.api.dto.RelayRequest
import com.fieldiq.api.dto.RelayResponse
import com.fieldiq.service.NegotiationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST controller for cross-instance negotiation relay endpoints.
 *
 * These endpoints are called by remote FieldIQ instances (not by the mobile app)
 * to exchange negotiation proposals and responses. They are HMAC-authenticated
 * by [com.fieldiq.security.HmacAuthenticationFilter] — NOT JWT-authenticated.
 *
 * The security model:
 * - Path `/api/negotiate/` is `permitAll()` in [com.fieldiq.security.SecurityConfig]
 * - [com.fieldiq.security.HmacAuthenticationFilter] validates HMAC signatures on
 *   every request to this path before it reaches the controller
 * - The filter stores `relaySessionId` and `relayInstanceId` as request attributes
 *
 * @property negotiationService Business logic for processing incoming relay requests.
 * @see com.fieldiq.security.HmacAuthenticationFilter for the HMAC validation filter.
 * @see com.fieldiq.service.CrossInstanceRelayClient for the outbound relay client.
 * @see NegotiationController for user-facing (JWT-authenticated) endpoints.
 */
@RestController
@RequestMapping("/api/negotiate")
class NegotiationRelayController(
    private val negotiationService: NegotiationService,
) {

    /**
     * Receives a cross-instance incoming negotiation request to bootstrap a shadow session.
     *
     * Called by Instance A during the join handshake to create a local session copy on
     * Instance B. No HMAC authentication — the invite token in the request body is the
     * bearer credential. This endpoint is excluded from [com.fieldiq.security.HmacAuthenticationFilter].
     *
     * @param httpRequest The HTTP request.
     * @param request The incoming negotiation details including invite token for key derivation.
     * @return 200 OK with [RelayResponse] containing the shadow session's state.
     */
    @PostMapping("/incoming")
    fun receiveIncoming(
        httpRequest: HttpServletRequest,
        @Valid @RequestBody request: IncomingNegotiationRequest,
    ): ResponseEntity<RelayResponse> {
        val session = negotiationService.createShadowSession(request)
        val response = RelayResponse(
            sessionStatus = session.status,
            currentRound = session.currentRound,
        )
        return ResponseEntity.ok(response)
    }

    /**
     * Receives a relayed proposal, response, confirmation, or cancellation
     * from a remote FieldIQ instance.
     *
     * This is the main relay endpoint used during active negotiations. The
     * [com.fieldiq.security.HmacAuthenticationFilter] has already validated
     * the HMAC signature and stored the session ID as `relaySessionId`.
     *
     * @param httpRequest The HTTP request (contains HMAC-validated session ID attribute).
     * @param sessionId UUID of the negotiation session (from URL path).
     * @param relay The relay request payload (action, slots, response status, etc.).
     * @return 200 OK with [RelayResponse] containing the session's updated state.
     */
    @PostMapping("/{sessionId}/relay")
    fun receiveRelay(
        httpRequest: HttpServletRequest,
        @PathVariable sessionId: UUID,
        @RequestBody relay: RelayRequest,
    ): ResponseEntity<RelayResponse> {
        val response = negotiationService.processIncomingRelay(sessionId, relay)
        return ResponseEntity.ok(response)
    }
}
