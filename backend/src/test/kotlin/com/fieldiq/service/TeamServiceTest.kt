package com.fieldiq.service

import com.fieldiq.api.dto.AddTeamMemberRequest
import com.fieldiq.api.dto.CreateTeamRequest
import com.fieldiq.domain.Team
import com.fieldiq.domain.TeamMember
import com.fieldiq.domain.User
import com.fieldiq.repository.TeamMemberRepository
import com.fieldiq.repository.TeamRepository
import com.fieldiq.repository.UserRepository
import io.mockk.*
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException
import java.util.*

/**
 * Unit tests for [TeamService] — the service managing team lifecycle and roster
 * operations in FieldIQ's multi-tenant team model.
 *
 * Verifies the five core operations of [TeamService]:
 * 1. **Team creation** — creates a [Team] and auto-assigns the creating user as the
 *    initial manager via a [TeamMember] record with `role = "manager"`.
 * 2. **Team retrieval** — returns team details for authorized members, enforced by
 *    [TeamAccessGuard.requireActiveMember].
 * 3. **My teams listing** — returns all teams the authenticated user belongs to,
 *    sourced from active [TeamMember] records.
 * 4. **Member addition** — adds a user to a team's roster (manager-only), with
 *    duplicate detection and expanded user profile in the response.
 * 5. **Roster retrieval** — returns the full active roster with expanded user profiles
 *    for display in the team management UI.
 *
 * **Testing approach:** All repositories ([TeamRepository], [TeamMemberRepository],
 * [UserRepository]) and the [TeamAccessGuard] are mocked via MockK. Access control
 * tests verify that [AccessDeniedException] propagates correctly when the guard rejects
 * unauthorized callers.
 *
 * **No database interaction** — all persistence is verified via MockK `verify` blocks.
 *
 * @see TeamService for the service under test.
 * @see TeamAccessGuard for the access control guard (mocked here).
 * @see com.fieldiq.api.TeamController for the REST endpoints that delegate to this service.
 * @see Team for the JPA entity representing a team.
 * @see TeamMember for the JPA entity representing team membership.
 */
class TeamServiceTest {

    /** Mocked repository for [Team] CRUD operations. */
    private val teamRepository: TeamRepository = mockk(relaxed = true)

    /** Mocked repository for [TeamMember] roster operations. */
    private val teamMemberRepository: TeamMemberRepository = mockk(relaxed = true)

    /** Mocked repository for [User] profile lookups (used to expand member responses). */
    private val userRepository: UserRepository = mockk(relaxed = true)

    /** Mocked access guard enforcing team membership and manager role requirements. */
    private val teamAccessGuard: TeamAccessGuard = mockk(relaxed = true)

    /** The [TeamService] instance under test, recreated before each test. */
    private lateinit var teamService: TeamService

    /** Stable user UUID representing the authenticated caller in most tests. */
    private val userId = UUID.randomUUID()

    /** Stable team UUID used across test scenarios. */
    private val teamId = UUID.randomUUID()

    /** Pre-built test team with a realistic DMV youth soccer name. */
    private val testTeam = Team(id = teamId, name = "Bethesda Fire U12", sport = "soccer")

    /** Pre-built test user with a valid US phone number and display name. */
    private val testUser = User(id = userId, phone = "+12025551234", displayName = "Sarah Johnson")

    /** Pre-built manager membership record linking [testUser] to [testTeam]. */
    private val managerMember = TeamMember(teamId = teamId, userId = userId, role = "manager")

    /**
     * Creates a fresh [TeamService] with all mocked dependencies before each test.
     */
    @BeforeEach
    fun setUp() {
        teamService = TeamService(teamRepository, teamMemberRepository, userRepository, teamAccessGuard)
    }

