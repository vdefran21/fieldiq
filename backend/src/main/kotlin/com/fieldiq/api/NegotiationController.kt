package com.fieldiq.api

import com.fieldiq.api.dto.ConfirmNegotiationRequest
import com.fieldiq.api.dto.InitiateNegotiationRequest
import com.fieldiq.api.dto.JoinSessionRequest
import com.fieldiq.api.dto.NegotiationProposalDto
import com.fieldiq.api.dto.NegotiationSocketTokenResponse
import com.fieldiq.api.dto.NegotiationSessionDto
import com.fieldiq.api.dto.RespondToProposalRequest
import com.fieldiq.security.authenticatedUserId
import com.fieldiq.service.NegotiationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST controller for the cross-team scheduling negotiation protocol.
 *
 * Provides the user-facing endpoints for managing negotiation sessions —
 * initiating, joining, proposing, responding, confirming, and cancelling.
 * All endpoints require JWT authentication (secured by Spring Security's
 * `anyRequest().authenticated()` rule in [com.fieldiq.security.SecurityConfig]).
 *
 * Cross-instance relay endpoints (HMAC-authenticated) are handled by
 * [NegotiationRelayController] at `/api/negotiate/` paths.
 *
 * @property negotiationService Business logic for the negotiation protocol.
 * @see NegotiationService for the underlying state machine and orchestration.
 * @see NegotiationRelayController for cross-instance relay endpoints.
 * @see com.fieldiq.service.TeamAccessGuard for multi-tenancy enforcement.
 */
@RestController
@RequestMapping("/negotiations")
class NegotiationController(
    private val negotiationService: NegotiationService,
) {

    /**
     * Initiates a new cross-team scheduling negotiation.
     *
     * Creates a session with a single-use invite token that can be shared
     * with the opposing team's manager. The authenticated user must be a
     * manager of the specified team.
     *
     * @param request The negotiation parameters (team, date range, duration).
     * @return 201 Created with the [NegotiationSessionDto] including invite token.
     */
    @PostMapping
    fun initiateNegotiation(
        @Valid @RequestBody request: InitiateNegotiationRequest,
    ): ResponseEntity<NegotiationSessionDto> {
        val userId = authenticatedUserId()
        val session = negotiationService.initiateNegotiation(userId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(session)
    }

    /**
     * Retrieves a negotiation session with its proposal history.
     *
     * The authenticated user must be a manager of either the initiator or
     * responder team.
     *
     * @param sessionId UUID of the negotiation session.
     * @return 200 OK with the [NegotiationSessionDto] including embedded proposals.
     */
    @GetMapping("/{sessionId}")
    fun getSession(@PathVariable sessionId: UUID): ResponseEntity<NegotiationSessionDto> {
        val userId = authenticatedUserId()
        val session = negotiationService.getSession(sessionId, userId)
        return ResponseEntity.ok(session)
    }

    /**
     * Exchanges the caller's bearer-authenticated REST session for a short-lived negotiation
     * WebSocket token scoped to this session only.
     *
     * @param sessionId UUID of the negotiation session.
     * @return 200 OK with the signed socket token and its lifetime.
     */
    @PostMapping("/{sessionId}/socket-token")
    fun socketToken(@PathVariable sessionId: UUID): ResponseEntity<NegotiationSocketTokenResponse> {
        val userId = authenticatedUserId()
        val token = negotiationService.createSocketToken(sessionId, userId)
        return ResponseEntity.ok(token)
    }

    /**
     * Joins an existing negotiation using the single-use invite token.
     *
     * Validates and consumes the invite token, derives the HMAC session key,
     * and transitions the session to "proposing" status.
     *
     * @param sessionId UUID of the negotiation session.
     * @param request The join parameters (invite token, responder team and instance).
     * @return 200 OK with the updated [NegotiationSessionDto].
     */
    @PostMapping("/{sessionId}/join")
    fun joinSession(
        @PathVariable sessionId: UUID,
        @Valid @RequestBody request: JoinSessionRequest,
    ): ResponseEntity<NegotiationSessionDto> {
        val userId = authenticatedUserId()
        val session = negotiationService.joinSession(sessionId, userId, request)
        return ResponseEntity.ok(session)
    }

    /**
     * Sends a slot proposal to the counterpart instance.
     *
     * Computes available windows for the acting team and relays them to the
     * remote instance. The authenticated user must be a manager of their side's team.
     *
     * @param sessionId UUID of the negotiation session.
     * @return 200 OK with the created [NegotiationProposalDto].
     */
    @PostMapping("/{sessionId}/propose")
    fun propose(@PathVariable sessionId: UUID): ResponseEntity<NegotiationProposalDto> {
        val userId = authenticatedUserId()
        val proposal = negotiationService.generateAndSendProposal(sessionId, userId)
        return ResponseEntity.ok(proposal)
    }

    /**
     * Responds to the counterpart's most recent proposal.
     *
     * Accepts, rejects, or counters the proposal. If countered, the response
     * includes alternative time slots.
     *
     * @param sessionId UUID of the negotiation session.
     * @param request The response details (status, optional counter-slots).
     * @return 200 OK with the updated [NegotiationSessionDto].
     */
    @PostMapping("/{sessionId}/respond")
    fun respond(
        @PathVariable sessionId: UUID,
        @Valid @RequestBody request: RespondToProposalRequest,
    ): ResponseEntity<NegotiationSessionDto> {
        val userId = authenticatedUserId()
        val session = negotiationService.respondToProposal(sessionId, userId, request)
        return ResponseEntity.ok(session)
    }

    /**
     * Confirms the agreed-upon time slot.
     *
     * Both managers must confirm independently. The session transitions to "confirmed"
     * and a game event is created only after both sides have confirmed. Returns the
     * session DTO reflecting current confirmation state (initiatorConfirmed/responderConfirmed).
     *
     * @param sessionId UUID of the negotiation session.
     * @param request The confirmation details (time slot).
     * @return 200 OK with the updated [NegotiationSessionDto].
     */
    @PostMapping("/{sessionId}/confirm")
    fun confirm(
        @PathVariable sessionId: UUID,
        @Valid @RequestBody request: ConfirmNegotiationRequest,
    ): ResponseEntity<NegotiationSessionDto> {
        val userId = authenticatedUserId()
        val session = negotiationService.confirmAgreement(sessionId, userId, request)
        return ResponseEntity.ok(session)
    }

    /**
     * Cancels a negotiation session.
     *
     * Transitions the session to "cancelled" and relays the cancellation
     * to the remote instance.
     *
     * @param sessionId UUID of the negotiation session.
     * @return 200 OK with the updated [NegotiationSessionDto].
     */
    @PostMapping("/{sessionId}/cancel")
    fun cancel(@PathVariable sessionId: UUID): ResponseEntity<NegotiationSessionDto> {
        val userId = authenticatedUserId()
        val session = negotiationService.cancelSession(sessionId, userId)
        return ResponseEntity.ok(session)
    }
}
