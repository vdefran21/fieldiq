package com.fieldiq.service

import com.fieldiq.api.dto.CreateAvailabilityWindowRequest
import com.fieldiq.domain.AvailabilityWindow
import com.fieldiq.domain.TeamMember
import com.fieldiq.repository.AvailabilityWindowRepository
import io.mockk.*
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException
import java.time.LocalTime
import java.util.*

/**
 * Unit tests for [AvailabilityWindowService] — the service managing recurring and
 * date-specific availability windows used by FieldIQ's scheduling engine.
 *
 * Verifies the four core operations of [AvailabilityWindowService]:
 * 1. **Window creation** — validates the mutual exclusivity constraint (exactly one of
 *    `dayOfWeek` or `specificDate` must be set), time ordering (`startTime < endTime`),
 *    and persists the window with `source = "manual"` and team membership enforcement.
 * 2. **Team availability query** — returns all windows for a team, used by
 *    [com.fieldiq.service.SchedulingService] to compute intersections during negotiation.
 * 3. **User availability query** — returns all windows across teams for the authenticated
 *    user's personal schedule view.
 * 4. **Window deletion** — ownership-based authorization (only the window creator can
 *    delete it), with entity existence checks.
 *
 * **Validation rules tested:**
 * - Exactly one of `dayOfWeek` (0–6) or `specificDate` (ISO date string) must be provided
 * - `startTime` must be strictly before `endTime` (zero-duration windows are rejected)
 * - Only active team members can create windows for their team
 *
 * **Testing approach:** The [AvailabilityWindowRepository] and [TeamAccessGuard] are
 * mocked via MockK. Validation tests exercise the service's input checking logic
 * independently of the database.
 *
 * **No database interaction** — all persistence is verified via MockK `verify` blocks.
 *
 * @see AvailabilityWindowService for the service under test.
 * @see AvailabilityWindow for the JPA entity representing an availability window.
 * @see com.fieldiq.api.AvailabilityController for the REST endpoints that delegate to this service.
 * @see com.fieldiq.service.SchedulingService for the consumer of availability data during negotiation.
 */
class AvailabilityWindowServiceTest {

    /** Mocked repository for [AvailabilityWindow] CRUD operations. */
    private val availabilityWindowRepository: AvailabilityWindowRepository = mockk(relaxed = true)

    /** Mocked access guard enforcing team membership requirements. */
    private val teamAccessGuard: TeamAccessGuard = mockk(relaxed = true)

    /** The [AvailabilityWindowService] instance under test, recreated before each test. */
    private lateinit var service: AvailabilityWindowService

    /** Stable user UUID representing the authenticated caller in most tests. */
    private val userId = UUID.randomUUID()

    /** Stable team UUID used across test scenarios. */
    private val teamId = UUID.randomUUID()

    /** Stable window UUID used in deletion tests. */
    private val windowId = UUID.randomUUID()

    /** Pre-built parent membership record linking the test user to the test team. */
    private val member = TeamMember(teamId = teamId, userId = userId, role = "parent")

    /**
     * Creates a fresh [AvailabilityWindowService] with all mocked dependencies before each test.
     */
    @BeforeEach
    fun setUp() {
        service = AvailabilityWindowService(availabilityWindowRepository, teamAccessGuard)
    }