    /**
     * Tests for [TeamService.createTeam].
     *
     * Validates the team creation workflow: [Team] entity persistence, automatic
     * [TeamMember] creation with `role = "manager"` for the creator, default values
     * for optional fields (sport defaults to "soccer"), and optional `orgId` parsing.
     */
    @Nested
    @DisplayName("createTeam")
    inner class CreateTeam {

        /**
         * Core workflow: creating a team persists both the [Team] entity and a [TeamMember]
         * record that assigns the creator as the initial manager.
         *
         * Verifies:
         * - The returned DTO has correct name, sport, and ageGroup from the request
         * - `teamRepository.save()` is called with the team data
         * - `teamMemberRepository.save()` is called with `role = "manager"` and the creator's userId
         */
        @Test
        fun `should create team and assign creator as manager`() {
            val request = CreateTeamRequest(name = "Bethesda Fire U12", sport = "soccer", ageGroup = "U12")
            every { teamRepository.save(any()) } answers { firstArg<Team>().copy(id = teamId) }
            every { teamMemberRepository.save(any()) } answers { firstArg() }

            val result = teamService.createTeam(userId, request)

            assertEquals("Bethesda Fire U12", result.name)
            assertEquals("soccer", result.sport)
            assertEquals("U12", result.ageGroup)

            verify { teamRepository.save(any()) }
            verify { teamMemberRepository.save(match { it.role == "manager" && it.userId == userId }) }
        }

        /**
         * Default sport: when the `sport` field is null in the request, the service
         * defaults to "soccer" (FieldIQ's Phase 1 target sport for DMV youth leagues).
         *
         * This prevents null sports from reaching the database and simplifies the
         * mobile app's team creation flow where sport selection is optional.
         */
        @Test
        fun `should default sport to soccer when not specified`() {
            val request = CreateTeamRequest(name = "My Team", sport = null)
            every { teamRepository.save(any()) } answers { firstArg() }
            every { teamMemberRepository.save(any()) } answers { firstArg() }

            teamService.createTeam(userId, request)

            verify { teamRepository.save(match { it.sport == "soccer" }) }
        }

        /**
         * Organization linkage: when an `orgId` UUID string is provided, it should be
         * parsed and stored on the [Team] entity for multi-team organization grouping.
         *
         * This supports the use case where a club (e.g., "Bethesda Fire") manages
         * multiple age-group teams under a single organizational umbrella.
         */
        @Test
        fun `should parse orgId when provided`() {
            val orgId = UUID.randomUUID()
            val request = CreateTeamRequest(name = "Team", orgId = orgId.toString())
            every { teamRepository.save(any()) } answers { firstArg() }
            every { teamMemberRepository.save(any()) } answers { firstArg() }

            teamService.createTeam(userId, request)

            verify { teamRepository.save(match { it.orgId == orgId }) }
        }
    }

    /**
     * Tests for [TeamService.getTeam].
     *
     * Validates team retrieval with access control: active members can view their team,
     * non-members are rejected, and non-existent teams return 404.
     */
    @Nested
    @DisplayName("getTeam")
    inner class GetTeam {

        /**
         * Happy path: an active team member can retrieve team details.
         *
         * Verifies the response DTO contains the correct team ID and name.
         * The [TeamAccessGuard.requireActiveMember] check passes for authorized callers.
         */
        @Test
        fun `should return team when user is active member`() {
            every { teamAccessGuard.requireActiveMember(userId, teamId) } returns managerMember
            every { teamRepository.findById(teamId) } returns Optional.of(testTeam)

            val result = teamService.getTeam(userId, teamId)

            assertEquals(teamId.toString(), result.id)
            assertEquals("Bethesda Fire U12", result.name)
        }

        /**
         * Access denial: a user who is not an active member of the team must be rejected.
         *
         * The [TeamAccessGuard] throws [AccessDeniedException], which propagates to
         * the controller layer and is mapped to HTTP 403 by [com.fieldiq.api.GlobalExceptionHandler].
         */
        @Test
        fun `should throw AccessDeniedException when user is not a member`() {
            every { teamAccessGuard.requireActiveMember(userId, teamId) } throws
                AccessDeniedException("Not a member")

            assertThrows(AccessDeniedException::class.java) {
                teamService.getTeam(userId, teamId)
            }
        }

        /**
         * Entity not found: when the team UUID doesn't exist in the database (even if
         * the membership check passed, which shouldn't happen in practice), the service
         * throws [EntityNotFoundException] mapped to HTTP 404.
         */
        @Test
        fun `should throw EntityNotFoundException when team does not exist`() {
            every { teamAccessGuard.requireActiveMember(userId, teamId) } returns managerMember
            every { teamRepository.findById(teamId) } returns Optional.empty()

            assertThrows(EntityNotFoundException::class.java) {
                teamService.getTeam(userId, teamId)
            }
        }
    }

