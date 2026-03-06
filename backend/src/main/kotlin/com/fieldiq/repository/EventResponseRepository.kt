package com.fieldiq.repository

import com.fieldiq.domain.EventResponse
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [EventResponse] entities.
 *
 * Provides queries for RSVP tracking on team events. Each team member can respond
 * exactly once per event (UNIQUE constraint on event_id + user_id). Responses
 * drive the attendance tracking UI and feed the CommunicationAgent for reminder
 * drafting — members who haven't responded get nudge notifications.
 *
 * @see EventResponse for the entity managed by this repository.
 * @see com.fieldiq.domain.Event for the events that responses belong to.
 */
interface EventResponseRepository : JpaRepository<EventResponse, UUID> {

    /**
     * Finds all RSVP responses for an event.
     *
     * Used to display attendance tracking on the event detail screen. Returns
     * all responses including "no_response" entries (members who haven't acted).
     *
     * @param eventId The UUID of the event.
     * @return All responses for the event, empty list if none exist.
     */
    fun findByEventId(eventId: UUID): List<EventResponse>

    /**
     * Finds a specific user's RSVP response for an event.
     *
     * Used during RSVP submission to implement upsert behavior — if the user
     * has already responded, update their existing response rather than creating
     * a duplicate. The UNIQUE constraint on (event_id, user_id) enforces this.
     *
     * @param eventId The UUID of the event.
     * @param userId The UUID of the responding user.
     * @return The user's response, or null if they haven't responded yet.
     */
    fun findByEventIdAndUserId(eventId: UUID, userId: UUID): EventResponse?
}