    /**
     * Tests for [AvailabilityWindowService.createWindow].
     *
     * Validates the full creation pipeline: mutual exclusivity of `dayOfWeek` vs.
     * `specificDate`, time ordering constraints, team membership enforcement, and
     * correct field mapping to the [AvailabilityWindow] entity.
     *
     * **Validation matrix:**
     * | dayOfWeek | specificDate | Result                          |
     * |-----------|-------------|----------------------------------|
     * | set       | null        | Recurring window (valid)         |
     * | null      | set         | Date-specific window (valid)     |
     * | set       | set         | IllegalArgumentException         |
     * | null      | null        | IllegalArgumentException         |
     */
    @Nested
    @DisplayName("createWindow")
    inner class CreateWindow {

        /**
         * Recurring window: `dayOfWeek` is set (6 = Saturday), `specificDate` is null.
         *
         * Verifies the entity has the correct dayOfWeek, null specificDate, time range,
         * window type, and `source = "manual"` (distinguishing from calendar-synced windows).
         */
        @Test
        fun `should create recurring window with dayOfWeek`() {
            val request = CreateAvailabilityWindowRequest(
                teamId = teamId.toString(),
                dayOfWeek = 6, // Saturday
                startTime = "09:00",
                endTime = "12:00",
                windowType = "available",
            )
            every { teamAccessGuard.requireActiveMember(userId, teamId) } returns member
            every { availabilityWindowRepository.save(any()) } answers { firstArg() }

            val result = service.createWindow(userId, request)

            assertEquals(6.toShort(), result.dayOfWeek)
            assertNull(result.specificDate)
            assertEquals("09:00", result.startTime)
            assertEquals("12:00", result.endTime)
            assertEquals("available", result.windowType)
            assertEquals("manual", result.source)
        }

        /**
         * Date-specific window: `specificDate` is set (ISO date), `dayOfWeek` is null.
         *
         * This supports one-off schedule overrides, e.g., "I'm unavailable on March 15
         * for a family event." Verifies correct field mapping and null dayOfWeek.
         */
        @Test
        fun `should create date-specific window with specificDate`() {
            val request = CreateAvailabilityWindowRequest(
                teamId = teamId.toString(),
                specificDate = "2026-03-15",
                startTime = "10:00",
                endTime = "11:30",
                windowType = "unavailable",
            )
            every { teamAccessGuard.requireActiveMember(userId, teamId) } returns member
            every { availabilityWindowRepository.save(any()) } answers { firstArg() }

            val result = service.createWindow(userId, request)

            assertNull(result.dayOfWeek)
            assertEquals("2026-03-15", result.specificDate)
            assertEquals("unavailable", result.windowType)
        }

        /**
         * Mutual exclusivity violation: providing BOTH `dayOfWeek` and `specificDate`
         * is invalid and must throw [IllegalArgumentException].
         *
         * A window cannot be simultaneously recurring (every Saturday) and date-specific
         * (March 15 only). The error message must contain "not both" to guide the caller.
         */
        @Test
        fun `should throw when both dayOfWeek and specificDate are provided`() {
            val request = CreateAvailabilityWindowRequest(
                teamId = teamId.toString(),
                dayOfWeek = 6,
                specificDate = "2026-03-15",
                startTime = "09:00",
                endTime = "12:00",
                windowType = "available",
            )

            val ex = assertThrows(IllegalArgumentException::class.java) {
                service.createWindow(userId, request)
            }
            assertTrue(ex.message!!.contains("not both"))
        }

        /**
         * Missing schedule type: providing NEITHER `dayOfWeek` nor `specificDate`
         * is invalid — the service can't determine when this window applies.
         *
         * The error message must contain "must be specified" to guide the caller.
         */
        @Test
        fun `should throw when neither dayOfWeek nor specificDate provided`() {
            val request = CreateAvailabilityWindowRequest(
                teamId = teamId.toString(),
                dayOfWeek = null,
                specificDate = null,
                startTime = "09:00",
                endTime = "12:00",
                windowType = "available",
            )

            val ex = assertThrows(IllegalArgumentException::class.java) {
                service.createWindow(userId, request)
            }
            assertTrue(ex.message!!.contains("must be specified"))
        }

        /**
         * Time ordering violation: `startTime` must be strictly before `endTime`.
         *
         * A window where 14:00 (2 PM) is the start and 09:00 (9 AM) is the end
         * is nonsensical. The error message must contain "before end time".
         */
        @Test
        fun `should throw when startTime is after endTime`() {
            val request = CreateAvailabilityWindowRequest(
                teamId = teamId.toString(),
                dayOfWeek = 6,
                startTime = "14:00",
                endTime = "09:00",
                windowType = "available",
            )

            val ex = assertThrows(IllegalArgumentException::class.java) {
                service.createWindow(userId, request)
            }
            assertTrue(ex.message!!.contains("before end time"))
        }

        /**
         * Zero-duration window: `startTime == endTime` is rejected because it represents
         * no actual availability and would cause division-by-zero or empty-intersection
         * issues in the scheduling engine.
         */
        @Test
        fun `should throw when startTime equals endTime (zero duration)`() {
            val request = CreateAvailabilityWindowRequest(
                teamId = teamId.toString(),
                dayOfWeek = 6,
                startTime = "10:00",
                endTime = "10:00",
                windowType = "available",
            )

            assertThrows(IllegalArgumentException::class.java) {
                service.createWindow(userId, request)
            }
        }

        /**
         * Team membership enforcement: only active team members can create availability
         * windows for their team.
         *
         * A non-member caller must be rejected with [AccessDeniedException], preventing
         * unauthorized users from polluting a team's scheduling data.
         */
        @Test
        fun `should throw AccessDeniedException when user is not team member`() {
            val request = CreateAvailabilityWindowRequest(
                teamId = teamId.toString(),
                dayOfWeek = 6,
                startTime = "09:00",
                endTime = "12:00",
                windowType = "available",
            )
            every { teamAccessGuard.requireActiveMember(userId, teamId) } throws
                AccessDeniedException("Not a member")

            assertThrows(AccessDeniedException::class.java) {
                service.createWindow(userId, request)
            }
        }
    }

