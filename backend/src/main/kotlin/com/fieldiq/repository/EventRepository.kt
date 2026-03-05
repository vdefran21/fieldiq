package com.fieldiq.repository

import com.fieldiq.domain.Event
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * Spring Data JPA repository for [Event] entities.
 *
 * Provides queries for retrieving team schedules and event lists. Events are
 * created either manually by managers or automatically when a [com.fieldiq.domain.NegotiationSession]
 * reaches "confirmed" status. The `idx_events_team` composite index on
 * (team_id, starts_at) optimizes the most common query pattern: "show me
 * upcoming events for my team."
 *
 * @see Event for the entity managed by this repository.
 * @see com.fieldiq.domain.EventResponse for RSVP tracking on events.
 */
interface EventRepository : JpaRepository<Event, UUID> {

    /**
     * Finds upcoming events for a team, sorted chronologically.
     *
     * The primary query for the mobile app's schedule feed. Returns only events
     * with a start time after the given threshold, sorted ascending (nearest
     * event first). Uses the `idx_events_team` composite index for performance.
     *
     * Note: events with null [Event.startsAt] (draft/unscheduled) are excluded
     * because the `starts_at > after` comparison filters them out.
     *
     * @param teamId The UUID of the team.
     * @param after The cutoff timestamp — only events starting after this time
     *   are returned. Typically set to "now" for upcoming events.
     * @return Upcoming events sorted by start time ascending, empty list if none.
     */
    fun findByTeamIdAndStartsAtAfterOrderByStartsAtAsc(teamId: UUID, after: Instant): List<Event>

    /**
     * Finds all events for a team regardless of timing or status.
     *
     * Used for admin views and data export. Includes past events, drafts,
     * cancelled events — everything. No ordering guarantee; callers should
     * sort as needed.
     *
     * @param teamId The UUID of the team.
     * @return All events for the team, empty list if none exist.
     */
    fun findByTeamId(teamId: UUID): List<Event>
}
