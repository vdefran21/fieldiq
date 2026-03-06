package com.fieldiq.repository

import com.fieldiq.domain.Event
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
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

    /**
     * Finds scheduled or draft events that overlap with a given time range.
     *
     * Used by [com.fieldiq.service.SchedulingService] to detect conflicts when
     * computing available windows. An event overlaps the range if it starts before
     * the range ends AND ends after the range starts. Only "scheduled" and "draft"
     * events are considered — cancelled/completed events are ignored.
     *
     * Events with null [Event.startsAt] or [Event.endsAt] (unscheduled drafts)
     * are excluded since they don't occupy a definite time slot.
     *
     * @param teamId The UUID of the team.
     * @param rangeStart Start of the time range (inclusive).
     * @param rangeEnd End of the time range (exclusive).
     * @return Events that overlap with the specified range, empty list if none.
     */
    @Query(
        """
        SELECT e FROM Event e
        WHERE e.teamId = :teamId
          AND e.startsAt < :rangeEnd
          AND e.endsAt > :rangeStart
          AND e.status IN ('scheduled', 'draft')
        """
    )
    fun findOverlappingEvents(teamId: UUID, rangeStart: Instant, rangeEnd: Instant): List<Event>
}