    /**
     * Tests for [AvailabilityWindowService.getTeamAvailability].
     *
     * Validates the team-scoped availability query: returns all windows for a team
     * (both recurring and date-specific, for all members), with active-member access control.
     */
    @Nested
    @DisplayName("getTeamAvailability")
    inner class GetTeamAvailability {

        /**
         * Happy path: returns all windows for the team, including windows from
         * different users and different schedule types.
         *
         * This data feeds the [com.fieldiq.service.SchedulingService] when computing
         * available time slots for cross-team negotiation.
         */
        @Test
        fun `should return all windows for the team`() {
            val windows = listOf(
                AvailabilityWindow(
                    teamId = teamId, userId = userId, dayOfWeek = 6,
                    startTime = LocalTime.of(9, 0), endTime = LocalTime.of(12, 0),
                    windowType = "available",
                ),
                AvailabilityWindow(
                    teamId = teamId, userId = UUID.randomUUID(), dayOfWeek = 0,
                    startTime = LocalTime.of(14, 0), endTime = LocalTime.of(16, 0),
                    windowType = "unavailable",
                ),
            )
            every { teamAccessGuard.requireActiveMember(userId, teamId) } returns member
            every { availabilityWindowRepository.findByTeamId(teamId) } returns windows

            val result = service.getTeamAvailability(userId, teamId)

            assertEquals(2, result.size)
        }

        /**
         * Access denial: a non-member cannot view team availability data.
         *
         * Availability windows reveal scheduling patterns and are team-private data,
         * so the [TeamAccessGuard] enforces active membership.
         */
        @Test
        fun `should throw AccessDeniedException when user is not a member`() {
            every { teamAccessGuard.requireActiveMember(userId, teamId) } throws
                AccessDeniedException("Not a member")

            assertThrows(AccessDeniedException::class.java) {
                service.getTeamAvailability(userId, teamId)
            }
        }
    }

    /**
     * Tests for [AvailabilityWindowService.getUserAvailability].
     *
     * Validates the user-scoped availability query: returns all windows across all
     * teams for the authenticated user's personal schedule overview. No team-specific
     * access guard is needed since users can always view their own availability.
     */
    @Nested
    @DisplayName("getUserAvailability")
    inner class GetUserAvailability {

        /**
         * Cross-team aggregation: returns windows from all teams the user belongs to.
         *
         * This feeds the mobile app's "My Availability" screen where a parent/coach
         * sees their complete schedule across multiple teams.
         */
        @Test
        fun `should return all windows across teams`() {
            val windows = listOf(
                AvailabilityWindow(
                    teamId = teamId, userId = userId, dayOfWeek = 6,
                    startTime = LocalTime.of(9, 0), endTime = LocalTime.of(12, 0),
                    windowType = "available",
                ),
            )
            every { availabilityWindowRepository.findByUserId(userId) } returns windows

            val result = service.getUserAvailability(userId)

            assertEquals(1, result.size)
        }

        /**
         * Empty state: a user who hasn't set up any availability windows sees an empty list.
         *
         * This is the default state for new users before they configure their schedule.
         */
        @Test
        fun `should return empty list when no windows exist`() {
            every { availabilityWindowRepository.findByUserId(userId) } returns emptyList()

            val result = service.getUserAvailability(userId)

            assertTrue(result.isEmpty())
        }
    }

    /**
     * Tests for [AvailabilityWindowService.deleteWindow].
     *
     * Validates ownership-based deletion: only the user who created the window can
     * delete it. Entity existence is checked first, and attempts to delete another
     * user's window are rejected.
     */
    @Nested
    @DisplayName("deleteWindow")
    inner class DeleteWindow {

        /**
         * Happy path: the owner can delete their own availability window.
         *
         * Verifies that `availabilityWindowRepository.delete()` is called with the
         * correct entity after ownership verification passes.
         */
        @Test
        fun `should delete window owned by the user`() {
            val window = AvailabilityWindow(
                id = windowId, teamId = teamId, userId = userId, dayOfWeek = 6,
                startTime = LocalTime.of(9, 0), endTime = LocalTime.of(12, 0),
                windowType = "available",
            )
            every { availabilityWindowRepository.findById(windowId) } returns Optional.of(window)

            assertDoesNotThrow { service.deleteWindow(userId, windowId) }

            verify { availabilityWindowRepository.delete(window) }
        }

        /**
         * Entity not found: deleting a non-existent window throws [EntityNotFoundException].
         *
         * This covers both genuinely missing windows and UUIDs that were never valid.
         */
        @Test
        fun `should throw EntityNotFoundException when window does not exist`() {
            every { availabilityWindowRepository.findById(windowId) } returns Optional.empty()

            assertThrows(EntityNotFoundException::class.java) {
                service.deleteWindow(userId, windowId)
            }
        }

        /**
         * Ownership enforcement: a user cannot delete another user's availability window.
         *
         * The window exists but belongs to a different userId, so the service must throw
         * [AccessDeniedException] and NOT call `delete()`. This prevents managers from
         * accidentally deleting a parent's availability data.
         */
        @Test
        fun `should throw AccessDeniedException when window belongs to another user`() {
            val otherUserId = UUID.randomUUID()
            val window = AvailabilityWindow(
                id = windowId, teamId = teamId, userId = otherUserId, dayOfWeek = 6,
                startTime = LocalTime.of(9, 0), endTime = LocalTime.of(12, 0),
                windowType = "available",
            )
            every { availabilityWindowRepository.findById(windowId) } returns Optional.of(window)

            assertThrows(AccessDeniedException::class.java) {
                service.deleteWindow(userId, windowId)
            }
            verify(exactly = 0) { availabilityWindowRepository.delete(any()) }
        }
    }
}
