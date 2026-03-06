package com.fieldiq.api

import com.fieldiq.api.dto.AddTeamMemberRequest
import com.fieldiq.api.dto.CreateTeamRequest
import com.fieldiq.api.dto.TeamDto
import com.fieldiq.api.dto.TeamMemberDto
import com.fieldiq.security.authenticatedUserId
import com.fieldiq.service.TeamService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST controller for team and team member management.
 *
 * Provides CRUD operations for teams and their rosters. All endpoints require
 * JWT authentication. Team-scoped operations are protected by [com.fieldiq.service.TeamAccessGuard]
 * via the [TeamService] — non-members receive 403 Forbidden.
 *
 * Endpoints:
 * - `POST /teams` — Create a new team (creator becomes manager)
 * - `GET /teams` — List all teams the authenticated user belongs to
 * - `GET /teams/{teamId}` — Get a single team's details
 * - `POST /teams/{teamId}/members` — Add a member to a team (manager only)
 * - `GET /teams/{teamId}/members` — List all active members of a team
 *
 * The authenticated user's UUID is extracted from the JWT via [authenticatedUserId].
 *
 * @property teamService Business logic for team and member operations.
 * @see TeamService for the underlying business logic.
 * @see com.fieldiq.service.TeamAccessGuard for multi-tenancy enforcement.
 */
@RestController
@RequestMapping("/teams")
class TeamController(
    private val teamService: TeamService,
) {

    /**
     * Creates a new team with the authenticated user as the initial manager.
     *
     * Every team must have at least one manager. The creator is automatically assigned
     * the "manager" role in the same transaction as team creation. After creation,
     * the team appears in the creator's team list.
     *
     * @param request The team creation details: name (required), sport, age group, season.
     * @return 201 Created with the [TeamDto] of the new team.
     */
    @PostMapping
    fun createTeam(@Valid @RequestBody request: CreateTeamRequest): ResponseEntity<TeamDto> {
        val userId = authenticatedUserId()
        val team = teamService.createTeam(userId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(team)
    }

    /**
     * Lists all teams the authenticated user is an active member of.
     *
     * Used to populate the team selector in the mobile app. Returns teams where
     * the user has any role (manager, coach, parent). No team-specific access
     * check is needed since the query is inherently scoped to the user's memberships.
     *
     * @return 200 OK with a list of [TeamDto]. Empty list if the user has no teams.
     */
    @GetMapping
    fun getMyTeams(): ResponseEntity<List<TeamDto>> {
        val userId = authenticatedUserId()
        val teams = teamService.getMyTeams(userId)
        return ResponseEntity.ok(teams)
    }

    /**
     * Retrieves a single team's details.
     *
     * Requires the authenticated user to be an active member of the team.
     * Returns 403 if not a member, 404 if the team doesn't exist.
     *
     * @param teamId The UUID of the team to retrieve.
     * @return 200 OK with the [TeamDto].
     */
    @GetMapping("/{teamId}")
    fun getTeam(@PathVariable teamId: UUID): ResponseEntity<TeamDto> {
        val userId = authenticatedUserId()
        val team = teamService.getTeam(userId, teamId)
        return ResponseEntity.ok(team)
    }

    /**
     * Adds a new member to a team.
     *
     * Only team managers can add members. The target user must already exist in the
     * system (registered via OTP at least once). Returns 403 if the caller is not a
     * manager, 404 if the target user doesn't exist, 400 if the user is already a member.
     *
     * @param teamId The UUID of the team to add the member to.
     * @param request The member details: userId, role, and optional playerName.
     * @return 201 Created with the [TeamMemberDto] including expanded user profile.
     */
    @PostMapping("/{teamId}/members")
    fun addMember(
        @PathVariable teamId: UUID,
        @Valid @RequestBody request: AddTeamMemberRequest,
    ): ResponseEntity<TeamMemberDto> {
        val userId = authenticatedUserId()
        val member = teamService.addMember(userId, teamId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(member)
    }

    /**
     * Lists all active members of a team with expanded user profiles.
     *
     * Requires the authenticated user to be an active member. Returns all members
     * regardless of role, each with their user profile data (display name, phone, email).
     *
     * @param teamId The UUID of the team whose members to list.
     * @return 200 OK with a list of [TeamMemberDto]. Empty list if the team has no active members.
     */
    @GetMapping("/{teamId}/members")
    fun getMembers(@PathVariable teamId: UUID): ResponseEntity<List<TeamMemberDto>> {
        val userId = authenticatedUserId()
        val members = teamService.getMembers(userId, teamId)
        return ResponseEntity.ok(members)
    }
}
