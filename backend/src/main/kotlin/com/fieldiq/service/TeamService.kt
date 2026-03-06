package com.fieldiq.service

import com.fieldiq.api.dto.AddTeamMemberRequest
import com.fieldiq.api.dto.CreateTeamRequest
import com.fieldiq.api.dto.TeamDto
import com.fieldiq.api.dto.TeamMemberDto
import com.fieldiq.domain.Team
import com.fieldiq.domain.TeamMember
import com.fieldiq.repository.TeamMemberRepository
import com.fieldiq.repository.TeamRepository
import com.fieldiq.repository.UserRepository
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Business logic for team and team member management.
 *
 * Handles team creation (with automatic manager membership for the creator),
 * team lookups (scoped through [TeamAccessGuard] for multi-tenancy), and roster
 * management (adding members with role enforcement).
 *
 * All team-scoped operations first verify the caller's membership and role via
 * [TeamAccessGuard], ensuring that users can only access teams they belong to
 * and only perform actions their role permits.
 *
 * **Database impact:** This service writes to the `teams` and `team_members` tables.
 * All write operations are wrapped in `@Transactional` to ensure atomicity (e.g.,
 * team creation + manager membership happen together or not at all).
 *
 * @property teamRepository Repository for [Team] entity CRUD.
 * @property teamMemberRepository Repository for [TeamMember] entity CRUD and queries.
 * @property userRepository Repository for [com.fieldiq.domain.User] lookups (to expand member profiles).
 * @property teamAccessGuard Multi-tenancy guard that enforces membership and role checks.
 * @see TeamAccessGuard for access control enforcement.
 * @see com.fieldiq.api.TeamController for the REST endpoints that delegate to this service.
 */
@Service
class TeamService(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val userRepository: UserRepository,
    private val teamAccessGuard: TeamAccessGuard,
) {
    private val logger = LoggerFactory.getLogger(TeamService::class.java)

    /**
     * Creates a new team and assigns the creator as its first manager.
     *
     * The team creation and the initial manager membership are performed atomically
     * in a single transaction. If either fails, the entire operation rolls back.
     *
     * Every team must have at least one manager. This method guarantees that invariant
     * by creating the manager membership in the same transaction as the team.
     *
     * @param userId The UUID of the authenticated user who is creating the team.
     *   This user will become the team's first manager.
     * @param request The team creation details including name, sport, age group, etc.
     * @return A [TeamDto] representing the newly created team.
     * @see TeamAccessGuard.requireManager for how manager permissions are checked on subsequent operations.
     */
    @Transactional
    fun createTeam(userId: UUID, request: CreateTeamRequest): TeamDto {
        val team = teamRepository.save(
            Team(
                name = request.name,
                orgId = request.orgId?.let { UUID.fromString(it) },
                sport = request.sport ?: "soccer",
                ageGroup = request.ageGroup,
                season = request.season,
            )
        )

        teamMemberRepository.save(
            TeamMember(
                teamId = team.id,
                userId = userId,
                role = "manager",
            )
        )

        logger.info("Team '{}' created by user {} with id {}", team.name, userId, team.id)
        return TeamDto.from(team)
    }

    /**
     * Retrieves a single team by its ID.
     *
     * Requires the requesting user to be an active member of the team.
     * Non-members receive a 403 Forbidden response.
     *
     * @param userId The UUID of the authenticated user requesting the team.
     * @param teamId The UUID of the team to retrieve.
     * @return A [TeamDto] with the team's details.
     * @throws org.springframework.security.access.AccessDeniedException If the user is not an active member.
     * @throws EntityNotFoundException If the team does not exist.
     */
    fun getTeam(userId: UUID, teamId: UUID): TeamDto {
        teamAccessGuard.requireActiveMember(userId, teamId)
        val team = teamRepository.findById(teamId)
            .orElseThrow { EntityNotFoundException("Team $teamId not found") }
        return TeamDto.from(team)
    }

    /**
     * Lists all teams the authenticated user is an active member of.
     *
     * Populates the team selector in the mobile app after login. This query does
     * NOT require a team-specific access check since it returns only teams the user
     * already belongs to. The result includes teams where the user has any role
     * (manager, coach, or parent).
     *
     * @param userId The UUID of the authenticated user.
     * @return A list of [TeamDto] for all teams the user is an active member of.
     *   Returns an empty list if the user has no active memberships.
     */
    fun getMyTeams(userId: UUID): List<TeamDto> {
        val memberships = teamMemberRepository.findByUserIdAndIsActiveTrue(userId)
        val teamIds = memberships.map { it.teamId }
        if (teamIds.isEmpty()) return emptyList()

        val teams = teamRepository.findAllById(teamIds)
        return teams.map { TeamDto.from(it) }
    }

    /**
     * Adds a new member to a team with a specified role.
     *
     * Only team managers can add members. The target user must already exist in the
     * system (they must have logged in at least once via OTP). The method checks
     * for existing active membership to prevent duplicate entries.
     *
     * @param userId The UUID of the authenticated user performing the add (must be a manager).
     * @param teamId The UUID of the team to add the member to.
     * @param request The member details including target user ID, role, and optional player name.
     * @return A [TeamMemberDto] representing the newly created membership, with expanded user profile.
     * @throws org.springframework.security.access.AccessDeniedException If the requesting user is not a manager.
     * @throws EntityNotFoundException If the target user does not exist.
     * @throws IllegalArgumentException If the target user is already an active member of the team.
     */
    @Transactional
    fun addMember(userId: UUID, teamId: UUID, request: AddTeamMemberRequest): TeamMemberDto {
        teamAccessGuard.requireManager(userId, teamId)

        val targetUserId = UUID.fromString(request.userId)
        val targetUser = userRepository.findById(targetUserId)
            .orElseThrow { EntityNotFoundException("User ${request.userId} not found") }

        // Check for existing active membership
        val existing = teamMemberRepository.findByUserIdAndTeamIdAndIsActiveTrue(targetUserId, teamId)
        if (existing != null) {
            throw IllegalArgumentException("User ${request.userId} is already an active member of team $teamId")
        }

        val member = teamMemberRepository.save(
            TeamMember(
                teamId = teamId,
                userId = targetUserId,
                role = request.role,
                playerName = request.playerName,
            )
        )

        logger.info("User {} added to team {} with role '{}' by manager {}", targetUserId, teamId, request.role, userId)
        return TeamMemberDto.from(member, targetUser)
    }

    /**
     * Lists all active members of a team with expanded user profiles.
     *
     * Requires the requesting user to be an active member of the team.
     * Returns members of all roles (manager, coach, parent) with their
     * user profile data (display name, phone, email) included.
     *
     * The member list is used for roster display, role management, and
     * selecting users for event notifications.
     *
     * @param userId The UUID of the authenticated user requesting the roster.
     * @param teamId The UUID of the team whose members to list.
     * @return A list of [TeamMemberDto] with expanded user profiles for all active members.
     * @throws org.springframework.security.access.AccessDeniedException If the user is not an active member.
     */
    fun getMembers(userId: UUID, teamId: UUID): List<TeamMemberDto> {
        teamAccessGuard.requireActiveMember(userId, teamId)

        val members = teamMemberRepository.findByTeamIdAndIsActiveTrue(teamId)
        val userIds = members.map { it.userId }
        val usersById = userRepository.findAllById(userIds).associateBy { it.id }

        return members.map { member ->
            TeamMemberDto.from(member, usersById[member.userId])
        }
    }
}
