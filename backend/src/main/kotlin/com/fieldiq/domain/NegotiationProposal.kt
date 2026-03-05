package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Represents a set of proposed time slots sent by one side during a negotiation round.
 *
 * During each round of a [NegotiationSession], one side computes their available time
 * windows (via SchedulingService) and sends them as a proposal. The other side then
 * intersects these with their own availability to find matches, or counters with
 * their own proposal.
 *
 * **Idempotency:** The unique constraint on (sessionId, roundNumber, proposedBy)
 * prevents duplicate proposals. If a network retry causes the same proposal to be
 * sent twice, the second insert is rejected. This is critical for cross-instance
 * reliability where HTTP calls may be retried.
 *
 * **Slot format:** The [slots] field is a JSONB column containing an array of time
 * slot objects. Each slot has the schema:
 * ```json
 * [{"starts_at": "2026-04-15T14:00:00Z", "ends_at": "2026-04-15T15:30:00Z", "location": "Field 3"}]
 * ```
 * The [schemaVersion] field allows future changes to the slot JSON format without
 * breaking existing proposals.
 *
 * @property id Unique identifier, auto-generated UUID.
 * @property sessionId Foreign key to the parent [NegotiationSession].
 * @property proposedBy Which side sent this proposal: "initiator" or "responder".
 *   Enforced by CHECK constraint.
 * @property roundNumber The negotiation round this proposal belongs to (1-indexed).
 *   Combined with [sessionId] and [proposedBy] forms the idempotency key.
 * @property slots JSONB array of proposed time windows. Stored as a raw JSON string
 *   in the entity; deserialized by the service layer. See slot format docs above.
 * @property schemaVersion Version number of the [slots] JSON schema. Defaults to 1.
 *   Allows backward-compatible evolution of the proposal format.
 * @property responseStatus How the other side responded to this proposal:
 *   "pending" (not yet responded), "accepted" (match found), "rejected" (no suitable
 *   slots), "countered" (responded with their own proposal). Enforced by CHECK.
 * @property rejectionReason Human-readable reason if [responseStatus] is "rejected".
 *   Values like "no_availability", "location_conflict" help with debugging and UX.
 * @property createdAt Timestamp of proposal creation, set once and never updated.
 * @see NegotiationSession for the parent session this proposal belongs to.
 */
@Entity
@Table(name = "negotiation_proposals")
data class NegotiationProposal(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "session_id", nullable = false)
    val sessionId: UUID,

    @Column(name = "proposed_by", nullable = false)
    val proposedBy: String,

    @Column(name = "round_number", nullable = false)
    val roundNumber: Int = 1,

    @Column(nullable = false, columnDefinition = "jsonb")
    val slots: String,

    @Column(name = "schema_version", nullable = false)
    val schemaVersion: Int = 1,

    @Column(name = "response_status")
    val responseStatus: String = "pending",

    @Column(name = "rejection_reason")
    val rejectionReason: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