    /**
     * Tests for [TeamService.getMyTeams].
     *
     * Validates the "my teams" listing: retrieves all active [TeamMember] records for
     * the authenticated user, then batch-loads the corresponding [Team] entities.
     * No access guard is needed since users can always see their own team list.
     */
    @Nested
    @DisplayName("getMyTeams")
    inner class GetMyTeams {

        /**
         * Multi-team membership: a user belonging to two teams should see both in the result.
         *
         * Verifies that the service correctly joins [TeamMember] records to [Team] entities
         * via `teamRepository.findAllById()`.
         */
        @Test
        fun `should return all teams user belongs to`() {
            val teamB = Team(id = UUID.randomUUID(), name = "Arlington United U10")
            val memberA = TeamMember(teamId = teamId, userId = userId, role = "manager")
            val memberB = TeamMember(teamId = teamB.id, userId = userId, role = "parent")

            every { teamMemberRepository.findByUserIdAndIsActiveTrue(userId) } returns listOf(memberA, memberB)
            every { teamRepository.findAllById(any<List<UUID>>()) } returns listOf(testTeam, teamB)

            val result = teamService.getMyTeams(userId)

            assertEquals(2, result.size)
        }

        /**
         * No memberships: a user who hasn't joined any teams should see an empty list.
         *
         * Importantly, `teamRepository.findAllById()` should NOT be called with an
         * empty list — the service should short-circuit to avoid unnecessary DB queries.
         */
        @Test
        fun `should return empty list when user has no teams`() {
            every { teamMemberRepository.findByUserIdAndIsActiveTrue(userId) } returns emptyList()

            val result = teamService.getMyTeams(userId)

            assertTrue(result.isEmpty())
            verify(exactly = 0) { teamRepository.findAllById(any<List<UUID>>()) }
        }
    }

