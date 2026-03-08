package com.fieldiq.api.dto

import java.time.Instant

/**
 * Request body for cross-instance relay calls between FieldIQ instances.
 *
 * Sent by [com.fieldiq.service.CrossInstanceRelayClient] and received by the relay
 * endpoint on the remote instance. The body is included in the HMAC signature
 * computation, so any modification in transit will cause signature validation to fail.
 *
 * **Actions:**
 * - `propose` — send candidate time slots for the current round
 * - `respond` — accept, reject, or counter a received proposal
 * - `confirm` — confirm an agreed-upon slot
 * - `cancel` — withdraw from the negotiation
 *
 * @property action The relay action type: "propose", "respond", "confirm", or "cancel".
 * @property roundNumber The current negotiation round (1-indexed).
 * @property proposalId UUID for idempotency — prevents duplicate processing if a relay
 *   call is retried. Maps to [com.fieldiq.domain.NegotiationProposal.id].
 * @property actor Which side is sending: "initiator" or "responder".
 * @property slots Array of proposed time windows. Populated for "propose" and "respond"
 *   (with countered status) actions. Null for "confirm" and "cancel".
 * @property responseStatus How the sender is responding to the other side's proposal:
 *   "accepted", "rejected", or "countered". Only populated for "respond" action.
 * @property rejectionReason Human-readable reason for rejection (e.g., "no_availability").
 *   Only populated when [responseStatus] is "rejected".
 * @see RelayResponse for the response from the receiving instance.
 */
data class RelayRequest(
    val action: String,
    val roundNumber: Int,
    val proposalId: String,
    val actor: String,
    val slots: List<RelaySlot>? = null,
    val responseStatus: String? = null,
    val rejectionReason: String? = null,
)

/**
 * A single proposed time slot within a relay request.
 *
 * Represents a candidate game time sent between instances during negotiation.
 * Only aggregated team availability is shared — never individual member schedules.
 *
 * @property startsAt ISO-8601 UTC start time of the proposed slot.
 * @property endsAt ISO-8601 UTC end time of the proposed slot.
 * @property location The proposed venue/field name. Optional — may be null if
 *   location is to be determined.
 */
data class RelaySlot(
    val startsAt: Instant,
    val endsAt: Instant,
    val location: String? = null,
)

/**
 * Response returned by a FieldIQ instance after receiving a relay request.
 *
 * Acknowledges receipt and reports the session's current state. The caller uses
 * this to verify the relay was processed and to detect state machine mismatches.
 * When the remote side transitions to "pending_approval" (match found), the agreed
 * slot details are included so the initiator can also transition locally.
 *
 * @property status Always "received" for successful processing.
 * @property sessionStatus The negotiation session's status after processing the relay
 *   (e.g., "proposing", "pending_approval", "confirmed", "failed").
 * @property currentRound The session's current round number after processing.
 * @property agreedStartsAt The agreed game start time, populated when sessionStatus
 *   is "pending_approval" (match found on the remote side).
 * @property agreedEndsAt The agreed game end time, populated when sessionStatus
 *   is "pending_approval".
 * @property agreedLocation The agreed game location, populated when sessionStatus
 *   is "pending_approval".
 */
data class RelayResponse(
    val status: String = "received",
    val sessionStatus: String,
    val currentRound: Int,
    val agreedStartsAt: Instant? = null,
    val agreedEndsAt: Instant? = null,
    val agreedLocation: String? = null,
)

/**
 * Error response for relay endpoint failures.
 *
 * Returned when a relay request fails validation or processing. Uses error codes
 * matching the contract in Doc 04.
 *
 * @property error Machine-readable error code: "invalid_signature", "session_not_found",
 *   "invalid_state_transition", "expired", or "replay_detected".
 * @property message Human-readable explanation of the failure.
 */
data class RelayErrorResponse(
    val error: String,
    val message: String,
)
