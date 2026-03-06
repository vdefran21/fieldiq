package com.fieldiq.service

import com.fieldiq.api.dto.CreateEventRequest
import com.fieldiq.api.dto.EventDto
import com.fieldiq.api.dto.EventResponseDto
import com.fieldiq.api.dto.RespondToEventRequest
import com.fieldiq.api.dto.UpdateEventRequest
import com.fieldiq.domain.Event
import com.fieldiq.domain.EventResponse
import com.fieldiq.repository.EventRepository
import com.fieldiq.repository.EventResponseRepository
import com.fieldiq.repository.UserRepository
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Business logic for event management and RSVP tracking.
 *
 * Handles the full event lifecycle: creation by managers, partial updates,
 * RSVP submissions by team members, and attendance queries. All operations
 * enforce multi-tenancy via [TeamAccessGuard] — only active team members can
 * view or interact with events, and only managers can create or modify them.
 *
 * Events can be created manually or via the negotiation protocol. This service
 * handles manual CRUD only; negotiation-created events are managed by
 * [com.fieldiq.service.NegotiationService] (Sprint 4).
 *
 * **RSVP upsert pattern:** When a user responds to an event, the service checks
 * for an existing response and updates it rather than creating a duplicate. This
 * is backed by a UNIQUE constraint on (event_id, user_id) in the database.
 *
 * **Database impact:** Writes to `events` and `event_responses` tables. All write
 * operations are wrapped in `@Transactional`.
 *
 * @property eventRepository Repository for [Event] entity CRUD and queries.
 * @property eventResponseRepository Repository for [EventResponse] RSVP queries.
 * @property userRepository Repository for [com.fieldiq.domain.User] lookups (to expand responder profiles).
 * @property teamAccessGuard Multi-tenancy guard for membership and role checks.
 * @see com.fieldiq.api.EventController for the REST endpoints that delegate to this service.
 * @see TeamAccessGuard for access control enforcement.
 */
