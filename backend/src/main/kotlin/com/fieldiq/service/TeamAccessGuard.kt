package com.fieldiq.service

import com.fieldiq.domain.TeamMember
import com.fieldiq.repository.TeamMemberRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Enforces multi-tenancy access control for all team-scoped operations.
 *
 * This is the primary security boundary in FieldIQ's Phase 1 architecture. Every
 * controller method that accesses team resources MUST call one of this guard's
 * methods before proceeding. This is enforced by convention (not by AOP or
 * interceptors) — code review should flag any controller that accesses team data
 * without a guard check.
 *
 * The guard queries [TeamMemberRepository] to verify that the authenticated user
 * has an active membership on the target team, and optionally that they hold the
 * required role (e.g., "manager" for write operations).
 *
 * **Why service-layer guards instead of Postgres RLS?**
 * Phase 1 uses service-layer enforcement because the schema is still evolving.
 * Postgres Row Level Security (RLS) is planned for Phase 2 hardening once the
 * schema stabilizes and performance characteristics are better understood.
 *
 * Usage example in a controller:
 * ```kotlin
 * @GetMapping("/teams/{teamId}/events")
 * fun getEvents(@PathVariable teamId: UUID, @AuthenticationPrincipal userId: UUID): List<EventDto> {
 *     teamAccessGuard.requireActiveMember(userId, teamId)
 *     return eventService.getUpcomingEvents(teamId)
 * }
 * ```
 *
 * @property teamMemberRepository Repository for querying team membership records.
 * @see TeamMember for the membership entity that backs access decisions.
 * @see TeamMemberRepository for the queries used by this guard.
 */
@Service
class TeamAccessGuard(
    private val teamMemberRepository: TeamMemberRepository
) {

    /**
     * Verifies that a user is an active member of the specified team.
     *
     * This is the minimum access check — required before any read operation on
     * team resources (events, roster, availability, etc.). Deactivated members
     * (isActive=false) are rejected.
     *
     * @param userId The UUID of the authenticated user.
     * @param teamId The UUID of the team being accessed.
     * @return The [TeamMember] record, which callers can use to check the user's
     *   role without an additional DB query.
     * @throws AccessDeniedException If the user has no active membership on the team.
     *   This is caught by Spring Security's exception handling and results in a 403 response.
     */
    fun requireActiveMember(userId: UUID, teamId: UUID): TeamMember {
        return teamMemberRepository.findByUserIdAndTeamIdAndIsActiveTrue(userId, teamId)
            ?: throw AccessDeniedException("User $userId is not an active member of team $teamId")
    }

    /**
     * Verifies that a user is an active manager of the specified team.
     *
     * Required before write operations that only managers can perform: initiating
     * negotiations, managing the roster, creating/editing events, changing team
     * settings. Delegates to [requireActiveMember] first, then checks the role.
     *
     * @param userId The UUID of the authenticated user.
     * @param teamId The UUID of the team being accessed.
     * @return The [TeamMember] record with role "manager".
     * @throws AccessDeniedException If the user is not an active member, or is a member
     *   but does not have the "manager" role.
     */
    fun requireManager(userId: UUID, teamId: UUID): TeamMember {
        val member = requireActiveMember(userId, teamId)
        if (member.role != "manager") {
            throw AccessDeniedException("User $userId is not a manager of team $teamId")
        }
        return member
    }
}
