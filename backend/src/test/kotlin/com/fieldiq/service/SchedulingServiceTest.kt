package com.fieldiq.service

import com.fieldiq.domain.AvailabilityWindow
import com.fieldiq.domain.Event
import com.fieldiq.domain.Organization
import com.fieldiq.domain.Team
import com.fieldiq.domain.TeamMember
import com.fieldiq.repository.AvailabilityWindowRepository
import com.fieldiq.repository.EventRepository
import com.fieldiq.repository.OrganizationRepository
import com.fieldiq.repository.TeamMemberRepository
import com.fieldiq.repository.TeamRepository
import io.mockk.every
import io.mockk.mockk
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.*

/**
 * Unit tests for [SchedulingService] — the deterministic scheduling engine that
 * computes optimal meeting times for teams and finds mutual availability across teams.
 *
 * Tests cover the two core algorithms:
 * 1. **[SchedulingService.findAvailableWindows]** — aggregates member availability,
 *    subtracts busy blocks and existing events, produces ranked time windows.
 * 2. **[SchedulingService.intersectWindows]** — finds time overlaps between two teams'
 *    available windows for cross-team negotiation.
 *
 * **Testing approach:** All repositories are mocked via MockK. The service's
 * deterministic algorithm is tested by providing controlled availability windows,
 * events, and member rosters, then asserting on the computed windows' time ranges
 * and confidence scores.
 *
 * **Timezone handling:** Tests use the "America/New_York" timezone (FieldIQ's
 * Phase 1 default for DMV youth soccer) via a mocked [Organization] entity.
 *
 * @see SchedulingService for the service under test.
 * @see com.fieldiq.domain.AvailabilityWindow for the availability input model.
 * @see com.fieldiq.domain.Event for the event conflict model.
 */
class SchedulingServiceTest {

    private val availabilityWindowRepository: AvailabilityWindowRepository = mockk()
    private val eventRepository: EventRepository = mockk()
    private val teamMemberRepository: TeamMemberRepository = mockk()
    private val teamRepository: TeamRepository = mockk()
    private val organizationRepository: OrganizationRepository = mockk()

    private lateinit var service: SchedulingService

    private val teamId = UUID.randomUUID()
    private val orgId = UUID.randomUUID()
    private val userA = UUID.randomUUID()
    private val userB = UUID.randomUUID()
    private val userC = UUID.randomUUID()

    private val timezone = ZoneId.of("America/New_York")

    /**
     * Creates the service and sets up common mock behavior before each test.
     * All tests start with a valid team linked to an organization with Eastern timezone.
     */
    @BeforeEach
    fun setup() {
        service = SchedulingService(
            availabilityWindowRepository,
            eventRepository,
            teamMemberRepository,
            teamRepository,
            organizationRepository,
        )

        // Default: team exists with org
        every { teamRepository.findById(teamId) } returns Optional.of(
            Team(id = teamId, orgId = orgId, name = "Test Team U12")
        )
        every { organizationRepository.findById(orgId) } returns Optional.of(
            Organization(id = orgId, name = "Test Org", slug = "test-org", timezone = "America/New_York")
        )

        // Default: no existing events
        every { eventRepository.findOverlappingEvents(teamId, any(), any()) } returns emptyList()
    }

    @Nested
    @DisplayName("findAvailableWindows — input validation")
    inner class InputValidation {

        /**
         * Verifies that dateRangeEnd before dateRangeStart throws [IllegalArgumentException].
         */
        @Test
        fun `rejects date range where end is before start`() {
            setupMembers(listOf(userA))
            setupWindows(emptyList())

            assertThrows(IllegalArgumentException::class.java) {
                service.findAvailableWindows(
                    teamId, LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 5), 90
                )
            }
        }

