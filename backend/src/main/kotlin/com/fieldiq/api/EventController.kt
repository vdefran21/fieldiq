package com.fieldiq.api

import com.fieldiq.api.dto.CreateEventRequest
import com.fieldiq.api.dto.EventDto
import com.fieldiq.api.dto.EventResponseDto
import com.fieldiq.api.dto.RespondToEventRequest
import com.fieldiq.api.dto.UpdateEventRequest
import com.fieldiq.security.authenticatedUserId
import com.fieldiq.service.EventService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST controller for event management and RSVP tracking.
 *
 * Provides CRUD for team events and RSVP submission/querying. All endpoints
 * require JWT authentication. Team-scoped operations are protected by
 * [com.fieldiq.service.TeamAccessGuard] via the [EventService].
 *
 * Endpoints are split across two URL patterns:
 * - `/teams/{teamId}/events` — Team-scoped event creation and listing
 * - `/events/{eventId}` — Event-scoped updates, RSVP, and response queries
 *
 * This split allows event updates and RSVP submissions without requiring the
 * team ID in the URL — the event's owning team is resolved from the database.
 *
 * @property eventService Business logic for event and RSVP operations.
 * @see EventService for the underlying business logic.
 * @see com.fieldiq.service.TeamAccessGuard for multi-tenancy enforcement.
 */
@RestController
class EventController(
    private val eventService: EventService,
) {

    /**
     * Creates a new event on a team's schedule.
     *
     * Only team managers can create events. Events without a start time are
     * created as drafts; events with a start time are immediately scheduled.
     *
     * @param teamId The UUID of the team to create the event on.
     * @param request The event details: type (required), title, location, times.
     * @return 201 Created with the [EventDto] of the new event.
     */
    @PostMapping("/teams/{teamId}/events")
    fun createEvent(
        @PathVariable teamId: UUID,
        @Valid @RequestBody request: CreateEventRequest,
    ): ResponseEntity<EventDto> {
        val userId = authenticatedUserId()
        val event = eventService.createEvent(userId, teamId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(event)
    }

    /**
     * Lists upcoming events for a team.
     *
     * Returns events starting after the current time, sorted chronologically.
     * Requires the authenticated user to be an active team member. Draft events
     * (no start time) and past events are excluded.
     *
     * @param teamId The UUID of the team whose events to list.
     * @return 200 OK with a list of [EventDto] sorted by start time ascending.
     */
    @GetMapping("/teams/{teamId}/events")
    fun getTeamEvents(@PathVariable teamId: UUID): ResponseEntity<List<EventDto>> {
        val userId = authenticatedUserId()
        val events = eventService.getTeamEvents(userId, teamId)
        return ResponseEntity.ok(events)
    }

    /**
     * Partially updates an existing event.
     *
     * PATCH semantics: only provided fields are updated; omitted fields keep
     * their current values. Requires the authenticated user to be a manager
     * of the event's owning team.
     *
     * @param eventId The UUID of the event to update.
     * @param request The fields to update (all optional).
     * @return 200 OK with the updated [EventDto].
     */
    @PatchMapping("/events/{eventId}")
    fun updateEvent(
        @PathVariable eventId: UUID,
        @RequestBody request: UpdateEventRequest,
    ): ResponseEntity<EventDto> {
        val userId = authenticatedUserId()
        val event = eventService.updateEvent(userId, eventId, request)
        return ResponseEntity.ok(event)
    }

    /**
     * Submits or updates a user's RSVP for an event.
     *
     * Upsert behavior: if the user has already responded, their response is
     * updated. If not, a new response is created. Requires the user to be an
     * active member of the event's team.
     *
     * @param eventId The UUID of the event to respond to.
     * @param request The RSVP status: "going", "not_going", or "maybe".
     * @return 200 OK with the [EventResponseDto] reflecting the user's response.
     */
    @PostMapping("/events/{eventId}/respond")
    fun respondToEvent(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: RespondToEventRequest,
    ): ResponseEntity<EventResponseDto> {
        val userId = authenticatedUserId()
        val response = eventService.respondToEvent(userId, eventId, request)
        return ResponseEntity.ok(response)
    }

    /**
     * Lists all RSVP responses for an event.
     *
     * Returns every response including "no_response" entries, each with the
     * user's profile data. Requires the authenticated user to be an active
     * member of the event's team.
     *
     * @param eventId The UUID of the event whose responses to list.
     * @return 200 OK with a list of [EventResponseDto] with expanded user profiles.
     */
    @GetMapping("/events/{eventId}/responses")
    fun getEventResponses(@PathVariable eventId: UUID): ResponseEntity<List<EventResponseDto>> {
        val userId = authenticatedUserId()
        val responses = eventService.getEventResponses(userId, eventId)
        return ResponseEntity.ok(responses)
    }
}
