package com.fieldiq.api.dto

import com.fieldiq.domain.NegotiationProposal
import com.fieldiq.domain.NegotiationSession
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

// ============================================================================
// Request DTOs
// ============================================================================

/**
 * Request body for `POST /negotiations`.
 *
 * Initiates a cross-team scheduling negotiation. Creates a session with an
 * invite token that can be shared with the opposing team's manager.
 * Requires manager role on the specified team.
 *
 * Corresponds to TypeScript: `InitiateNegotiationRequest` in `shared/types/index.ts`.
 *
 * @property teamId UUID of the initiating team. The authenticated user must be a manager.
 * @property dateRangeStart Earliest acceptable game date in YYYY-MM-DD format.
 * @property dateRangeEnd Latest acceptable game date in YYYY-MM-DD format.
 * @property durationMinutes Desired game duration in minutes. Defaults to 90.
 * @property preferredDays Preferred days of week (0=Sunday through 6=Saturday).
 */
data class InitiateNegotiationRequest(
    @field:NotNull
    val teamId: UUID,
    @field:NotBlank
    val dateRangeStart: String,
    @field:NotBlank
    val dateRangeEnd: String,
    val durationMinutes: Int = 90,
    val preferredDays: List<Int>? = null,
)

/**
 * Request body for `POST /negotiations/:sessionId/join`.
 *
 * Allows the opposing team's manager to join an existing negotiation session
 * using the single-use invite token. Consumes the token and transitions the
 * session to "proposing" status.
 *
 * @property inviteToken The single-use bearer token from the invite link.
 * @property responderTeamId UUID of the responding team on their FieldIQ instance.
 * @property responderInstance Base URL of the responder's FieldIQ instance
 *   (e.g., "http://localhost:8081"). Used for cross-instance relay calls.
 */
data class JoinSessionRequest(
    @field:NotBlank
    val inviteToken: String,
    @field:NotNull
    val responderTeamId: UUID,
    @field:NotBlank
    val responderInstance: String,
)

/**
 * Request body for `POST /negotiations/:sessionId/propose`.
 *
 * Sends a set of available time slots to the other team. Typically called
 * after joining a session, or to manually propose slots instead of relying
 * on automatic proposal generation.
 *
 * Corresponds to TypeScript: `ProposeRequest` in `shared/types/index.ts`.
 *
 * @property slots Array of proposed time windows for the game.
 */
data class ProposeRequest(
    @field:Valid
    val slots: List<TimeSlotRequest>,
)

/**
 * A single proposed time slot in a negotiation request.
 *
 * Used in [ProposeRequest], [RespondToProposalRequest] (counter-slots),
 * and [ConfirmNegotiationRequest].
 *
 * @property startsAt Slot start time in ISO 8601 UTC format.
 * @property endsAt Slot end time in ISO 8601 UTC format.
 * @property location Proposed venue/field name. Optional.
 */
data class TimeSlotRequest(
    @field:NotNull
    val startsAt: Instant,
    @field:NotNull
    val endsAt: Instant,
    val location: String? = null,
)

/**
 * Request body for `POST /negotiations/:sessionId/respond`.
 *
 * Responds to the other team's most recent proposal — accept, reject, or counter.
 *
 * Corresponds to TypeScript: `RespondToProposalRequest` in `shared/types/index.ts`.
 *
 * @property responseStatus How to respond: "accepted", "rejected", or "countered".
 * @property rejectionReason Reason for rejection (required when responseStatus is "rejected").
 * @property counterSlots Counter-proposal slots (required when responseStatus is "countered").
 */
data class RespondToProposalRequest(
    @field:NotBlank
    @field:Pattern(regexp = "accepted|rejected|countered")
    val responseStatus: String,
    val rejectionReason: String? = null,
    val counterSlots: List<TimeSlotRequest>? = null,
)

/**
 * Request body for `POST /negotiations/:sessionId/confirm`.
 *
 * Human confirmation of an agreed-upon time slot. Both managers must confirm
 * before the game is officially scheduled.
 *
 * Corresponds to TypeScript: `ConfirmNegotiationRequest` in `shared/types/index.ts`.
 *
 * @property slot The specific time slot being confirmed.
 */