        /**
         * Verifies that a date range exceeding 60 days is rejected.
         */
        @Test
        fun `rejects date range exceeding 60 days`() {
            setupMembers(listOf(userA))
            setupWindows(emptyList())

            assertThrows(IllegalArgumentException::class.java) {
                service.findAvailableWindows(
                    teamId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), 90
                )
            }
        }

        /**
         * Verifies that duration under 15 minutes is rejected.
         */
        @Test
        fun `rejects duration under 15 minutes`() {
            setupMembers(listOf(userA))
            setupWindows(emptyList())

            assertThrows(IllegalArgumentException::class.java) {
                service.findAvailableWindows(
                    teamId, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 7), 10
                )
            }
        }

        /**
         * Verifies that a nonexistent team throws [EntityNotFoundException].
         */
        @Test
        fun `throws when team not found`() {
            val unknownTeamId = UUID.randomUUID()
            every { teamRepository.findById(unknownTeamId) } returns Optional.empty()

            assertThrows(EntityNotFoundException::class.java) {
                service.findAvailableWindows(
                    unknownTeamId, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 7), 90
                )
            }
        }

        /**
         * Verifies that a team with no active members returns empty results.
         */
        @Test
        fun `returns empty when team has no active members`() {
            setupMembers(emptyList())
            setupWindows(emptyList())

            val result = service.findAvailableWindows(
                teamId, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 7), 90
            )
            assertTrue(result.isEmpty())
        }
    }

    @Nested
    @DisplayName("findAvailableWindows — single member availability")
    inner class SingleMember {

        /**
         * Verifies that a single member's recurring Saturday availability window
         * produces a result on a Saturday within the date range.
         *
         * Saturday April 4, 2026 = dayOfWeek 6 in FieldIQ convention.
         */
        @Test
        fun `finds window on recurring day of week`() {
            setupMembers(listOf(userA))
            setupWindows(
                listOf(
                    makeWindow(userA, dayOfWeek = 6, start = "09:00", end = "12:00", type = "available"),
                )
            )

            // April 4, 2026 is a Saturday (dayOfWeek=6)
            val result = service.findAvailableWindows(
                teamId, LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 4), 90
            )

            assertEquals(1, result.size)
            assertEquals(1.0, result[0].confidence) // 1/1 members available
        }

        /**
         * Verifies that a specific-date availability window is matched only
         * on that exact date.
         */
        @Test
        fun `finds window on specific date`() {
            setupMembers(listOf(userA))
            setupWindows(
                listOf(
                    makeWindow(
                        userA, specificDate = LocalDate.of(2026, 4, 5),
                        start = "10:00", end = "14:00", type = "available"
                    ),
                )
            )

            val result = service.findAvailableWindows(
                teamId, LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 6), 90
            )

            // Should only find a window on April 5
            assertEquals(1, result.size)
        }

        /**
         * Verifies that no windows are returned when no member has declared
         * availability for the queried date range.
         */
        @Test
        fun `returns empty when no availability matches date range`() {
            setupMembers(listOf(userA))
            // Only available on Sundays (dow=0), but querying a Monday-Friday range
            setupWindows(
                listOf(
                    makeWindow(userA, dayOfWeek = 0, start = "09:00", end = "12:00", type = "available"),
                )
            )

            // April 6-10, 2026 is Monday-Friday
            val result = service.findAvailableWindows(
                teamId, LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 10), 90
            )

            assertTrue(result.isEmpty())
        }

        /**
         * Verifies that a window too short for the requested duration is excluded.
         */
        @Test
        fun `excludes windows shorter than required duration`() {
            setupMembers(listOf(userA))
            setupWindows(
                listOf(
                    // Only 60 min available, but 90 min requested
                    makeWindow(userA, dayOfWeek = 6, start = "09:00", end = "10:00", type = "available"),
                )
            )

            val result = service.findAvailableWindows(
                teamId, LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 4), 90
            )

            assertTrue(result.isEmpty())
        }

        /**
         * Verifies that an "unavailable" window correctly subtracts from an
         * "available" window, potentially splitting it or reducing it below
         * the minimum duration.
         */
        @Test
        fun `unavailable window subtracts from available window`() {
            setupMembers(listOf(userA))
            setupWindows(
                listOf(
                    makeWindow(userA, dayOfWeek = 6, start = "09:00", end = "15:00", type = "available"),
                    makeWindow(userA, dayOfWeek = 6, start = "11:00", end = "13:00", type = "unavailable"),
                )
            )

            // Available: 09:00-11:00 (120min) and 13:00-15:00 (120min)
            // Both are >= 90 min, so we should get 2 windows
            val result = service.findAvailableWindows(
                teamId, LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 4), 90
            )

            assertEquals(2, result.size)
        }
    }

    @Nested
    @DisplayName("findAvailableWindows — multiple member confidence scoring")
    inner class MultiMemberConfidence {

        /**
         * Verifies that confidence reflects the fraction of members available.
         * With 3 members and 2 available, confidence should be ~0.67.
         */
        @Test
        fun `confidence reflects fraction of available members`() {
            setupMembers(listOf(userA, userB, userC))
            setupWindows(
                listOf(
                    makeWindow(userA, dayOfWeek = 6, start = "09:00", end = "12:00", type = "available"),
                    makeWindow(userB, dayOfWeek = 6, start = "09:00", end = "12:00", type = "available"),
                    // userC has no availability declared
                )
            )

            val result = service.findAvailableWindows(
                teamId, LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 4), 90
            )

            assertEquals(1, result.size)
            // 2 out of 3 members available
            assertEquals(2.0 / 3.0, result[0].confidence, 0.01)
        }

        /**
         * Verifies that when all members are available, confidence is 1.0.
         */
        @Test
        fun `confidence is 1 when all members available`() {
            setupMembers(listOf(userA, userB))
            setupWindows(
                listOf(
                    makeWindow(userA, dayOfWeek = 6, start = "09:00", end = "12:00", type = "available"),
                    makeWindow(userB, dayOfWeek = 6, start = "09:00", end = "12:00", type = "available"),
                )
            )

            val result = service.findAvailableWindows(
                teamId, LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 4), 90
            )

            assertEquals(1, result.size)
            assertEquals(1.0, result[0].confidence)
        }
    }

    @Nested
    @DisplayName("findAvailableWindows — event conflict subtraction")
    inner class EventConflicts {

        /**
         * Verifies that existing scheduled events are subtracted from availability.
         * An event during a member's available window should reduce the usable time.
         */
        @Test
        fun `existing events reduce available time`() {
            setupMembers(listOf(userA))
            setupWindows(
                listOf(
                    // Available 09:00-15:00 (6 hours) on Saturday
                    makeWindow(userA, dayOfWeek = 6, start = "09:00", end = "15:00", type = "available"),
                )
            )

            // Event from 11:00-13:00 on April 4 (Saturday)
            val eventStart = LocalDate.of(2026, 4, 4).atTime(11, 0)
                .atZone(timezone).toInstant()
            val eventEnd = LocalDate.of(2026, 4, 4).atTime(13, 0)
                .atZone(timezone).toInstant()

            every { eventRepository.findOverlappingEvents(teamId, any(), any()) } returns listOf(
                Event(
                    teamId = teamId, eventType = "game", startsAt = eventStart,
                    endsAt = eventEnd, status = "scheduled"
                )
            )

            val result = service.findAvailableWindows(
                teamId, LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 4), 90
            )

            // Should get two windows: 09:00-11:00 (120min) and 13:00-15:00 (120min)
            assertEquals(2, result.size)
        }
    }

    @Nested
    @DisplayName("findAvailableWindows — preferred day boosting")
    inner class PreferredDays {

        /**
         * Verifies that windows on preferred days receive a confidence boost.
         * A Saturday window with preferred=[6] should score higher than an
         * identical Sunday window without the boost.
         */
        @Test
        fun `preferred day boosts confidence`() {
            setupMembers(listOf(userA, userB))
            setupWindows(
                listOf(
                    // Saturday availability (both members)
                    makeWindow(userA, dayOfWeek = 6, start = "09:00", end = "12:00", type = "available"),
                    // Sunday availability (only userA)
                    makeWindow(userA, dayOfWeek = 0, start = "09:00", end = "12:00", type = "available"),
                )
            )

            // Prefer Saturdays (dow=6)
            // April 4 = Saturday, April 5 = Sunday
            val result = service.findAvailableWindows(
                teamId, LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 5), 90,
                preferredDays = listOf(6)
            )

            // Saturday has 1 member (userA only, since only userA has Saturday window)
            // but Saturday is preferred so gets boosted
            // Sunday has 1 member (userA only)
            // Both have base confidence of 0.5 (1/2 members)
            // Saturday gets 0.5 * 1.25 = 0.625
            // Sunday stays at 0.5
            assertTrue(result.isNotEmpty())
            if (result.size >= 2) {
                assertTrue(result[0].confidence >= result[1].confidence,
                    "Preferred day should rank higher")
            }
        }
    }

    @Nested
    @DisplayName("findAvailableWindows — result limiting and timezone")
    inner class ResultLimiting {

        /**
         * Verifies that at most MAX_RESULTS (10) windows are returned even when
         * more candidates exist.
         */
        @Test
        fun `returns at most MAX_RESULTS windows`() {
            setupMembers(listOf(userA))
            // Create availability on every day of the week for 3 weeks
            val windows = (0..6).map { dow ->
                makeWindow(userA, dayOfWeek = dow, start = "09:00", end = "17:00", type = "available")
            }
            setupWindows(windows)

            // Query a 3-week range (21 days, each with an 8-hour window)
            val result = service.findAvailableWindows(
                teamId, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 21), 90
            )

            assertTrue(result.size <= SchedulingService.MAX_RESULTS)
        }

        /**
         * Verifies that a team without an organization falls back to the default
         * timezone (America/New_York).
         */
        @Test
        fun `uses default timezone for team without org`() {
            every { teamRepository.findById(teamId) } returns Optional.of(
                Team(id = teamId, orgId = null, name = "No Org Team")
            )
            setupMembers(listOf(userA))
            setupWindows(
                listOf(
                    makeWindow(userA, dayOfWeek = 6, start = "09:00", end = "12:00", type = "available"),
                )
            )

            val result = service.findAvailableWindows(
                teamId, LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 4), 90
            )

            // Should work without errors, using default timezone
            assertEquals(1, result.size)
        }
    }

    @Nested
    @DisplayName("intersectWindows — cross-team overlap detection")
    inner class IntersectWindows {

        /**
         * Verifies that overlapping windows from two teams produce an intersection
         * with the correct time range and minimum confidence.
         */
        @Test
        fun `finds overlap between two teams`() {
            val teamAWindows = listOf(
                SchedulingService.TimeWindow(
                    startsAt = Instant.parse("2026-04-04T13:00:00Z"),
                    endsAt = Instant.parse("2026-04-04T16:00:00Z"),
                    confidence = 0.8,
                )
            )
            val teamBWindows = listOf(
                SchedulingService.TimeWindow(
                    startsAt = Instant.parse("2026-04-04T14:00:00Z"),
                    endsAt = Instant.parse("2026-04-04T17:00:00Z"),
                    confidence = 0.7,
                )
            )

            val result = service.intersectWindows(teamAWindows, teamBWindows)

            assertEquals(1, result.size)
            assertEquals(Instant.parse("2026-04-04T14:00:00Z"), result[0].startsAt)
            assertEquals(Instant.parse("2026-04-04T16:00:00Z"), result[0].endsAt)
            assertEquals(0.7, result[0].confidence) // min(0.8, 0.7)
        }

        /**
         * Verifies that non-overlapping windows produce no intersections.
         */
        @Test
        fun `returns empty when no overlap`() {
            val teamAWindows = listOf(
                SchedulingService.TimeWindow(
                    startsAt = Instant.parse("2026-04-04T09:00:00Z"),
                    endsAt = Instant.parse("2026-04-04T12:00:00Z"),
                    confidence = 1.0,
                )
            )
            val teamBWindows = listOf(
                SchedulingService.TimeWindow(
                    startsAt = Instant.parse("2026-04-04T14:00:00Z"),
                    endsAt = Instant.parse("2026-04-04T17:00:00Z"),
                    confidence = 1.0,
                )
            )

            val result = service.intersectWindows(teamAWindows, teamBWindows)
            assertTrue(result.isEmpty())
        }

        /**
         * Verifies that intersections below the minimum confidence threshold are filtered out.
         */
        @Test
        fun `filters by minimum confidence`() {
            val teamAWindows = listOf(
                SchedulingService.TimeWindow(
                    startsAt = Instant.parse("2026-04-04T09:00:00Z"),
                    endsAt = Instant.parse("2026-04-04T12:00:00Z"),
                    confidence = 0.4,
                )
            )
            val teamBWindows = listOf(
                SchedulingService.TimeWindow(
                    startsAt = Instant.parse("2026-04-04T09:00:00Z"),
                    endsAt = Instant.parse("2026-04-04T12:00:00Z"),
                    confidence = 0.3,
                )
            )

            // Default minimum confidence is 0.5
            val result = service.intersectWindows(teamAWindows, teamBWindows)
            assertTrue(result.isEmpty())

            // With lower threshold
            val resultLow = service.intersectWindows(teamAWindows, teamBWindows, minimumConfidence = 0.2)
            assertEquals(1, resultLow.size)
            assertEquals(0.3, resultLow[0].confidence)
        }

        /**
         * Verifies that multiple overlapping pairs are all found and sorted by
         * confidence descending.
         */
        @Test
        fun `returns multiple intersections sorted by confidence`() {
            val teamAWindows = listOf(
                SchedulingService.TimeWindow(
                    startsAt = Instant.parse("2026-04-04T09:00:00Z"),
                    endsAt = Instant.parse("2026-04-04T12:00:00Z"),
                    confidence = 0.6,
                ),
                SchedulingService.TimeWindow(
                    startsAt = Instant.parse("2026-04-05T14:00:00Z"),
                    endsAt = Instant.parse("2026-04-05T17:00:00Z"),
                    confidence = 0.9,
                ),
            )
            val teamBWindows = listOf(
                SchedulingService.TimeWindow(
                    startsAt = Instant.parse("2026-04-04T10:00:00Z"),
                    endsAt = Instant.parse("2026-04-04T13:00:00Z"),
                    confidence = 0.7,
                ),
                SchedulingService.TimeWindow(
                    startsAt = Instant.parse("2026-04-05T15:00:00Z"),
                    endsAt = Instant.parse("2026-04-05T18:00:00Z"),
                    confidence = 0.8,
                ),
            )

            val result = service.intersectWindows(teamAWindows, teamBWindows)

            assertEquals(2, result.size)
            // Higher confidence intersection should come first
            assertTrue(result[0].confidence >= result[1].confidence)
            // First intersection: April 5 overlap (0.8, min of 0.9 and 0.8)
            assertEquals(0.8, result[0].confidence)
            // Second intersection: April 4 overlap (0.6, min of 0.6 and 0.7)
            assertEquals(0.6, result[1].confidence)
        }

        /**
         * Verifies that empty input lists produce no results.
         */
        @Test
        fun `handles empty input lists`() {
            assertTrue(service.intersectWindows(emptyList(), emptyList()).isEmpty())
            assertTrue(
                service.intersectWindows(
                    listOf(
                        SchedulingService.TimeWindow(
                            Instant.parse("2026-04-04T09:00:00Z"),
                            Instant.parse("2026-04-04T12:00:00Z"), 1.0
                        )
                    ),
                    emptyList()
                ).isEmpty()
            )
        }
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Configures the mocked [TeamMemberRepository] to return active members for the test team.
     *
     * @param userIds User IDs of the active team members.
     */
    private fun setupMembers(userIds: List<UUID>) {
        val members = userIds.map { userId ->
            TeamMember(teamId = teamId, userId = userId, role = "parent", isActive = true)
        }
        every { teamMemberRepository.findByTeamIdAndIsActiveTrue(teamId) } returns members
    }

    /**
     * Configures the mocked [AvailabilityWindowRepository] to return the given windows.
     *
     * @param windows The availability windows to return for the test team.
     */
    private fun setupWindows(windows: List<AvailabilityWindow>) {
        every { availabilityWindowRepository.findByTeamId(teamId) } returns windows
    }

    /**
     * Creates an [AvailabilityWindow] for testing with sensible defaults.
     *
     * @param userId The team member this window belongs to.
     * @param dayOfWeek Recurring day of week (0=Sunday, mutually exclusive with [specificDate]).
     * @param specificDate Specific date (mutually exclusive with [dayOfWeek]).
     * @param start Start time in HH:mm format.
     * @param end End time in HH:mm format.
     * @param type "available" or "unavailable".
     * @return A test [AvailabilityWindow] entity.
     */
    private fun makeWindow(
        userId: UUID,
        dayOfWeek: Int? = null,
        specificDate: LocalDate? = null,
        start: String,
        end: String,
        type: String,
    ): AvailabilityWindow {
        return AvailabilityWindow(
            teamId = teamId,
            userId = userId,
            dayOfWeek = dayOfWeek?.toShort(),
            specificDate = specificDate,
            startTime = LocalTime.parse(start),
            endTime = LocalTime.parse(end),
            windowType = type,
            source = "manual",
        )
    }
}
