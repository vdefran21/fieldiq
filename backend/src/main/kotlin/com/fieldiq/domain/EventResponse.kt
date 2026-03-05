package com.fieldiq.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Tracks a user's RSVP response to a team event.
 *
 * Each team member can respond exactly once per event (enforced by the unique
 * constraint on (eventId, userId)). Responses drive the attendance tracking UI
 * in the mobile app and feed into the CommunicationAgent for reminder drafting —
 * members who haven't responded get nudge notifications.
 *
 * The default status is "no_response", meaning the user has been invited but
 * hasn't acted yet. The [respondedAt] timestamp is null until the user explicitly
 * responds, distinguishing "hasn't seen it" from "chose not to answer."
 *
 * @property id Unique identifier, auto-generated UUID.
 * @property eventId Foreign key to the [Event] this response is for.
 * @property userId Foreign key to the [User] who responded.
 * @property status The user's RSVP status. One of: "going", "not_going", "maybe",
 *   "no_response". Enforced by CHECK constraint. Defaults to "no_response".
 * @property respondedAt Timestamp of when the user last changed their response.
 *   Null if [status] is still "no_response" (user hasn't interacted yet).
 *   Updated each time the user changes their RSVP.
 * @property createdAt Timestamp of when the response record was created (typically
 *   when the event is created and RSVP slots are initialized). Immutable.
 * @see Event for the event this response is associated with.
 * @see User for the user who submitted this response.
 */
@Entity
@Table(name = "event_responses")
data class EventResponse(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "event_id", nullable = false)
    val eventId: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(nullable = false)
    val status: String = "no_response",

    @Column(name = "responded_at")
    val respondedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
