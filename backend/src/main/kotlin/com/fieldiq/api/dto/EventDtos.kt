package com.fieldiq.api.dto

import com.fieldiq.domain.Event
import com.fieldiq.domain.EventResponse
import com.fieldiq.domain.User
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * Team event data returned in API responses.
 *
 * Covers games, practices, tournaments, and other scheduled activities.
 *
 * Corresponds to TypeScript interface: `EventDto` in `shared/types/index.ts`.
 * Corresponds to Kotlin entity: [com.fieldiq.domain.Event].
 *
 * @property id UUID of the event.
 * @property teamId UUID of the team this event belongs to.
 * @property eventType Type of event: "game", "practice", "tournament", "other".
 * @property title Display title (e.g., "vs Arlington United").
 * @property location Venue name or address.
 * @property locationNotes Additional directions (e.g., "Park in Lot B").
 * @property startsAt Scheduled start time in ISO 8601 UTC format. Null for unscheduled events.
 * @property endsAt Scheduled end time in ISO 8601 UTC format.
 * @property status Lifecycle state: "draft", "scheduled", "cancelled", "completed".
 * @property opponentName Opponent team name (for games).
 * @property negotiationId UUID of the negotiation session that created this event, if any.
 */
data class EventDto(
    val id: String,
    val teamId: String,
    val eventType: String,
    val title: String? = null,
    val location: String? = null,
    val locationNotes: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val status: String,
    val opponentName: String? = null,
    val negotiationId: String? = null,
) {
    companion object {
        /**
         * Converts an [Event] entity to an [EventDto] response object.
         *
         * @param event The event entity to convert.
         * @return An [EventDto] with all fields mapped. Instant fields are converted to ISO 8601 strings.
         */
        fun from(event: Event): EventDto = EventDto(
            id = event.id.toString(),
            teamId = event.teamId.toString(),
            eventType = event.eventType,
            title = event.title,
            location = event.location,
            locationNotes = event.locationNotes,
            startsAt = event.startsAt?.toString(),
            endsAt = event.endsAt?.toString(),
            status = event.status,
            opponentName = event.opponentName,
            negotiationId = event.negotiationId?.toString(),
        )
    }
}

/**
 * Request body for `POST /teams/:teamId/events`.
 *
 * Creates a new event on the team's schedule. Requires manager or coach role.
 *
 * Corresponds to TypeScript interface: `CreateEventRequest` in `shared/types/index.ts`.
 *
 * @property eventType Type of event to create: "game", "practice", "tournament", "other".
 * @property title Display title.
 * @property location Venue name or address.
 * @property locationNotes Additional directions or notes.
 * @property startsAt Start time in ISO 8601 UTC format. Null creates a draft event.
 * @property endsAt End time in ISO 8601 UTC format.
 */
data class CreateEventRequest(
    @field:NotBlank(message = "Event type is required")
    @field:Pattern(
        regexp = "game|practice|tournament|other",
        message = "Event type must be 'game', 'practice', 'tournament', or 'other'"
    )
    val eventType: String,
    val title: String? = null,
    val location: String? = null,
    val locationNotes: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
)

/**
 * Request body for `PATCH /events/:eventId`.
 *
 * Partial update for an existing event. All fields are optional — only provided
 * fields are updated. Requires manager or coach role.
 *
 * @property title Updated display title.
 * @property location Updated venue.
 * @property locationNotes Updated directions.
 * @property startsAt Updated start time in ISO 8601 UTC format.
 * @property endsAt Updated end time in ISO 8601 UTC format.
 * @property status Updated lifecycle state.
 */
data class UpdateEventRequest(
    val title: String? = null,
    val location: String? = null,
    val locationNotes: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val status: String? = null,
)

/**
 * Request body for `POST /events/:eventId/respond`.
 *
 * Submits or updates a user's RSVP for an event.
 *
 * Corresponds to TypeScript interface: `RespondToEventRequest` in `shared/types/index.ts`.
 *
 * @property status The RSVP status to set: "going", "not_going", or "maybe".
 */
data class RespondToEventRequest(
    @field:NotBlank(message = "Status is required")
    @field:Pattern(
        regexp = "going|not_going|maybe",
        message = "Status must be 'going', 'not_going', or 'maybe'"
    )
    val status: String,
)

/**
 * RSVP response data returned in API responses.
 *
 * Tracks whether a team member is attending an event.
 *
 * Corresponds to TypeScript interface: `EventResponseDto` in `shared/types/index.ts`.
 * Corresponds to Kotlin entity: [com.fieldiq.domain.EventResponse].
 *
 * @property id UUID of the response record.
 * @property eventId UUID of the event.
 * @property userId UUID of the user who responded.
 * @property status The user's RSVP status.
 * @property respondedAt When the user last changed their response. Null if they haven't responded.
 * @property user Expanded user profile, included when the API joins user data.
 */
data class EventResponseDto(
    val id: String,
    val eventId: String,
    val userId: String,
    val status: String,
    val respondedAt: String? = null,
    val user: UserDto? = null,
) {
    companion object {
        /**
         * Converts an [EventResponse] entity to an [EventResponseDto] response object.
         *
         * @param response The event response entity to convert.
         * @param user Optional [User] entity to expand in the response.
         * @return An [EventResponseDto] with all fields mapped.
         */
        fun from(response: EventResponse, user: User? = null): EventResponseDto = EventResponseDto(
            id = response.id.toString(),
            eventId = response.eventId.toString(),
            userId = response.userId.toString(),
            status = response.status,
            respondedAt = response.respondedAt?.toString(),
            user = user?.let { UserDto.from(it) },
        )
    }
}
