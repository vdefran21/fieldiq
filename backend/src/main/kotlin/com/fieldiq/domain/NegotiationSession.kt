package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Represents a single cross-team scheduling negotiation — FieldIQ's core IP.
 *
 * A negotiation session tracks the full lifecycle of two teams trying to find
 * a mutually agreeable game time. Sessions can span two different FieldIQ instances
 * (the key differentiator), with proposals relayed via HMAC-authenticated HTTP calls.
 *
 * **State machine flow:**
 * ```
 * pending_response → proposing → pending_approval → confirmed
 *                                                  → failed
 *                                                  → cancelled
 * ```
 *
 * 1. **pending_response:** Manager A creates a session with an [inviteToken]. Waiting
 *    for Manager B to join.
 * 2. **proposing:** Both sides are exchanging slot proposals. Each round, one side
 *    proposes available time windows (via [NegotiationProposal]), and the other side
 *    accepts, rejects, or counters. Up to [maxRounds] rounds (default 3).
 * 3. **pending_approval:** A matching slot was found by the system. Both managers
 *    must confirm before the game is scheduled.
 * 4. **confirmed:** Both managers approved. Events are created on both teams. Terminal state.
 * 5. **failed:** No match found within [maxRounds], or session expired. Terminal state.
 * 6. **cancelled:** One side withdrew from the negotiation. Terminal state.
 *
 * **Cross-instance auth:** The [inviteToken] is a single-use bearer secret (48h TTL).
 * After the responder joins, a per-session HMAC key is derived:
 * `HMAC-SHA256(instance_secret, invite_token)`. All subsequent cross-instance calls
 * use this derived key for signature validation. The [sessionKeyHash] stores a hash
 * of the derived key for audit purposes (never the key itself).
 *
 * @property id Unique identifier, auto-generated UUID. Used as the session ID in
 *   cross-instance HTTP headers (`X-FieldIQ-Session-Id`).
 * @property initiatorTeamId UUID of the team that started this negotiation.
 *   On the initiating instance this points to a local [Team]. On a responder-side
 *   shadow session it is a remote team identifier carried only as a reference, so
 *   the database does not enforce a local foreign key for this column.
 * @property initiatorInstance Base URL of the initiating FieldIQ instance
 *   (e.g., "http://localhost:8080"). Used by the responder to relay proposals back.
 * @property initiatorManager Foreign key to the [User] (manager) who initiated. Used
 *   for sending confirmation notifications.
 * @property responderTeamId Foreign key to the responding [Team]. Null until the
 *   responder joins via [inviteToken]. On cross-instance negotiations, this is the
 *   responder's team ID on THEIR instance, stored here as a reference.
 * @property responderInstance Base URL of the responding FieldIQ instance. Null until join.
 * @property responderExternalId The responder's team UUID on their FieldIQ instance.
 *   Stored separately because [responderTeamId] may reference a local team (same-instance)
 *   or be null (cross-instance, not yet joined).
 * @property status Current state machine state. See state machine docs above. Enforced
 *   by CHECK constraint in the database.
 * @property requestedDateRangeStart Earliest acceptable game date, set by the initiator.
 * @property requestedDateRangeEnd Latest acceptable game date, set by the initiator.
 * @property requestedDurationMinutes Desired game duration in minutes. Defaults to 90
 *   (standard youth soccer game). Used by SchedulingService to find windows of
 *   sufficient length.
 * @property agreedStartsAt The mutually agreed game start time. Populated when a match
 *   is found (pending_approval) or on confirmation. Null before match.
 * @property agreedEndsAt The mutually agreed game end time. Populated alongside
 *   [agreedStartsAt] when a match is found via relay response processing.
 * @property agreedLocation The mutually agreed game location. Populated on confirmation.
 * @property inviteToken Single-use bearer token for the join handshake. Generated on
 *   session creation, consumed (nullified) when the responder joins. 48-hour TTL.
 *   Unique constraint prevents token reuse.
 * @property sessionKeyHash SHA-256 hash of the derived HMAC session key, stored for
 *   audit purposes. The actual key is never persisted — both instances derive it
 *   independently from `HMAC-SHA256(instance_secret, invite_token)`.
 * @property maxRounds Maximum number of proposal exchange rounds before the negotiation
 *   fails. Defaults to 3. Prevents infinite back-and-forth.
 * @property currentRound The current round number (0 = no proposals yet, 1 = first
 *   round of proposals, etc.). Incremented by NegotiationService when a new round begins.
 * @property initiatorConfirmed Whether the initiating manager has confirmed the agreed slot.
 *   Both [initiatorConfirmed] and [responderConfirmed] must be true before the session
 *   transitions to "confirmed" and game events are created.
 * @property responderConfirmed Whether the responding manager has confirmed the agreed slot.
 *   Both flags must be true before the session transitions to "confirmed".
 * @property expiresAt Absolute expiration timestamp for the session. Sessions that reach
 *   this time without confirming are moved to "failed" status by a cleanup job.
 * @property createdAt Timestamp of session creation, set once and never updated.
 * @property updatedAt Timestamp of the last state change. Updated by NegotiationService
 *   on every status transition.
 * @see NegotiationProposal for the individual slot proposals exchanged during this session.
 * @see NegotiationEvent for the audit log of state changes.
 * @see Event for the game event created when a session reaches "confirmed" status.
 */
@Entity
@Table(name = "negotiation_sessions")
data class NegotiationSession(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "initiator_team_id", nullable = false)
    val initiatorTeamId: UUID,

    @Column(name = "initiator_instance", nullable = false)
    val initiatorInstance: String,

    @Column(name = "initiator_manager")
    val initiatorManager: UUID? = null,

    @Column(name = "responder_team_id")
    val responderTeamId: UUID? = null,

    @Column(name = "responder_instance")
    val responderInstance: String? = null,

    @Column(name = "responder_external_id")
    val responderExternalId: String? = null,

    @Column(nullable = false)
    val status: String = "pending_response",

    @Column(name = "requested_date_range_start")
    val requestedDateRangeStart: LocalDate? = null,

    @Column(name = "requested_date_range_end")
    val requestedDateRangeEnd: LocalDate? = null,

    @Column(name = "requested_duration_minutes", nullable = false)
    val requestedDurationMinutes: Int = 90,

    @Column(name = "agreed_starts_at")
    val agreedStartsAt: Instant? = null,

    @Column(name = "agreed_ends_at")
    val agreedEndsAt: Instant? = null,

    @Column(name = "agreed_location")
    val agreedLocation: String? = null,

    @Column(name = "invite_token", unique = true)
    val inviteToken: String? = null,

    @Column(name = "session_key_hash")
    val sessionKeyHash: String? = null,

    @Column(name = "max_rounds", nullable = false)
    val maxRounds: Int = 3,

    @Column(name = "current_round", nullable = false)
    val currentRound: Int = 0,

    @Column(name = "initiator_confirmed", nullable = false)
    val initiatorConfirmed: Boolean = false,

    @Column(name = "responder_confirmed", nullable = false)
    val responderConfirmed: Boolean = false,

    @Column(name = "expires_at")
    val expiresAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)