data class ConfirmNegotiationRequest(
    @field:Valid
    @field:NotNull
    val slot: TimeSlotRequest,
)

/**
 * Request body for `POST /api/negotiate/incoming`.
 *
 * Sent by Instance A to Instance B during the join handshake to bootstrap a
 * shadow session on Instance B. The invite token serves as a bearer credential —
 * it is cryptographic randomness shared only with session participants. Instance B
 * derives its HMAC session key from this token and creates a local session copy so
 * subsequent `/relay` calls can be HMAC-validated.
 *
 * This endpoint is excluded from the HMAC filter because no local session exists
 * on Instance B yet at the time of this call.
 *
 * @property sessionId UUID of the negotiation session (created by Instance A).
 * @property inviteToken Bearer credential and key derivation material.
 * @property initiatorTeamId UUID of the initiating team on Instance A.
 * @property initiatorInstance Base URL of Instance A.
 * @property responderTeamId UUID of the responding team on Instance B.
 * @property responderInstance Base URL of Instance B for relay symmetry.
 * @property requestedDateRangeStart Earliest acceptable game date in YYYY-MM-DD format.
 * @property requestedDateRangeEnd Latest acceptable game date in YYYY-MM-DD format.
 * @property requestedDurationMinutes Desired game duration in minutes.
 * @property maxRounds Maximum proposal rounds before failure.
 * @property expiresAt Session expiration time in ISO 8601 format.
 */
data class IncomingNegotiationRequest(
    @field:NotNull
    val sessionId: UUID,
    @field:NotBlank
    val inviteToken: String,
    @field:NotNull
    val initiatorTeamId: UUID,
    @field:NotBlank
    val initiatorInstance: String,
    @field:NotNull
    val responderTeamId: UUID,
    @field:NotBlank
    val responderInstance: String,
    val requestedDateRangeStart: String? = null,
    val requestedDateRangeEnd: String? = null,
    val requestedDurationMinutes: Int = 90,
    val maxRounds: Int = 3,
    val expiresAt: String? = null,
)

// ============================================================================
// Response DTOs
// ============================================================================

/**
 * Negotiation session data returned in API responses.
 *
 * Tracks the full state of a cross-team scheduling negotiation including
 * embedded proposals when available. The [inviteToken] is only non-null in
 * the creation response (before the responder joins); Jackson `non_null`
 * serialization omits it in subsequent GET responses.
 *
 * Corresponds to TypeScript: `NegotiationSessionDto` in `shared/types/index.ts`.
 *
 * @property id UUID of the session.
 * @property initiatorTeamId UUID of the team that started this negotiation.
 * @property responderTeamId UUID of the responding team. Null until responder joins.
 * @property status Current state machine status.
 * @property requestedDateRangeStart Earliest acceptable date set by initiator.
 * @property requestedDateRangeEnd Latest acceptable date set by initiator.
 * @property requestedDurationMinutes Desired game duration in minutes.
 * @property agreedStartsAt Agreed game start time (only when confirmed).
 * @property agreedLocation Agreed game location (only when confirmed).
 * @property inviteToken Invite token (only in creation response, null after join).
 * @property maxRounds Maximum proposal rounds before failure.
 * @property currentRound Current round number (0 = no proposals yet).
 * @property expiresAt Session expiration time.
 * @property createdAt When the session was created.
 * @property proposals Embedded proposal history for this session.
 */
