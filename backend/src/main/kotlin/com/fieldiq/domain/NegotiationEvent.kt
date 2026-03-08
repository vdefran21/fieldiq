package com.fieldiq.domain

import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Immutable audit log entry for a negotiation state change.
 *
 * Every meaningful action in a [NegotiationSession] produces a NegotiationEvent record.
 * This creates a complete, append-only history of the negotiation that can be used for:
 * - Debugging failed negotiations (what happened and when)
 * - Displaying a timeline in the mobile app ("Proposal sent", "Match found", etc.)
 * - Compliance and dispute resolution (who did what)
 *
 * Events are never updated or deleted — they form an immutable audit trail.
 *
 * Common [eventType] values:
 * - "session_created" — negotiation initiated by a manager
 * - "responder_joined" — second team joined via invite token
 * - "proposal_sent" — one side sent time slot proposals
 * - "proposal_received" — incoming proposal from remote instance
 * - "match_found" — system detected overlapping availability
 * - "confirmation_sent" — manager approved the matched slot
 * - "session_confirmed" — both sides confirmed, game scheduled
 * - "session_failed" — max rounds exceeded or session expired
 * - "session_cancelled" — one side withdrew
 *
 * @property id Unique identifier, auto-generated UUID.
 * @property sessionId Foreign key to the [NegotiationSession] this event belongs to.
 * @property eventType Descriptive label for the action that occurred. Not constrained
 *   by CHECK to allow new event types without migration. See common values above.
 * @property actor Who triggered this event: "initiator", "responder", or "system"
 *   (for automated actions like expiration). Nullable for legacy or system events.
 * @property payload Optional JSONB containing event-specific data. For example, a
 *   "proposal_sent" event might include the proposal ID and slot count. Stored as
 *   raw JSON string; structure varies by [eventType].
 * @property createdAt Timestamp of when the event occurred. Immutable.
 * @see NegotiationSession for the session this event belongs to.
 */
@Entity
@Table(name = "negotiation_events")
data class NegotiationEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "session_id", nullable = false)
    val sessionId: UUID,

    @Column(name = "event_type", nullable = false)
    val eventType: String,

    val actor: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    val payload: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
