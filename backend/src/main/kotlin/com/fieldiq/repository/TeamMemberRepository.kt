package com.fieldiq.repository

import com.fieldiq.domain.TeamMember
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [TeamMember] entities.
 *
 * This is the most security-critical repository in FieldIQ — it backs the
 * [com.fieldiq.service.TeamAccessGuard] that enforces multi-tenancy. Every team
 * resource access ultimately queries this table to verify the requesting user is
 * an active member with the appropriate role.
 *
 * All queries filter by `isActive = true` to respect soft-deletes. Deactivated
 * members still exist in the database for audit purposes but are invisible to
 * access control checks.
 *
 * @see TeamMember for the entity managed by this repository.
 * @see com.fieldiq.service.TeamAccessGuard for the service that uses this repository.
 */
interface TeamMemberRepository : JpaRepository<TeamMember, UUID> {

    /**
     * Checks if a user is an active member of a specific team.
     *
     * This is the core access control query — called by [com.fieldiq.service.TeamAccessGuard.requireActiveMember]
     * before every team resource access. The combination of userId + teamId + isActive
     * is covered by the unique constraint on (team_id, user_id) plus the isActive filter.
     *
     * @param userId The UUID of the user to check.
     * @param teamId The UUID of the team to check membership for.
     * @return The active [TeamMember] record, or null if the user is not an active
     *   member of the team (either no record exists, or the record has isActive=false).
     */
    fun findByUserIdAndTeamIdAndIsActiveTrue(userId: UUID, teamId: UUID): TeamMember?

    /**
     * Lists all active members of a team.
     *
     * Used for roster display, availability aggregation, and notification dispatch.
     * Returns members of all roles (manager, coach, parent).
     *
     * @param teamId The UUID of the team.
     * @return All active members of the team, empty list if the team has no active members.
     */
    fun findByTeamIdAndIsActiveTrue(teamId: UUID): List<TeamMember>

    /**
     * Lists all teams a user is an active member of.
     *
     * Used to populate the team selector in the mobile app and to determine
     * which teams a user can access after authentication.
     *
     * @param userId The UUID of the user.
     * @return All active memberships for the user across all teams.
     */
    fun findByUserIdAndIsActiveTrue(userId: UUID): List<TeamMember>
}
