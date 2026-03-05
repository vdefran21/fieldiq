package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Represents a scheduled team event — a game, practice, tournament, or other activity.
 *
 * Events are the end result of the scheduling workflow. They can be created manually
 * by a manager, or automatically when a cross-team negotiation reaches "confirmed"
 * status. When created via negotiation, [negotiationId] links back to the
 * [NegotiationSession] that produced it, and the event is created on BOTH teams
 * involved in the negotiation.
 *
 * **Opponent modeling:** For games, the opponent can be referenced two ways:
 * - **Same-instance opponent:** [opponentTeamId] is set to the other team's UUID on
 *   this FieldIQ instance. Both teams see the game in their schedule.
 * - **Cross-instance opponent:** [opponentName] (display string like "Bethesda Fire U12")
 *   and [opponentExternalRef] (their team UUID on their FieldIQ instance) are set.
 *   The remote team's event is managed by their own FieldIQ instance.
 *
 * @property id Unique identifier, auto-generated UUID.
 * @property teamId Foreign key to the [Team] that owns this event.
 * @property eventType Type of event. One of: "game", "practice", "tournament", "other".
 *   Enforced by a CHECK constraint in the database.
 * @property title Optional display title (e.g., "vs Arlington United"). Auto-generated
 *   from opponent name for negotiation-created games if not explicitly set.
 * @property location Venue name or address (e.g., "Bethesda Soccer Field #3").
 * @property locationNotes Additional directions or notes (e.g., "Park in Lot B, enter
 *   through the north gate").
 * @property startsAt Scheduled start time in UTC. Null for draft/unscheduled events.
 * @property endsAt Scheduled end time in UTC. Null for draft/unscheduled events.
 * @property status Lifecycle state of the event. One of: "draft" (not yet finalized),
 *   "scheduled" (confirmed and visible), "cancelled", "completed". Enforced by CHECK.
 * @property opponentTeamId For same-instance games: UUID of the opposing team on this
 *   FieldIQ instance. Null for practices, cross-instance games, and non-game events.
 * @property opponentName For cross-instance games: display name of the opposing team
 *   (e.g., "Bethesda Fire U12"). Shown in the mobile app since we can't look up
 *   the remote team locally.
 * @property opponentExternalRef For cross-instance games: the opposing team's UUID on
 *   their FieldIQ instance. Used for future cross-instance data resolution.
 * @property negotiationId Foreign key to the [NegotiationSession] that created this
 *   event, if it was created through the negotiation protocol. Null for manually
 *   created events. FK constraint added in V2 migration.
 * @property createdBy Foreign key to the [User] who created this event. Null if
 *   created by the system (e.g., via negotiation confirmation).
 * @property createdAt Timestamp of event creation, set once and never updated.
 * @see NegotiationSession for the negotiation that may have produced this event.
 * @see EventResponse for RSVP tracking on this event.
 */
@Entity
@Table(name = "events")
data class Event(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "team_id", nullable = false)
    val teamId: UUID,

    @Column(name = "event_type", nullable = false)
    val eventType: String,

    val title: String? = null,

    val location: String? = null,

    @Column(name = "location_notes")
    val locationNotes: String? = null,

    @Column(name = "starts_at")
    val startsAt: Instant? = null,

    @Column(name = "ends_at")
    val endsAt: Instant? = null,

    @Column(nullable = false)
    val status: String = "scheduled",

    @Column(name = "opponent_team_id")
    val opponentTeamId: UUID? = null,

    @Column(name = "opponent_name")
    val opponentName: String? = null,

    @Column(name = "opponent_external_ref")
    val opponentExternalRef: String? = null,

    @Column(name = "negotiation_id")
    val negotiationId: UUID? = null,

    @Column(name = "created_by")
    val createdBy: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