    /**
     * Tests for [TeamService.addMember].
     *
     * Validates the member addition workflow: manager-only authorization, target user
     * existence check, duplicate membership detection, [TeamMember] creation, and
     * expanded user profile in the response DTO.
     */
    @Nested
    @DisplayName("addMember")
    inner class AddMember {

        /** UUID for the target user being added to the team (distinct from the caller). */
        private val targetUserId = UUID.randomUUID()

        /** Pre-built target user with a valid phone number and display name. */
        private val targetUser = User(id = targetUserId, phone = "+12025559999", displayName = "Jake's Dad")

        /**
         * Happy path: a manager adds a new parent member with a player name.
         *
         * Verifies:
         * - The returned DTO has correct role, playerName, userId, and expanded user profile
         * - The user profile includes the display name from the [User] entity
         */
        @Test
        fun `should add member with expanded user profile`() {
            val request = AddTeamMemberRequest(
                userId = targetUserId.toString(),
                role = "parent",
                playerName = "Jake Johnson",
            )
            every { teamAccessGuard.requireManager(userId, teamId) } returns managerMember
            every { userRepository.findById(targetUserId) } returns Optional.of(targetUser)
            every { teamMemberRepository.findByUserIdAndTeamIdAndIsActiveTrue(targetUserId, teamId) } returns null
            every { teamMemberRepository.save(any()) } answers { firstArg() }

            val result = teamService.addMember(userId, teamId, request)

            assertEquals("parent", result.role)
            assertEquals("Jake Johnson", result.playerName)
            assertEquals(targetUserId.toString(), result.userId)
            assertNotNull(result.user)
            assertEquals("Jake's Dad", result.user?.displayName)
        }

        /**
         * Authorization: only managers can add members to a team. A non-manager caller
         * (e.g., a parent) must be rejected with [AccessDeniedException].
         */
        @Test
        fun `should throw AccessDeniedException when caller is not manager`() {
            val request = AddTeamMemberRequest(userId = targetUserId.toString(), role = "parent")
            every { teamAccessGuard.requireManager(userId, teamId) } throws
                AccessDeniedException("Not a manager")

            assertThrows(AccessDeniedException::class.java) {
                teamService.addMember(userId, teamId, request)
            }
        }

        /**
         * Target user existence: the user being added must have an account in FieldIQ.
         *
         * If the target `userId` doesn't exist in the `users` table, the service throws
         * [EntityNotFoundException]. In production, the manager would first invite the
         * user to create an account via OTP sign-up.
         */
        @Test
        fun `should throw EntityNotFoundException when target user does not exist`() {
            val request = AddTeamMemberRequest(userId = targetUserId.toString(), role = "parent")
            every { teamAccessGuard.requireManager(userId, teamId) } returns managerMember
            every { userRepository.findById(targetUserId) } returns Optional.empty()

            assertThrows(EntityNotFoundException::class.java) {
                teamService.addMember(userId, teamId, request)
            }
        }

        /**
         * Duplicate detection: adding a user who is already an active member of the
         * team must be rejected with [IllegalArgumentException].
         *
         * This prevents accidental double-adds from the manager UI and ensures the
         * roster count stays accurate.
         */
        @Test
        fun `should throw IllegalArgumentException when user is already active member`() {
            val request = AddTeamMemberRequest(userId = targetUserId.toString(), role = "parent")
            every { teamAccessGuard.requireManager(userId, teamId) } returns managerMember
            every { userRepository.findById(targetUserId) } returns Optional.of(targetUser)
            every { teamMemberRepository.findByUserIdAndTeamIdAndIsActiveTrue(targetUserId, teamId) } returns
                TeamMember(teamId = teamId, userId = targetUserId, role = "parent")

            assertThrows(IllegalArgumentException::class.java) {
                teamService.addMember(userId, teamId, request)
            }
        }
    }

    /**
     * Tests for [TeamService.getMembers].
     *
     * Validates the roster retrieval: active members with expanded user profiles for
     * display in the team management UI, with access control enforcement.
     */
    @Nested
    @DisplayName("getMembers")
    inner class GetMembers {

        /**
         * Full roster with profiles: returns all active members with their [User]
         * profiles joined via batch loading.
         *
         * Verifies:
         * - The correct number of members is returned
         * - Both role types (manager, parent) are present
         * - Player names are included where applicable
         */
        @Test
        fun `should return members with expanded user profiles`() {
            val otherUserId = UUID.randomUUID()
            val otherUser = User(id = otherUserId, phone = "+12025559999")
            val members = listOf(
                TeamMember(teamId = teamId, userId = userId, role = "manager"),
                TeamMember(teamId = teamId, userId = otherUserId, role = "parent", playerName = "Jake"),
            )

            every { teamAccessGuard.requireActiveMember(userId, teamId) } returns managerMember
            every { teamMemberRepository.findByTeamIdAndIsActiveTrue(teamId) } returns members
            every { userRepository.findAllById(any<List<UUID>>()) } returns listOf(testUser, otherUser)

            val result = teamService.getMembers(userId, teamId)

            assertEquals(2, result.size)
            assertTrue(result.any { it.role == "manager" })
            assertTrue(result.any { it.role == "parent" && it.playerName == "Jake" })
        }

        /**
         * Access denial: a user who is not an active member cannot view the team roster.
         *
         * The [TeamAccessGuard] throws [AccessDeniedException], which propagates to
         * the controller layer and is mapped to HTTP 403.
         */
        @Test
        fun `should throw AccessDeniedException when user is not a member`() {
            every { teamAccessGuard.requireActiveMember(userId, teamId) } throws
                AccessDeniedException("Not a member")

            assertThrows(AccessDeniedException::class.java) {
                teamService.getMembers(userId, teamId)
            }
        }
    }
}
