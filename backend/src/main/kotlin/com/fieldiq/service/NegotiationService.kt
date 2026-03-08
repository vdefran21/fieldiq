package com.fieldiq.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fieldiq.api.dto.ConfirmNegotiationRequest
import com.fieldiq.api.dto.IncomingNegotiationRequest
import com.fieldiq.api.dto.InitiateNegotiationRequest
import com.fieldiq.api.dto.JoinSessionRequest
import com.fieldiq.api.dto.NegotiationProposalDto
import com.fieldiq.api.dto.NegotiationSessionDto
import com.fieldiq.api.dto.RelayRequest
import com.fieldiq.api.dto.RelayResponse
import com.fieldiq.api.dto.RelaySlot
import com.fieldiq.api.dto.RespondToProposalRequest
import com.fieldiq.api.dto.TimeSlotDto
import com.fieldiq.config.FieldIQProperties
import com.fieldiq.domain.Event
import com.fieldiq.domain.NegotiationEvent
import com.fieldiq.domain.NegotiationProposal
import com.fieldiq.domain.NegotiationSession
import com.fieldiq.repository.EventRepository
import com.fieldiq.repository.NegotiationEventRepository
import com.fieldiq.repository.NegotiationProposalRepository
import com.fieldiq.repository.NegotiationSessionRepository
import com.fieldiq.security.HmacAuthenticationFilter
import com.fieldiq.security.HmacService
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * Core orchestration service for the cross-team scheduling negotiation protocol — FieldIQ's IP.
 *
 * Manages the full negotiation lifecycle: session creation with invite tokens, responder
 * join handshake with HMAC key derivation, slot proposal generation and relay, match
 * detection via [SchedulingService.intersectWindows], and confirmation with event creation.
 *
 * **State machine enforcement:** Every state-mutating method validates the transition via
 * [requireTransition] before modifying the session. Invalid transitions throw
 * [InvalidStateTransitionException] (mapped to HTTP 409 by [com.fieldiq.api.GlobalExceptionHandler]).
 *
 * **Cross-instance communication:** Proposals and responses are relayed to the remote
 * FieldIQ instance via [CrossInstanceRelayClient]. The derived HMAC session key is cached
 * in Redis at join time for post-join relay authentication.
 *
 * **Transaction boundaries:** All public methods are `@Transactional`. If a relay call
 * fails (throws [RelayException]), the transaction rolls back and local state is not corrupted.
 *
 * **Concurrency note:** No optimistic locking (`@Version`) is used in Sprint 4. Under
 * concurrent access (e.g., both managers calling confirm simultaneously), there is a
 * race condition. This is acceptable for the demo; optimistic locking is a Phase 2 hardening item.
 *
 * @property sessionRepo Repository for negotiation session CRUD.
 * @property proposalRepo Repository for proposal storage and idempotency checks.
 * @property negotiationEventRepo Repository for the append-only audit log.
 * @property eventRepository Repository for creating game events on confirmation.
 * @property schedulingService Deterministic window computation (no LLM).
 * @property relayClient HTTP client for cross-instance relay calls with HMAC signatures.
 * @property teamAccessGuard Multi-tenancy enforcement — ensures callers are team managers.
 * @property hmacService HMAC key derivation and signature computation.
 * @property properties Application configuration (instance ID, base URL, secrets).
 * @property redisTemplate Redis client for caching derived session keys.
 * @property objectMapper Jackson mapper for JSONB slot serialization/deserialization.
 * @property namedParameterJdbcTemplate JDBC helper used for shadow-session bootstrap inserts with remote UUIDs.
 * @see com.fieldiq.api.NegotiationController for the REST endpoints that delegate to this service.
 * @see com.fieldiq.api.NegotiationRelayController for the HMAC-authenticated relay endpoints.
 */