data class NegotiationSessionDto(
    val id: String,
    val initiatorTeamId: String,
    val responderTeamId: String? = null,
    val status: String,
    val requestedDateRangeStart: String? = null,
    val requestedDateRangeEnd: String? = null,
    val requestedDurationMinutes: Int,
    val agreedStartsAt: String? = null,
    val agreedEndsAt: String? = null,
    val agreedLocation: String? = null,
    val inviteToken: String? = null,
    val maxRounds: Int,
    val currentRound: Int,
    val initiatorConfirmed: Boolean = false,
    val responderConfirmed: Boolean = false,
    val expiresAt: String? = null,
    val createdAt: String,
    val proposals: List<NegotiationProposalDto> = emptyList(),
) {
    companion object {
        /**
         * Converts a [NegotiationSession] entity and its proposals to a response DTO.
         *
         * @param session The session entity.
         * @param proposalDtos Pre-converted proposal DTOs. If provided, [proposals] is ignored.
         * @return A [NegotiationSessionDto] with all fields mapped.
         */
        fun from(
            session: NegotiationSession,
            proposalDtos: List<NegotiationProposalDto> = emptyList(),
        ): NegotiationSessionDto = NegotiationSessionDto(
            id = session.id.toString(),
            initiatorTeamId = session.initiatorTeamId.toString(),
            responderTeamId = session.responderTeamId?.toString(),
            status = session.status,
            requestedDateRangeStart = session.requestedDateRangeStart?.toString(),
            requestedDateRangeEnd = session.requestedDateRangeEnd?.toString(),
            requestedDurationMinutes = session.requestedDurationMinutes,
            agreedStartsAt = session.agreedStartsAt?.toString(),
            agreedEndsAt = session.agreedEndsAt?.toString(),
            agreedLocation = session.agreedLocation,
            inviteToken = session.inviteToken,
            maxRounds = session.maxRounds,
            currentRound = session.currentRound,
            initiatorConfirmed = session.initiatorConfirmed,
            responderConfirmed = session.responderConfirmed,
            expiresAt = session.expiresAt?.toString(),
            createdAt = session.createdAt.toString(),
            proposals = proposalDtos,
        )
    }
}

/**
 * Proposal data returned in API responses.
 *
 * Contains the time slots one side proposed during a negotiation round.
 * The [slots] are deserialized from the JSONB column in [NegotiationProposal].
 *
 * Corresponds to TypeScript: `NegotiationProposalDto` in `shared/types/index.ts`.
 *
 * @property id UUID of the proposal.
 * @property sessionId UUID of the parent negotiation session.
 * @property proposedBy Which side sent this proposal: "initiator" or "responder".
 * @property roundNumber The negotiation round (1-indexed).
 * @property slots Array of proposed time windows.
 * @property responseStatus How the other side responded.
 * @property rejectionReason Reason for rejection, if applicable.
 */
data class NegotiationProposalDto(
    val id: String,
    val sessionId: String,
    val proposedBy: String,
    val roundNumber: Int,
    val slots: List<TimeSlotDto>,
    val responseStatus: String,
    val rejectionReason: String? = null,
) {
    companion object {
        /**
         * Converts a [NegotiationProposal] entity to a response DTO.
         *
         * The [NegotiationProposal.slots] JSONB string is deserialized externally
         * and passed as [parsedSlots] to avoid coupling this DTO to Jackson.
         *
         * @param proposal The proposal entity.
         * @param parsedSlots The deserialized slot list from the JSONB column.
         * @return A [NegotiationProposalDto] with all fields mapped.
         */
        fun from(
            proposal: NegotiationProposal,
            parsedSlots: List<TimeSlotDto>,
        ): NegotiationProposalDto = NegotiationProposalDto(
            id = proposal.id.toString(),
            sessionId = proposal.sessionId.toString(),
            proposedBy = proposal.proposedBy,
            roundNumber = proposal.roundNumber,
            slots = parsedSlots,
            responseStatus = proposal.responseStatus,
            rejectionReason = proposal.rejectionReason,
        )
    }
}

/**
 * A single time slot in a negotiation response.
 *
 * Corresponds to TypeScript: `TimeSlotDto` in `shared/types/index.ts`.
 *
 * @property startsAt Slot start time in ISO 8601 UTC format.
 * @property endsAt Slot end time in ISO 8601 UTC format.
 * @property location Proposed venue name. Null if not yet decided.
 */
data class TimeSlotDto(
    val startsAt: String,
    val endsAt: String,
    val location: String? = null,
)
