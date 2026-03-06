package com.fieldiq.api.dto

import com.fieldiq.domain.Team
import com.fieldiq.domain.TeamMember
import com.fieldiq.domain.User
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * Team data returned in API responses.
 *
 * Corresponds to TypeScript interface: `TeamDto` in `shared/types/index.ts`.
 * Corresponds to Kotlin entity: [com.fieldiq.domain.Team].
 *
 * @property id UUID of the team.
 * @property orgId UUID of the parent organization (club/league). Null for standalone teams.
 * @property name Display name (e.g., "Bethesda Fire U12 Boys").
 * @property sport Sport type. Defaults to "soccer" in Phase 1.
 * @property ageGroup Age division label (e.g., "U10", "U14").
 * @property season Season identifier (e.g., "Spring2026").
 */
data class TeamDto(
    val id: String,
    val orgId: String? = null,
    val name: String,
    val sport: String,
    val ageGroup: String? = null,
    val season: String? = null,
) {
    companion object {
        /**
         * Converts a [Team] entity to a [TeamDto] response object.
         *
         * @param team The team entity to convert.
         * @return A [TeamDto] with all fields mapped.
         */
        fun from(team: Team): TeamDto = TeamDto(
            id = team.id.toString(),
            orgId = team.orgId?.toString(),
            name = team.name,
            sport = team.sport,
            ageGroup = team.ageGroup,
            season = team.season,
        )
    }
}

/**
 * Request body for `POST /teams`.
 *
 * Creates a new team. The authenticated user automatically becomes the team manager.
 *
 * Corresponds to TypeScript interface: `CreateTeamRequest` in `shared/types/index.ts`.
 *
 * @property name Display name for the team.
 * @property orgId Parent organization UUID. Optional for standalone teams.
 * @property sport Sport type. Defaults to "soccer" if omitted.
 * @property ageGroup Age division label (e.g., "U12").
 * @property season Season identifier (e.g., "Spring2026").
 */
data class CreateTeamRequest(
    @field:NotBlank(message = "Team name is required")
    val name: String,
    val orgId: String? = null,
    val sport: String? = "soccer",
    val ageGroup: String? = null,
    val season: String? = null,
)

/**
 * Team member data returned in API responses.
 *
 * Includes role and optional child name (for parent members).
 *
 * Corresponds to TypeScript interface: `TeamMemberDto` in `shared/types/index.ts`.
 * Corresponds to Kotlin entity: [com.fieldiq.domain.TeamMember].
 *
 * @property id UUID of the team membership record.
 * @property teamId UUID of the team.
 * @property userId UUID of the user.
 * @property role User's role on this team. Determines permissions.
 * @property playerName Child's name for roster display (COPPA: only stored here).
 * @property isActive Whether this membership is currently active.
 * @property user Expanded user profile, included when the API joins user data.
 */
data class TeamMemberDto(
    val id: String,
    val teamId: String,
    val userId: String,
    val role: String,
    val playerName: String? = null,
    val isActive: Boolean,
    val user: UserDto? = null,
) {
    companion object {
        /**
         * Converts a [TeamMember] entity to a [TeamMemberDto] response object.
         *
         * @param member The team member entity to convert.
         * @param user Optional [User] entity to expand in the response.
         * @return A [TeamMemberDto] with all fields mapped.
         */
        fun from(member: TeamMember, user: User? = null): TeamMemberDto = TeamMemberDto(
            id = member.id.toString(),
            teamId = member.teamId.toString(),
            userId = member.userId.toString(),
            role = member.role,
            playerName = member.playerName,
            isActive = member.isActive,
            user = user?.let { UserDto.from(it) },
        )
    }
}

/**
 * Request body for `POST /teams/:teamId/members`.
 *
 * Adds a user to a team with a specific role. Requires manager permissions.
 *
 * Corresponds to TypeScript interface: `AddTeamMemberRequest` in `shared/types/index.ts`.
 *
 * @property userId UUID of the user to add.
 * @property role Role to assign on this team: "manager", "coach", or "parent".
 * @property playerName Child's name (required when role is "parent", for roster display).
 */
data class AddTeamMemberRequest(
    @field:NotBlank(message = "User ID is required")
    val userId: String,

    @field:NotBlank(message = "Role is required")
    @field:Pattern(regexp = "manager|coach|parent", message = "Role must be 'manager', 'coach', or 'parent'")
    val role: String,

    val playerName: String? = null,
)