@Service
class NegotiationService(
    private val sessionRepo: NegotiationSessionRepository,
    private val proposalRepo: NegotiationProposalRepository,
    private val negotiationEventRepo: NegotiationEventRepository,
    private val eventRepository: EventRepository,
    private val schedulingService: SchedulingService,
    private val relayClient: CrossInstanceRelayClient,
    private val teamAccessGuard: TeamAccessGuard,
    private val hmacService: HmacService,
    private val properties: FieldIQProperties,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val namedParameterJdbcTemplate: NamedParameterJdbcTemplate,
) {

    private val log = LoggerFactory.getLogger(NegotiationService::class.java)

    companion object {
        /** Number of top windows to include in a proposal relay. */
        const val PROPOSAL_SLOT_COUNT = 5

        /** Invite token validity period. */
        private val INVITE_TOKEN_TTL = 48L // hours

        /** Number of random bytes for invite token generation. */
        private val INVITE_TOKEN_BYTES = 36

        /** Terminal states that do not allow any further transitions. */
        private val TERMINAL_STATES = setOf("confirmed", "failed", "cancelled")

        /** Allowed state transitions for the negotiation state machine. */
        private val ALLOWED_TRANSITIONS = mapOf(
            "pending_response" to setOf("proposing", "cancelled", "failed"),
            "proposing" to setOf("pending_approval", "failed", "cancelled"),
            "pending_approval" to setOf("confirmed", "proposing", "cancelled"),
        )
    }

    // ========================================================================
    // Session Lifecycle (Phase B)
    // ========================================================================

    /**
     * Creates a new negotiation session with a single-use invite token.
     *
     * The authenticated user must be a manager of [request.teamId]. Generates a
     * cryptographically random invite token (48h TTL) that can be shared with the
     * opposing team's manager via SMS/email deep link.
     *
     * **Database impact:** Inserts one row into `negotiation_sessions` and one audit
     * event into `negotiation_events`.
     *
     * @param managerId UUID of the authenticated user (must be a team manager).
     * @param request The negotiation parameters (team, date range, duration).
     * @return DTO of the created session, including the invite token.
     * @throws org.springframework.security.access.AccessDeniedException If the user
     *   is not a manager of the specified team.
     */
    @Transactional
    fun initiateNegotiation(
        managerId: UUID,
        request: InitiateNegotiationRequest,
    ): NegotiationSessionDto {
        teamAccessGuard.requireManager(managerId, request.teamId)

        val inviteToken = generateInviteToken()
        val expiresAt = Instant.now().plus(INVITE_TOKEN_TTL, ChronoUnit.HOURS)

        val session = sessionRepo.save(
            NegotiationSession(
                initiatorTeamId = request.teamId,
                initiatorInstance = properties.instance.baseUrl,
                initiatorManager = managerId,
                status = "pending_response",
                requestedDateRangeStart = LocalDate.parse(request.dateRangeStart),
                requestedDateRangeEnd = LocalDate.parse(request.dateRangeEnd),
                requestedDurationMinutes = request.durationMinutes,
                inviteToken = inviteToken,
                expiresAt = expiresAt,
            ),
        )

        logEvent(session.id, "session_created", "initiator")
        log.info("Negotiation session created: id={}, team={}", session.id, request.teamId)

        return NegotiationSessionDto.from(session)
    }

    /**
     * Retrieves a negotiation session with its proposal history.
     *
     * The authenticated user must be a manager of either the initiator or responder
     * team. Returns the full session state including all proposals.
     *
     * @param sessionId UUID of the negotiation session.
     * @param userId UUID of the authenticated user.
     * @return DTO of the session with embedded proposals.
     * @throws EntityNotFoundException If the session does not exist.
     * @throws org.springframework.security.access.AccessDeniedException If the user
     *   is not a manager of either team.
     */
    @Transactional(readOnly = true)
    fun getSession(sessionId: UUID, userId: UUID): NegotiationSessionDto {
        val session = findSessionOrThrow(sessionId)
        requireSessionAccess(session, userId)

        val proposals = proposalRepo.findBySessionId(sessionId)
        val proposalDtos = proposals.map { toProposalDto(it) }

        return NegotiationSessionDto.from(session, proposalDtos)
    }

    /**
     * Joins an existing negotiation session using the single-use invite token.
     *
     * Validates and consumes the invite token, derives the HMAC session key,
     * caches the key in Redis for future relay authentication, and transitions
     * the session from "pending_response" to "proposing".
     *
     * **Database impact:** Updates `negotiation_sessions` (nullifies invite token,
     * sets responder fields, transitions status). Inserts one audit event.
     *
     * **Redis impact:** Caches the derived session key at
     * `fieldiq:sessionkey:<sessionId>` with 72h TTL.
     *
     * @param sessionId UUID of the negotiation session to join.
     * @param managerId UUID of the authenticated user. For same-instance negotiations this
     *   user must be a manager of [JoinSessionRequest.responderTeamId]. For cross-instance
     *   negotiations, the responder team lives on the remote instance, so local manager
     *   authorization is deferred to that instance's own team-scoped operations.
     * @param request The join parameters (invite token, responder team, instance URL).
     * @return DTO of the updated session in "proposing" status.
     * @throws IllegalArgumentException If the invite token is invalid or does not match.
     * @throws InvalidStateTransitionException If the session is not in "pending_response".
     * @throws EntityNotFoundException If the session does not exist.
     * @throws org.springframework.security.access.AccessDeniedException If the user is not
     *   a manager of the responder team for a same-instance negotiation.
     */
    @Transactional
    fun joinSession(sessionId: UUID, managerId: UUID, request: JoinSessionRequest): NegotiationSessionDto {
        val session = findSessionOrThrow(sessionId)

        // Validate invite token
        if (session.inviteToken == null) {
            throw IllegalArgumentException("Invite token has already been consumed")
        }
        if (session.inviteToken != request.inviteToken) {
            throw IllegalArgumentException("Invalid invite token")
        }

        // Check expiry
        if (session.expiresAt != null && Instant.now().isAfter(session.expiresAt)) {
            val failed = session.copy(status = "failed", updatedAt = Instant.now())
            sessionRepo.save(failed)
            logEvent(session.id, "session_failed", "system", """{"reason":"invite_expired"}""")
            throw InvalidStateTransitionException("Negotiation session has expired")
        }

        requireTransition(session.status, "proposing")

        // Same-instance joins can authorize the responder team locally. Cross-instance
        // joins carry a responderTeamId owned by the remote instance, so local
        // membership checks here would incorrectly reject valid remote teams.
        if (isLocalInstance(request.responderInstance)) {
            teamAccessGuard.requireManager(managerId, request.responderTeamId)
        }

        // Build incoming request for shadow session bootstrap on Instance B.
        // This must happen BEFORE consuming the invite token — if it fails, the
        // join fails and the token stays valid for retry.
        val incomingRequest = IncomingNegotiationRequest(
            sessionId = sessionId,
            inviteToken = session.inviteToken,
            initiatorTeamId = session.initiatorTeamId,
            initiatorInstance = session.initiatorInstance,
            responderTeamId = request.responderTeamId,
            responderInstance = request.responderInstance,
            requestedDateRangeStart = session.requestedDateRangeStart?.toString(),
            requestedDateRangeEnd = session.requestedDateRangeEnd?.toString(),
            requestedDurationMinutes = session.requestedDurationMinutes,
            maxRounds = session.maxRounds,
            expiresAt = session.expiresAt?.toString(),
        )

        // Bootstrap shadow session on the responder's instance
        val targetInstance = request.responderInstance
        relayClient.sendIncoming(targetInstance, incomingRequest)

        // Shadow session created — now safe to consume the token locally
        val sessionKey = hmacService.deriveSessionKey(session.inviteToken)
        val keyHash = hashBytes(sessionKey)
        cacheSessionKey(sessionId, sessionKey)

        val updated = session.copy(
            status = "proposing",
            inviteToken = null,
            sessionKeyHash = keyHash,
            responderTeamId = request.responderTeamId,
            responderInstance = request.responderInstance,
            updatedAt = Instant.now(),
        )
        val saved = sessionRepo.save(updated)

        logEvent(session.id, "responder_joined", "responder")
        log.info(
            "Responder joined negotiation: session={}, responderTeam={}",
            sessionId,
            request.responderTeamId,
        )

        return NegotiationSessionDto.from(saved)
    }

    /**
     * Creates a shadow session on this instance, bootstrapped from a remote instance's
     * `/api/negotiate/incoming` call during the join handshake.
     *
     * This method is called on Instance B when Manager B joins a session that was
     * created on Instance A. The shadow session enables Instance B to:
     * - Validate HMAC signatures on subsequent `/relay` calls (the filter needs a local session)
     * - Determine the local actor ("initiator" or "responder") for relay processing
     * - Create game events on the local team when the negotiation is confirmed
     *
     * **Idempotency:** If a session with the same UUID already exists locally, the existing
     * session is returned without modification.
     *
     * **Database impact:** Inserts one row into `negotiation_sessions` and one audit event.
     *
     * **Redis impact:** Caches the derived session key at `fieldiq:sessionkey:<sessionId>`.
     *
     * @param request The incoming negotiation details from Instance A.
     * @return DTO of the created (or existing) shadow session.
     */
    @Transactional
    fun createShadowSession(request: IncomingNegotiationRequest): NegotiationSessionDto {
        // Idempotency: if session already exists locally, return it
        val existing = sessionRepo.findById(request.sessionId).orElse(null)
        if (existing != null) {
            log.info("Shadow session already exists: sessionId={}", request.sessionId)
            return NegotiationSessionDto.from(existing)
        }

        // Derive and cache session key from invite token
        val sessionKey = hmacService.deriveSessionKey(request.inviteToken)
        val keyHash = hashBytes(sessionKey)
        cacheSessionKey(request.sessionId, sessionKey)

        val shadowSession = NegotiationSession(
            id = request.sessionId,
            initiatorTeamId = request.initiatorTeamId,
            initiatorInstance = request.initiatorInstance,
            responderTeamId = request.responderTeamId,
            responderInstance = request.responderInstance,
            status = "proposing",
            requestedDateRangeStart = request.requestedDateRangeStart?.let { LocalDate.parse(it) },
            requestedDateRangeEnd = request.requestedDateRangeEnd?.let { LocalDate.parse(it) },
            requestedDurationMinutes = request.requestedDurationMinutes,
            maxRounds = request.maxRounds,
            expiresAt = request.expiresAt?.let { Instant.parse(it) },
            sessionKeyHash = keyHash,
        )

        // Insert shadow sessions via JDBC because they must preserve the remote instance's UUID.
        // JPA `save(...)`/`persist(...)` relies on entity-state detection that treats these
        // pre-populated IDs inconsistently, which caused responder-side bootstrap failures.
        namedParameterJdbcTemplate.update(
            """
            INSERT INTO negotiation_sessions (
                id,
                initiator_team_id,
                initiator_instance,
                initiator_manager,
                responder_team_id,
                responder_instance,
                responder_external_id,
                status,
                requested_date_range_start,
                requested_date_range_end,
                requested_duration_minutes,
                agreed_starts_at,
                agreed_ends_at,
                agreed_location,
                invite_token,
                session_key_hash,
                max_rounds,
                current_round,
                initiator_confirmed,
                responder_confirmed,
                expires_at,
                created_at,
                updated_at
            ) VALUES (
                :id,
                :initiatorTeamId,
                :initiatorInstance,
                :initiatorManager,
                :responderTeamId,
                :responderInstance,
                :responderExternalId,
                :status,
                :requestedDateRangeStart,
                :requestedDateRangeEnd,
                :requestedDurationMinutes,
                :agreedStartsAt,
                :agreedEndsAt,
                :agreedLocation,
                :inviteToken,
                :sessionKeyHash,
                :maxRounds,
                :currentRound,
                :initiatorConfirmed,
                :responderConfirmed,
                :expiresAt,
                :createdAt,
                :updatedAt
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", shadowSession.id)
                .addValue("initiatorTeamId", shadowSession.initiatorTeamId)
                .addValue("initiatorInstance", shadowSession.initiatorInstance)
                .addValue("initiatorManager", shadowSession.initiatorManager)
                .addValue("responderTeamId", shadowSession.responderTeamId)
                .addValue("responderInstance", shadowSession.responderInstance)
                .addValue("responderExternalId", shadowSession.responderExternalId)
                .addValue("status", shadowSession.status)
                .addValue("requestedDateRangeStart", shadowSession.requestedDateRangeStart?.let(Date::valueOf))
                .addValue("requestedDateRangeEnd", shadowSession.requestedDateRangeEnd?.let(Date::valueOf))
                .addValue("requestedDurationMinutes", shadowSession.requestedDurationMinutes)
                .addValue("agreedStartsAt", shadowSession.agreedStartsAt?.let(Timestamp::from))
                .addValue("agreedEndsAt", shadowSession.agreedEndsAt?.let(Timestamp::from))
                .addValue("agreedLocation", shadowSession.agreedLocation)
                .addValue("inviteToken", shadowSession.inviteToken)
                .addValue("sessionKeyHash", shadowSession.sessionKeyHash)
                .addValue("maxRounds", shadowSession.maxRounds)
                .addValue("currentRound", shadowSession.currentRound)
                .addValue("initiatorConfirmed", shadowSession.initiatorConfirmed)
                .addValue("responderConfirmed", shadowSession.responderConfirmed)
                .addValue("expiresAt", shadowSession.expiresAt?.let(Timestamp::from))
                .addValue("createdAt", Timestamp.from(shadowSession.createdAt))
                .addValue("updatedAt", Timestamp.from(shadowSession.updatedAt)),
        )

        logEvent(request.sessionId, "shadow_session_created", "system")
        log.info(
            "Shadow session created: sessionId={}, responderTeam={}",
            request.sessionId,
            request.responderTeamId,
        )

        return NegotiationSessionDto.from(shadowSession)
    }

    // ========================================================================
    // Proposal Exchange (Phase C)
    // ========================================================================

    /**
     * Generates and sends a slot proposal to the counterpart instance.
     *
     * Computes available windows for the acting team via [SchedulingService],
     * stores the proposal in the database, and relays it to the remote instance
     * via [CrossInstanceRelayClient].
     *
     * **Database impact:** Inserts one row into `negotiation_proposals`, updates
     * `currentRound` on the session, inserts one audit event.
     *
     * @param sessionId UUID of the negotiation session.
     * @param userId UUID of the authenticated user (to determine which side they are).
     * @return DTO of the created proposal.
     * @throws InvalidStateTransitionException If the session is not in "proposing".
     * @throws EntityNotFoundException If the session does not exist.
     * @throws RelayException If the relay call to the remote instance fails.
     */
    @Transactional
    fun generateAndSendProposal(sessionId: UUID, userId: UUID): NegotiationProposalDto {
        val session = findSessionOrThrow(sessionId)
        require(session.status == "proposing") {
            "Cannot propose in status '${session.status}' — session must be in 'proposing'"
        }

        val actor = determineActor(session, userId)
        val teamId = if (actor == "initiator") session.initiatorTeamId else session.responderTeamId
            ?: throw IllegalStateException("Responder team not set on session $sessionId")

        // Compute available windows for the acting team
        val windows = schedulingService.findAvailableWindows(
            teamId = teamId,
            dateRangeStart = session.requestedDateRangeStart
                ?: throw IllegalStateException("Date range start not set on session $sessionId"),
            dateRangeEnd = session.requestedDateRangeEnd
                ?: throw IllegalStateException("Date range end not set on session $sessionId"),
            durationMinutes = session.requestedDurationMinutes,
        )

        val topSlots = windows.take(PROPOSAL_SLOT_COUNT)
        val relaySlots = topSlots.map { RelaySlot(startsAt = it.startsAt, endsAt = it.endsAt) }
        val slotsJson = objectMapper.writeValueAsString(relaySlots)

        val newRound = session.currentRound + 1

        // Save proposal
        val proposal = proposalRepo.save(
            NegotiationProposal(
                sessionId = sessionId,
                proposedBy = actor,
                roundNumber = newRound,
                slots = slotsJson,
            ),
        )

        // Update session round
        val updatedSession = session.copy(currentRound = newRound, updatedAt = Instant.now())
        sessionRepo.save(updatedSession)

        // Relay to counterpart
        val targetUrl = if (actor == "initiator") session.responderInstance else session.initiatorInstance
        val sessionKey = retrieveSessionKey(sessionId)

        val relayRequest = RelayRequest(
            action = "propose",
            roundNumber = newRound,
            proposalId = proposal.id.toString(),
            actor = actor,
            slots = relaySlots,
        )

        val relayResponse = relayClient.sendRelay(targetUrl!!, sessionId.toString(), sessionKey, relayRequest)

        // Process relay response — the remote side may have found a match or exhausted rounds
        when (relayResponse.sessionStatus) {
            "pending_approval" -> {
                val matched = updatedSession.copy(
                    status = "pending_approval",
                    agreedStartsAt = relayResponse.agreedStartsAt,
                    agreedEndsAt = relayResponse.agreedEndsAt,
                    agreedLocation = relayResponse.agreedLocation,
                    currentRound = relayResponse.currentRound,
                    updatedAt = Instant.now(),
                )
                sessionRepo.save(matched)
                logEvent(sessionId, "match_found_via_relay", actor)
            }
            "failed" -> {
                val failed = updatedSession.copy(
                    status = "failed",
                    currentRound = relayResponse.currentRound,
                    updatedAt = Instant.now(),
                )
                sessionRepo.save(failed)
                logEvent(sessionId, "session_failed_via_relay", actor)
            }
            // "proposing" = counter is coming as a separate relay, no local update needed
        }

        logEvent(sessionId, "proposal_sent", actor, """{"round":$newRound,"slotCount":${topSlots.size}}""")
        log.info("Proposal sent: session={}, round={}, actor={}", sessionId, newRound, actor)

        return toProposalDto(proposal)
    }

    /**
     * Processes an incoming relay request from the counterpart instance.
     *
     * Routes by action type: "propose" triggers intersection with local availability,
     * "respond" updates proposal status, "confirm" handles confirmation, "cancel"
     * transitions to cancelled.
     *
     * **Idempotency:** Duplicate proposals (same session, round, actor) are safely
     * ignored — returns the current session state without re-processing.
     *
     * @param sessionId UUID of the negotiation session.
     * @param relay The relay request from the remote instance.
     * @return [RelayResponse] acknowledging receipt with current session state.
     * @throws EntityNotFoundException If the session does not exist.
     * @throws InvalidStateTransitionException If the action is not valid in the current state.
     */
    @Transactional
    fun processIncomingRelay(sessionId: UUID, relay: RelayRequest): RelayResponse {
        val session = findSessionOrThrow(sessionId)

        return when (relay.action) {
            "propose" -> handleIncomingProposal(session, relay)
            "respond" -> handleIncomingResponse(session, relay)
            "confirm" -> handleIncomingConfirm(session, relay)
            "cancel" -> handleIncomingCancel(session, relay)
            else -> throw IllegalArgumentException("Unknown relay action: ${relay.action}")
        }
    }

    /**
     * Handles an incoming "propose" relay action.
     *
     * Stores the incoming proposal, computes local team availability, and intersects
     * with the proposed slots. If a match is found, transitions to "pending_approval".
     * If no match and rounds remain, generates a counter-proposal. If max rounds
     * reached, transitions to "failed".
     */
    private fun handleIncomingProposal(session: NegotiationSession, relay: RelayRequest): RelayResponse {
        require(session.status == "proposing") {
            "Cannot receive proposals in status '${session.status}'"
        }

        // Idempotency check
        val existing = proposalRepo.findBySessionIdAndRoundNumber(session.id, relay.roundNumber)
        if (existing.any { it.proposedBy == relay.actor }) {
            log.info("Duplicate proposal ignored: session={}, round={}, actor={}", session.id, relay.roundNumber, relay.actor)
            return RelayResponse(sessionStatus = session.status, currentRound = session.currentRound)
        }

        // Store the incoming proposal
        val slotsJson = objectMapper.writeValueAsString(relay.slots ?: emptyList<RelaySlot>())
        proposalRepo.save(
            NegotiationProposal(
                sessionId = session.id,
                proposedBy = relay.actor,
                roundNumber = relay.roundNumber,
                slots = slotsJson,
            ),
        )

        logEvent(session.id, "proposal_received", relay.actor, """{"round":${relay.roundNumber}}""")

        // Determine local team and compute availability
        val localActor = if (relay.actor == "initiator") "responder" else "initiator"
        val localTeamId = if (localActor == "initiator") session.initiatorTeamId
        else session.responderTeamId
            ?: return RelayResponse(sessionStatus = session.status, currentRound = session.currentRound)

        val localWindows = schedulingService.findAvailableWindows(
            teamId = localTeamId,
            dateRangeStart = session.requestedDateRangeStart ?: return RelayResponse(
                sessionStatus = session.status,
                currentRound = session.currentRound,
            ),
            dateRangeEnd = session.requestedDateRangeEnd ?: return RelayResponse(
                sessionStatus = session.status,
                currentRound = session.currentRound,
            ),
            durationMinutes = session.requestedDurationMinutes,
        )

        // Convert incoming slots to TimeWindows for intersection
        val incomingWindows = (relay.slots ?: emptyList()).map { slot ->
            SchedulingService.TimeWindow(
                startsAt = slot.startsAt,
                endsAt = slot.endsAt,
                confidence = 1.0,
            )
        }

        val intersections = schedulingService.intersectWindows(localWindows, incomingWindows)

        if (intersections.isNotEmpty()) {
            // Match found — transition to pending_approval with agreed slot details
            val bestMatch = intersections.first()
            val updated = session.copy(
                status = "pending_approval",
                currentRound = relay.roundNumber,
                agreedStartsAt = bestMatch.startsAt,
                agreedEndsAt = bestMatch.endsAt,
                updatedAt = Instant.now(),
            )
            sessionRepo.save(updated)

            logEvent(session.id, "match_found", "system", objectMapper.writeValueAsString(
                mapOf(
                    "startsAt" to bestMatch.startsAt.toString(),
                    "endsAt" to bestMatch.endsAt.toString(),
                    "confidence" to bestMatch.confidence,
                ),
            ))
            log.info("Match found: session={}, slot={}-{}", session.id, bestMatch.startsAt, bestMatch.endsAt)

            return RelayResponse(
                sessionStatus = "pending_approval",
                currentRound = relay.roundNumber,
                agreedStartsAt = bestMatch.startsAt,
                agreedEndsAt = bestMatch.endsAt,
            )
        }

        // No match — check if max rounds exceeded
        if (relay.roundNumber >= session.maxRounds) {
            val failed = session.copy(
                status = "failed",
                currentRound = relay.roundNumber,
                updatedAt = Instant.now(),
            )
            sessionRepo.save(failed)
            logEvent(session.id, "session_failed", "system", """{"reason":"max_rounds_exceeded"}""")
            log.info("Negotiation failed (max rounds): session={}", session.id)

            return RelayResponse(sessionStatus = "failed", currentRound = relay.roundNumber)
        }

        // Counter-propose with local availability
        val counterSlots = localWindows.take(PROPOSAL_SLOT_COUNT)
        val counterRelaySlots = counterSlots.map { RelaySlot(startsAt = it.startsAt, endsAt = it.endsAt) }
        val counterJson = objectMapper.writeValueAsString(counterRelaySlots)
        val counterRound = relay.roundNumber + 1

        proposalRepo.save(
            NegotiationProposal(
                sessionId = session.id,
                proposedBy = localActor,
                roundNumber = counterRound,
                slots = counterJson,
            ),
        )

        val updatedSession = session.copy(currentRound = counterRound, updatedAt = Instant.now())
        sessionRepo.save(updatedSession)

        // Relay counter-proposal back and process the response
        val targetUrl = if (localActor == "responder") session.initiatorInstance else session.responderInstance
        var finalSession = updatedSession
        if (targetUrl != null) {
            val sessionKey = retrieveSessionKey(session.id)
            val counterRelay = RelayRequest(
                action = "propose",
                roundNumber = counterRound,
                proposalId = UUID.randomUUID().toString(),
                actor = localActor,
                slots = counterRelaySlots,
            )
            val relayResponse = relayClient.sendRelay(targetUrl, session.id.toString(), sessionKey, counterRelay)

            // Process relay response — the remote side may have matched or failed
            when (relayResponse.sessionStatus) {
                "pending_approval" -> {
                    finalSession = updatedSession.copy(
                        status = "pending_approval",
                        agreedStartsAt = relayResponse.agreedStartsAt,
                        agreedEndsAt = relayResponse.agreedEndsAt,
                        agreedLocation = relayResponse.agreedLocation,
                        currentRound = relayResponse.currentRound,
                        updatedAt = Instant.now(),
                    )
                    sessionRepo.save(finalSession)
                    logEvent(session.id, "match_found_via_relay", localActor)
                }
                "failed" -> {
                    finalSession = updatedSession.copy(
                        status = "failed",
                        currentRound = relayResponse.currentRound,
                        updatedAt = Instant.now(),
                    )
                    sessionRepo.save(finalSession)
                    logEvent(session.id, "session_failed_via_relay", localActor)
                }
                // "proposing" = further counter expected, no additional local update
            }
        }

        logEvent(session.id, "proposal_sent", localActor, """{"round":$counterRound,"slotCount":${counterSlots.size}}""")

        return RelayResponse(
            sessionStatus = finalSession.status,
            currentRound = finalSession.currentRound,
            agreedStartsAt = finalSession.agreedStartsAt,
            agreedEndsAt = finalSession.agreedEndsAt,
            agreedLocation = finalSession.agreedLocation,
        )
    }

    /**
     * Handles an incoming "respond" relay action.
     *
     * Updates the matching proposal's response status. If the response is "countered" and
     * includes slots, creates a counter-proposal record, intersects with local availability,
     * and transitions based on the result (match → pending_approval, max rounds → failed,
     * else stays in proposing).
     *
     * @param session The local negotiation session.
     * @param relay The relay request containing the response and optional counter-slots.
     * @return [RelayResponse] with the session's current state after processing.
     */
    private fun handleIncomingResponse(session: NegotiationSession, relay: RelayRequest): RelayResponse {
        val proposals = proposalRepo.findBySessionIdAndRoundNumber(session.id, relay.roundNumber)
        val counterpartActor = if (relay.actor == "initiator") "responder" else "initiator"
        val targetProposal = proposals.find { it.proposedBy == counterpartActor }

        if (targetProposal != null && relay.responseStatus != null) {
            val updated = targetProposal.copy(
                responseStatus = relay.responseStatus,
                rejectionReason = relay.rejectionReason,
            )
            proposalRepo.save(updated)
        }

        logEvent(session.id, "response_received", relay.actor, """{"status":"${relay.responseStatus}"}""")

        // Handle counter-proposal: create proposal record and intersect with local availability
        if (relay.responseStatus == "countered" && !relay.slots.isNullOrEmpty()) {
            val counterRound = relay.roundNumber + 1
            val counterJson = objectMapper.writeValueAsString(relay.slots)
            proposalRepo.save(
                NegotiationProposal(
                    sessionId = session.id,
                    proposedBy = relay.actor,
                    roundNumber = counterRound,
                    slots = counterJson,
                ),
            )

            val updatedSession = session.copy(currentRound = counterRound, updatedAt = Instant.now())
            sessionRepo.save(updatedSession)

            // Intersect counter with local availability
            val localActor = if (relay.actor == "initiator") "responder" else "initiator"
            val localTeamId = if (localActor == "initiator") session.initiatorTeamId
            else session.responderTeamId
                ?: return RelayResponse(sessionStatus = updatedSession.status, currentRound = counterRound)

            val localWindows = schedulingService.findAvailableWindows(
                teamId = localTeamId,
                dateRangeStart = session.requestedDateRangeStart
                    ?: return RelayResponse(sessionStatus = updatedSession.status, currentRound = counterRound),
                dateRangeEnd = session.requestedDateRangeEnd
                    ?: return RelayResponse(sessionStatus = updatedSession.status, currentRound = counterRound),
                durationMinutes = session.requestedDurationMinutes,
            )

            val counterWindows = relay.slots.map { slot ->
                SchedulingService.TimeWindow(startsAt = slot.startsAt, endsAt = slot.endsAt, confidence = 1.0)
            }

            val intersections = schedulingService.intersectWindows(localWindows, counterWindows)

            if (intersections.isNotEmpty()) {
                val bestMatch = intersections.first()
                val matched = updatedSession.copy(
                    status = "pending_approval",
                    agreedStartsAt = bestMatch.startsAt,
                    agreedEndsAt = bestMatch.endsAt,
                    updatedAt = Instant.now(),
                )
                sessionRepo.save(matched)
                logEvent(session.id, "match_found", "system")
                return RelayResponse(
                    sessionStatus = "pending_approval",
                    currentRound = counterRound,
                    agreedStartsAt = bestMatch.startsAt,
                    agreedEndsAt = bestMatch.endsAt,
                )
            }

            if (counterRound >= session.maxRounds) {
                val failed = updatedSession.copy(status = "failed", updatedAt = Instant.now())
                sessionRepo.save(failed)
                logEvent(session.id, "session_failed", "system", """{"reason":"max_rounds_exceeded"}""")
                return RelayResponse(sessionStatus = "failed", currentRound = counterRound)
            }

            return RelayResponse(sessionStatus = updatedSession.status, currentRound = counterRound)
        }

        return RelayResponse(sessionStatus = session.status, currentRound = session.currentRound)
    }

    /**
     * Handles an incoming "confirm" relay action from the remote instance.
     *
     * Sets the remote side's confirmation flag and persists the agreed slot from the relay.
     * If both sides have now confirmed, transitions to "confirmed" and creates a local
     * game [Event]. The local team ID is determined from the relay actor (if the remote
     * actor is "initiator", the local team is the responder, and vice versa).
     *
     * **Idempotency:** Duplicate confirm relays are safe — the flag is already set
     * and event creation uses the `(teamId, negotiationId)` lookup guard.
     */
    private fun handleIncomingConfirm(session: NegotiationSession, relay: RelayRequest): RelayResponse {
        if (session.status !in listOf("pending_approval", "confirmed")) {
            return RelayResponse(sessionStatus = session.status, currentRound = session.currentRound)
        }

        // Determine which side the remote actor represents and set their flag
        val agreedSlot = relay.slots?.firstOrNull()
        val updatedSession = session.copy(
            initiatorConfirmed = if (relay.actor == "initiator") true else session.initiatorConfirmed,
            responderConfirmed = if (relay.actor == "responder") true else session.responderConfirmed,
            agreedStartsAt = agreedSlot?.startsAt ?: session.agreedStartsAt,
            agreedEndsAt = agreedSlot?.endsAt ?: session.agreedEndsAt,
            agreedLocation = agreedSlot?.location ?: session.agreedLocation,
            updatedAt = Instant.now(),
        )

        val finalSession = if (updatedSession.initiatorConfirmed && updatedSession.responderConfirmed) {
            val confirmed = updatedSession.copy(status = "confirmed")
            sessionRepo.save(confirmed)

            // Determine local team (opposite of remote actor)
            val localTeamId = if (relay.actor == "initiator") session.responderTeamId else session.initiatorTeamId
            if (localTeamId != null && confirmed.agreedStartsAt != null && confirmed.agreedEndsAt != null) {
                val existingEvent = eventRepository.findByTeamIdAndNegotiationId(localTeamId, session.id)
                if (existingEvent == null) {
                    eventRepository.save(
                        Event(
                            teamId = localTeamId,
                            eventType = "game",
                            title = "Negotiated Game",
                            location = confirmed.agreedLocation,
                            startsAt = confirmed.agreedStartsAt,
                            endsAt = confirmed.agreedEndsAt,
                            status = "scheduled",
                            negotiationId = session.id,
                            createdBy = session.initiatorManager,
                        ),
                    )
                }
            }

            logEvent(session.id, "session_confirmed", "system", """{"trigger":"remote_confirm"}""")
            log.info("Negotiation confirmed via remote confirm: session={}", session.id)
            confirmed
        } else {
            sessionRepo.save(updatedSession)
            logEvent(session.id, "confirmation_received", relay.actor)
            updatedSession
        }

        return RelayResponse(sessionStatus = finalSession.status, currentRound = finalSession.currentRound)
    }

    /**
     * Handles an incoming "cancel" relay action. Transitions the session to cancelled.
     */
    private fun handleIncomingCancel(session: NegotiationSession, relay: RelayRequest): RelayResponse {
        if (session.status in TERMINAL_STATES) {
            return RelayResponse(sessionStatus = session.status, currentRound = session.currentRound)
        }

        val cancelled = session.copy(status = "cancelled", updatedAt = Instant.now())
        sessionRepo.save(cancelled)
        logEvent(session.id, "session_cancelled", relay.actor, """{"reason":"remote_cancellation"}""")
        log.info("Negotiation cancelled by remote: session={}", session.id)

        return RelayResponse(sessionStatus = "cancelled", currentRound = session.currentRound)
    }

    // ========================================================================
    // Confirm / Respond / Cancel (Phase D)
    // ========================================================================

    /**
     * Confirms the agreed-upon time slot for the local manager's side.
     *
     * Implements dual confirmation: each side sets its confirmation flag independently.
     * Only when both [NegotiationSession.initiatorConfirmed] and [NegotiationSession.responderConfirmed]
     * are true does the session transition to "confirmed" and a game [Event] get created.
     *
     * **Idempotency:** Duplicate confirmations from the same actor are no-ops — the flag
     * is already set and no additional event is created. Event creation also checks for
     * existing events via `(teamId, negotiationId)` lookup to prevent duplicates from
     * retried relay calls.
     *
     * **Database impact:** Updates `negotiation_sessions` (sets confirmation flag + agreed slot).
     * Conditionally inserts one row into `events` if both sides have confirmed. Inserts audit event.
     *
     * @param sessionId UUID of the negotiation session.
     * @param userId UUID of the authenticated user.
     * @param request The confirmation details (time slot).
     * @return DTO of the session reflecting current confirmation state. Event may or may not
     *   have been created depending on whether both sides have confirmed.
     * @throws InvalidStateTransitionException If the session is not in "pending_approval".
     */
    @Transactional
    fun confirmAgreement(
        sessionId: UUID,
        userId: UUID,
        request: ConfirmNegotiationRequest,
    ): NegotiationSessionDto {
        val session = findSessionOrThrow(sessionId)
        requireTransition(session.status, "confirmed")

        val actor = determineActor(session, userId)
        val teamId = if (actor == "initiator") session.initiatorTeamId else session.responderTeamId
            ?: throw IllegalStateException("Responder team not set")

        // Short-circuit duplicate confirms
        if ((actor == "initiator" && session.initiatorConfirmed) ||
            (actor == "responder" && session.responderConfirmed)) {
            return NegotiationSessionDto.from(session)
        }

        // Set this side's flag and store agreed slot
        val updatedSession = session.copy(
            initiatorConfirmed = if (actor == "initiator") true else session.initiatorConfirmed,
            responderConfirmed = if (actor == "responder") true else session.responderConfirmed,
            agreedStartsAt = request.slot.startsAt,
            agreedEndsAt = request.slot.endsAt,
            agreedLocation = request.slot.location,
            updatedAt = Instant.now(),
        )

        // Check if both sides have now confirmed
        val finalSession = if (updatedSession.initiatorConfirmed && updatedSession.responderConfirmed) {
            val confirmed = updatedSession.copy(status = "confirmed")
            sessionRepo.save(confirmed)

            // Create game event — idempotency guard prevents duplicates
            val existingEvent = eventRepository.findByTeamIdAndNegotiationId(teamId, sessionId)
            val event = existingEvent ?: eventRepository.save(
                Event(
                    teamId = teamId,
                    eventType = "game",
                    title = "Negotiated Game",
                    location = confirmed.agreedLocation,
                    startsAt = confirmed.agreedStartsAt!!,
                    endsAt = confirmed.agreedEndsAt!!,
                    status = "scheduled",
                    negotiationId = sessionId,
                    createdBy = userId,
                ),
            )
            logEvent(sessionId, "session_confirmed", actor, """{"eventId":"${event.id}"}""")
            log.info("Negotiation confirmed: session={}, event={}", sessionId, event.id)
            confirmed
        } else {
            sessionRepo.save(updatedSession) // stays in pending_approval
            logEvent(sessionId, "confirmation_recorded", actor)
            log.info("Confirmation recorded (waiting for other side): session={}, actor={}", sessionId, actor)
            updatedSession
        }

        // Relay confirmation to remote instance (include agreed slot so remote can persist it)
        val targetUrl = if (actor == "initiator") session.responderInstance else session.initiatorInstance
        if (targetUrl != null) {
            try {
                val sessionKey = retrieveSessionKey(sessionId)
                val confirmRelay = RelayRequest(
                    action = "confirm",
                    roundNumber = session.currentRound,
                    proposalId = UUID.randomUUID().toString(),
                    actor = actor,
                    slots = listOf(RelaySlot(request.slot.startsAt, request.slot.endsAt, request.slot.location)),
                )
                relayClient.sendRelay(targetUrl, sessionId.toString(), sessionKey, confirmRelay)
            } catch (e: Exception) {
                log.warn("Failed to relay confirmation to remote instance: {}", e.message)
                // Don't fail the local confirmation if relay fails — the remote side
                // can still confirm independently
            }
        }

        return NegotiationSessionDto.from(finalSession)
    }

    /**
     * Responds to the most recent proposal — accept, reject, or counter.
     *
     * Updates the latest proposal's response status and optionally triggers
     * a counter-proposal if the response is "countered".
     *
     * @param sessionId UUID of the negotiation session.
     * @param userId UUID of the authenticated user.
     * @param request The response details (status, optional counter-slots).
     * @return DTO of the updated session.
     */
    @Transactional
    fun respondToProposal(
        sessionId: UUID,
        userId: UUID,
        request: RespondToProposalRequest,
    ): NegotiationSessionDto {
        val session = findSessionOrThrow(sessionId)
        require(session.status == "proposing") {
            "Cannot respond to proposals in status '${session.status}'"
        }

        val actor = determineActor(session, userId)
        val counterpartActor = if (actor == "initiator") "responder" else "initiator"

        // Find the latest proposal from the counterpart
        val proposals = proposalRepo.findBySessionId(sessionId)
        val latestProposal = proposals
            .filter { it.proposedBy == counterpartActor }
            .maxByOrNull { it.roundNumber }
            ?: throw EntityNotFoundException("No proposals found from counterpart")

        // Update proposal response status
        val updatedProposal = latestProposal.copy(
            responseStatus = request.responseStatus,
            rejectionReason = request.rejectionReason,
        )
        proposalRepo.save(updatedProposal)

        if (request.responseStatus == "countered" && !request.counterSlots.isNullOrEmpty()) {
            val counterRound = latestProposal.roundNumber + 1
            val counterSlotsJson = objectMapper.writeValueAsString(
                request.counterSlots.map { RelaySlot(it.startsAt, it.endsAt, it.location) },
            )
            proposalRepo.save(
                NegotiationProposal(
                    sessionId = sessionId,
                    proposedBy = actor,
                    roundNumber = counterRound,
                    slots = counterSlotsJson,
                ),
            )
            sessionRepo.save(session.copy(currentRound = counterRound, updatedAt = Instant.now()))
        }

        logEvent(sessionId, "response_sent", actor, """{"status":"${request.responseStatus}"}""")

        // Relay the response
        val targetUrl = if (actor == "initiator") session.responderInstance else session.initiatorInstance
        if (targetUrl != null) {
            val sessionKey = retrieveSessionKey(sessionId)
            val responseRelay = RelayRequest(
                action = "respond",
                roundNumber = latestProposal.roundNumber,
                proposalId = latestProposal.id.toString(),
                actor = actor,
                responseStatus = request.responseStatus,
                rejectionReason = request.rejectionReason,
                slots = request.counterSlots?.map { RelaySlot(it.startsAt, it.endsAt, it.location) },
            )
            relayClient.sendRelay(targetUrl, sessionId.toString(), sessionKey, responseRelay)
        }

        return getSession(sessionId, userId)
    }

    /**
     * Cancels a negotiation session.
     *
     * Transitions the session to "cancelled" and relays the cancellation to the
     * remote instance if one is connected.
     *
     * @param sessionId UUID of the negotiation session.
     * @param userId UUID of the authenticated user.
     * @return DTO of the cancelled session.
     * @throws InvalidStateTransitionException If the session is already in a terminal state.
     */
    @Transactional
    fun cancelSession(sessionId: UUID, userId: UUID): NegotiationSessionDto {
        val session = findSessionOrThrow(sessionId)
        requireSessionAccess(session, userId)

        if (session.status in TERMINAL_STATES) {
            throw InvalidStateTransitionException(
                "Cannot cancel session in terminal state '${session.status}'",
            )
        }

        requireTransition(session.status, "cancelled")

        val actor = determineActor(session, userId)

        val cancelled = session.copy(status = "cancelled", updatedAt = Instant.now())
        val saved = sessionRepo.save(cancelled)

        logEvent(sessionId, "session_cancelled", actor)
        log.info("Negotiation cancelled: session={}, by={}", sessionId, actor)

        // Relay cancellation to remote instance
        val targetUrl = if (actor == "initiator") session.responderInstance else session.initiatorInstance
        if (targetUrl != null) {
            try {
                val sessionKey = retrieveSessionKey(sessionId)
                val cancelRelay = RelayRequest(
                    action = "cancel",
                    roundNumber = session.currentRound,
                    proposalId = UUID.randomUUID().toString(),
                    actor = actor,
                )
                relayClient.sendRelay(targetUrl, sessionId.toString(), sessionKey, cancelRelay)
            } catch (e: Exception) {
                log.warn("Failed to relay cancellation to remote instance: {}", e.message)
            }
        }

        return NegotiationSessionDto.from(saved)
    }

    // ========================================================================
    // Private Helpers
    // ========================================================================

    /**
     * Validates a state machine transition.
     *
     * @throws InvalidStateTransitionException If the transition is not allowed.
     */
    private fun requireTransition(currentStatus: String, targetStatus: String) {
        val allowed = ALLOWED_TRANSITIONS[currentStatus]
        if (allowed == null || targetStatus !in allowed) {
            throw InvalidStateTransitionException(
                "Invalid state transition: '$currentStatus' → '$targetStatus'",
            )
        }
    }

    /**
     * Finds a session by ID or throws [EntityNotFoundException].
     */
    private fun findSessionOrThrow(sessionId: UUID): NegotiationSession {
        return sessionRepo.findById(sessionId).orElseThrow {
            EntityNotFoundException("Negotiation session not found: $sessionId")
        }
    }

    /**
     * Returns whether a base URL points at this application instance.
     *
     * Normalizes trailing slashes so Bruno and backend config can use equivalent URLs
     * without causing cross-instance joins to be misclassified as local.
     */
    private fun isLocalInstance(instanceUrl: String?): Boolean {
        if (instanceUrl == null) return false
        return instanceUrl.trimEnd('/') == properties.instance.baseUrl.trimEnd('/')
    }

    /**
     * Verifies the authenticated user is a manager of either the initiator or responder team.
     *
     * @throws org.springframework.security.access.AccessDeniedException If the user is not a
     *   manager of either team.
     */
    private fun requireSessionAccess(session: NegotiationSession, userId: UUID) {
        val isInitiatorManager = runCatching {
            teamAccessGuard.requireManager(userId, session.initiatorTeamId)
        }.isSuccess

        val isResponderManager = session.responderTeamId?.let { respTeamId ->
            runCatching { teamAccessGuard.requireManager(userId, respTeamId) }.isSuccess
        } ?: false

        if (!isInitiatorManager && !isResponderManager) {
            throw org.springframework.security.access.AccessDeniedException(
                "User $userId is not a manager of either team in session ${session.id}",
            )
        }
    }

    /**
     * Determines whether the authenticated user is the "initiator" or "responder"
     * in a negotiation session.
     *
     * @return "initiator" or "responder".
     * @throws org.springframework.security.access.AccessDeniedException If the user
     *   is not a manager of either team.
     */
    private fun determineActor(session: NegotiationSession, userId: UUID): String {
        val isInitiator = runCatching {
            teamAccessGuard.requireManager(userId, session.initiatorTeamId)
        }.isSuccess

        if (isInitiator) return "initiator"

        if (session.responderTeamId != null) {
            val isResponder = runCatching {
                teamAccessGuard.requireManager(userId, session.responderTeamId)
            }.isSuccess
            if (isResponder) return "responder"
        }

        throw org.springframework.security.access.AccessDeniedException(
            "User $userId is not a manager of either team in session ${session.id}",
        )
    }

    /**
     * Generates a cryptographically random invite token.
     *
     * @return A URL-safe Base64-encoded token string (48 characters).
     */
    private fun generateInviteToken(): String {
        val bytes = ByteArray(INVITE_TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Caches the derived HMAC session key in Redis for post-join relay authentication.
     *
     * The key is stored as Base64-encoded string at `fieldiq:sessionkey:<sessionId>`
     * with a 72h TTL.
     */
    private fun cacheSessionKey(sessionId: UUID, sessionKey: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(sessionKey)
        redisTemplate.opsForValue().set(
            "${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId",
            encoded,
            HmacAuthenticationFilter.SESSION_KEY_TTL,
        )
    }

    /**
     * Retrieves the cached session key from Redis.
     *
     * @throws IllegalStateException If the key is not found in Redis.
     */
    private fun retrieveSessionKey(sessionId: UUID): ByteArray {
        val encoded = redisTemplate.opsForValue()
            .get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            ?: throw IllegalStateException("Session key not found in cache for session $sessionId")
        return Base64.getDecoder().decode(encoded)
    }

    /**
     * SHA-256 hashes a byte array for audit logging (e.g., session key hash).
     *
     * @return Hex-encoded hash string.
     */
    private fun hashBytes(data: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Logs a negotiation audit event.
     */
    private fun logEvent(
        sessionId: UUID,
        eventType: String,
        actor: String,
        payload: String? = null,
    ) {
        negotiationEventRepo.save(
            NegotiationEvent(
                sessionId = sessionId,
                eventType = eventType,
                actor = actor,
                payload = payload,
            ),
        )
    }

    /**
     * Converts a [NegotiationProposal] entity to a [NegotiationProposalDto].
     *
     * Deserializes the JSONB slots column into a list of [TimeSlotDto].
     */
    private fun toProposalDto(proposal: NegotiationProposal): NegotiationProposalDto {
        val slots: List<RelaySlot> = try {
            objectMapper.readValue(proposal.slots)
        } catch (e: Exception) {
            log.warn("Failed to deserialize proposal slots: proposalId={}", proposal.id, e)
            emptyList()
        }
        val slotDtos = slots.map { TimeSlotDto(it.startsAt.toString(), it.endsAt.toString(), it.location) }
        return NegotiationProposalDto.from(proposal, slotDtos)
    }
}