@Service
class EventService(
    private val eventRepository: EventRepository,
    private val eventResponseRepository: EventResponseRepository,
    private val userRepository: UserRepository,
    private val teamAccessGuard: TeamAccessGuard,
) {
    private val logger = LoggerFactory.getLogger(EventService::class.java)

    /**
     * Creates a new event on a team's schedule.
     *
     * Only managers can create events. The event starts in "scheduled" status by
     * default (or "draft" if no start time is provided). The creator is recorded
     * in [Event.createdBy] for audit purposes.
     *
     * @param userId The UUID of the authenticated user creating the event (must be a manager).
     * @param teamId The UUID of the team to create the event on.
     * @param request The event details: type, title, location, times, etc.
     * @return An [EventDto] representing the newly created event.
     * @throws org.springframework.security.access.AccessDeniedException If the user is not a manager.
     */
    @Transactional
    fun createEvent(userId: UUID, teamId: UUID, request: CreateEventRequest): EventDto {
        teamAccessGuard.requireManager(userId, teamId)

        val startsAt = request.startsAt?.let { Instant.parse(it) }
        val endsAt = request.endsAt?.let { Instant.parse(it) }
        val status = if (startsAt != null) "scheduled" else "draft"

        val event = eventRepository.save(
            Event(
                teamId = teamId,
                eventType = request.eventType,
                title = request.title,
                location = request.location,
                locationNotes = request.locationNotes,
                startsAt = startsAt,
                endsAt = endsAt,
                status = status,
                createdBy = userId,
            )
        )

        logger.info("Event '{}' ({}) created on team {} by user {}", event.title, event.eventType, teamId, userId)
        return EventDto.from(event)
    }

    /**
     * Lists upcoming events for a team.
     *
     * Returns events with a start time after the current moment, sorted
     * chronologically (nearest event first). Draft events (no start time) and
     * past events are excluded. Requires the requesting user to be an active
     * team member.
     *
     * @param userId The UUID of the authenticated user requesting events.
     * @param teamId The UUID of the team whose events to list.
     * @return A list of [EventDto] for upcoming events, sorted by start time ascending.
     * @throws org.springframework.security.access.AccessDeniedException If the user is not an active member.
     */
    fun getTeamEvents(userId: UUID, teamId: UUID): List<EventDto> {
        teamAccessGuard.requireActiveMember(userId, teamId)
        val events = eventRepository.findByTeamIdAndStartsAtAfterOrderByStartsAtAsc(teamId, Instant.now())
        return events.map { EventDto.from(it) }
    }

    /**
     * Partially updates an existing event.
     *
     * Only managers of the event's team can update events. Fields not provided in
     * the request are left unchanged (PATCH semantics). The event is looked up by
     * ID and the team access check is performed against the event's owning team.
     *
     * @param userId The UUID of the authenticated user updating the event (must be a manager).
     * @param eventId The UUID of the event to update.
     * @param request The fields to update. All fields are optional.
     * @return An [EventDto] with the updated event details.
     * @throws EntityNotFoundException If the event does not exist.
     * @throws org.springframework.security.access.AccessDeniedException If the user is not a manager of the event's team.
     */
    @Transactional
    fun updateEvent(userId: UUID, eventId: UUID, request: UpdateEventRequest): EventDto {
        val event = eventRepository.findById(eventId)
            .orElseThrow { EntityNotFoundException("Event $eventId not found") }

        teamAccessGuard.requireManager(userId, event.teamId)

        val updated = event.copy(
            title = request.title ?: event.title,
            location = request.location ?: event.location,
            locationNotes = request.locationNotes ?: event.locationNotes,
            startsAt = request.startsAt?.let { Instant.parse(it) } ?: event.startsAt,
            endsAt = request.endsAt?.let { Instant.parse(it) } ?: event.endsAt,
            status = request.status ?: event.status,
        )

        val saved = eventRepository.save(updated)
        logger.info("Event {} updated by user {}", eventId, userId)
        return EventDto.from(saved)
    }

    /**
     * Submits or updates a user's RSVP response for an event.
     *
     * Implements upsert behavior: if the user has already responded, their existing
     * response is updated. If not, a new response is created. The [EventResponse.respondedAt]
     * timestamp is set to the current time on every update.
     *
     * Requires the user to be an active member of the event's team.
     *
     * @param userId The UUID of the authenticated user responding.
     * @param eventId The UUID of the event to respond to.
     * @param request The RSVP status: "going", "not_going", or "maybe".
     * @return An [EventResponseDto] with the user's updated response.
     * @throws EntityNotFoundException If the event does not exist.
     * @throws org.springframework.security.access.AccessDeniedException If the user is not a member of the event's team.
     */
    @Transactional
    fun respondToEvent(userId: UUID, eventId: UUID, request: RespondToEventRequest): EventResponseDto {
        val event = eventRepository.findById(eventId)
            .orElseThrow { EntityNotFoundException("Event $eventId not found") }

        teamAccessGuard.requireActiveMember(userId, event.teamId)

        val existing = eventResponseRepository.findByEventIdAndUserId(eventId, userId)
        val response = if (existing != null) {
            eventResponseRepository.save(
                existing.copy(
                    status = request.status,
                    respondedAt = Instant.now(),
                )
            )
        } else {
            eventResponseRepository.save(
                EventResponse(
                    eventId = eventId,
                    userId = userId,
                    status = request.status,
                    respondedAt = Instant.now(),
                )
            )
        }

        val user = userRepository.findById(userId).orElse(null)
        logger.info("User {} responded '{}' to event {}", userId, request.status, eventId)
        return EventResponseDto.from(response, user)
    }

    /**
     * Lists all RSVP responses for an event with expanded user profiles.
     *
     * Returns every response including "no_response" entries (members who haven't
     * acted yet). Each response includes the user's profile data for display in
     * the attendance tracking UI.
     *
     * Requires the user to be an active member of the event's team.
     *
     * @param userId The UUID of the authenticated user requesting responses.
     * @param eventId The UUID of the event whose responses to list.
     * @return A list of [EventResponseDto] with expanded user profiles.
     * @throws EntityNotFoundException If the event does not exist.
     * @throws org.springframework.security.access.AccessDeniedException If the user is not a member of the event's team.
     */
    fun getEventResponses(userId: UUID, eventId: UUID): List<EventResponseDto> {
        val event = eventRepository.findById(eventId)
            .orElseThrow { EntityNotFoundException("Event $eventId not found") }

        teamAccessGuard.requireActiveMember(userId, event.teamId)

        val responses = eventResponseRepository.findByEventId(eventId)
        val userIds = responses.map { it.userId }
        val usersById = userRepository.findAllById(userIds).associateBy { it.id }

        return responses.map { response ->
            EventResponseDto.from(response, usersById[response.userId])
        }
    }
}
