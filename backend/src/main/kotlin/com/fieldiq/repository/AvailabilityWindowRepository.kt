package com.fieldiq.repository

import com.fieldiq.domain.AvailabilityWindow
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [AvailabilityWindow] entities.
 *
 * Provides queries for retrieving availability data that feeds into
 * SchedulingService. Availability windows are created by users via the mobile
 * app (manual entry) or by the agent layer's calendar sync worker (Google Calendar
 * busy blocks converted to "unavailable" windows).
 *
 * Indexed by team_id and user_id for the two primary query patterns:
 * - Team-wide aggregation (SchedulingService needs all members' availability)
 * - Per-user management (user views/edits their own availability)
 *
 * @see AvailabilityWindow for the entity managed by this repository.
 */
interface AvailabilityWindowRepository : JpaRepository<AvailabilityWindow, UUID> {

    /**
     * Finds all availability windows for a team across all members.
     *
     * Primary query for SchedulingService.findAvailableWindows — aggregates every
     * team member's availability to compute team-wide free/busy blocks. Returns both
     * "available" and "unavailable" windows; the service layer handles subtraction.
     *
     * @param teamId The UUID of the team.
     * @return All availability windows for the team, empty list if none declared.
     */
    fun findByTeamId(teamId: UUID): List<AvailabilityWindow>

    /**
     * Finds all availability windows declared by a specific user across all teams.
     *
     * Used in the mobile app's personal availability management screen. A user
     * might have different availability for different teams (e.g., available
     * Saturday mornings for U10 but not for U14).
     *
     * @param userId The UUID of the user.
     * @return All availability windows for the user, empty list if none declared.
     */
    fun findByUserId(userId: UUID): List<AvailabilityWindow>

    /**
     * Finds availability windows for a specific user on a specific team.
     *
     * Used when a user wants to view or edit their availability for one particular
     * team. Also used by the calendar sync worker to find and replace Google Calendar
     * windows for a user-team combination.
     *
     * @param teamId The UUID of the team.
     * @param userId The UUID of the user.
     * @return Availability windows for this user-team combination, empty list if none.
     */
    fun findByTeamIdAndUserId(teamId: UUID, userId: UUID): List<AvailabilityWindow>
}
