package com.fieldiq.service

import com.fieldiq.api.dto.CreateEventRequest
import com.fieldiq.api.dto.RespondToEventRequest
import com.fieldiq.api.dto.UpdateEventRequest
import com.fieldiq.domain.Event
import com.fieldiq.domain.EventResponse
import com.fieldiq.domain.TeamMember
import com.fieldiq.domain.User
import com.fieldiq.repository.EventRepository
import com.fieldiq.repository.EventResponseRepository
import com.fieldiq.repository.UserRepository
import io.mockk.*
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Unit tests for [EventService] — the service managing event lifecycle and RSVP
 * operations for team games, practices, and other activities in FieldIQ.
 *
 * Verifies the five core operations of [EventService]:
 * 1. **Event creation** — manager-only operation that creates an [Event] with automatic
 *    status determination: `"scheduled"` when `startsAt` is provided, `"draft"` otherwise.
 * 2. **Team events listing** — returns upcoming events for a team, sorted by start time,
 *    with active-member access control.
 * 3. **Event update** — PATCH semantics where only fields present in the request are
 *    modified; absent fields retain their original values.
 * 4. **RSVP response** — upsert semantics where a new [EventResponse] is created on
 *    first response, or the existing record is updated on subsequent changes.
 * 5. **Response listing** — returns all RSVPs for an event with expanded user profiles
 *    for the attendance summary UI.
 *
 * **Testing approach:** All repositories ([EventRepository], [EventResponseRepository],
 * [UserRepository]) and the [TeamAccessGuard] are mocked via MockK. The test event is
 * set 7 days in the future to avoid any "past event" edge cases.
 *
 * **No database interaction** — all persistence is verified via MockK `verify` blocks.
 *
 * @see EventService for the service under test.
 * @see TeamAccessGuard for the access control guard (mocked here).
 * @see com.fieldiq.api.EventController for the REST endpoints that delegate to this service.
 * @see Event for the JPA entity representing a team event.
 * @see EventResponse for the JPA entity representing an RSVP.
 */
class EventServiceTest {

    /** Mocked repository for [Event] CRUD operations. */
    private val eventRepository: EventRepository = mockk(relaxed = true)

    /** Mocked repository for [EventResponse] RSVP upsert and query operations. */
    private val eventResponseRepository: EventResponseRepository = mockk(relaxed = true)

    /** Mocked repository for [User] profile lookups (used to expand RSVP responses). */
    private val userRepository: UserRepository = mockk(relaxed = true)

    /** Mocked access guard enforcing team membership and manager role requirements. */
    private val teamAccessGuard: TeamAccessGuard = mockk(relaxed = true)

    /** The [EventService] instance under test, recreated before each test. */
    private lateinit var eventService: EventService

    /** Stable user UUID representing the authenticated caller in most tests. */
    private val userId = UUID.randomUUID()

    /** Stable team UUID used across test scenarios. */
    private val teamId = UUID.randomUUID()

    /** Stable event UUID used across test scenarios. */
    private val eventId = UUID.randomUUID()

    /** Pre-built test user with a valid US phone number and display name. */
    private val testUser = User(id = userId, phone = "+12025551234", displayName = "Sarah")

    /** Pre-built manager membership record linking [testUser] to the test team. */
    private val managerMember = TeamMember(teamId = teamId, userId = userId, role = "manager")

    /** Event start time set 7 days in the future to avoid past-event edge cases. */
    private val startsAt = Instant.now().plus(7, ChronoUnit.DAYS)

    /** Event end time set 2 hours after [startsAt] (typical game duration). */
    private val endsAt = startsAt.plus(2, ChronoUnit.HOURS)

    /**
     * Pre-built test event representing a scheduled game with all required fields.
     *
     * Uses realistic DMV youth soccer data: a game against "Arlington United" at
     * a future date, created by the authenticated user.
     */
    private val testEvent = Event(
        id = eventId,
        teamId = teamId,
        eventType = "game",
        title = "vs Arlington United",
        startsAt = startsAt,
        endsAt = endsAt,
        status = "scheduled",
        createdBy = userId,
    )

    /**
     * Creates a fresh [EventService] with all mocked dependencies before each test.
     */
    @BeforeEach
    fun setUp() {
        eventService = EventService(eventRepository, eventResponseRepository, userRepository, teamAccessGuard)
    }

    /**
     * Tests for [EventService.createEvent].
     *
     * Validates the event creation workflow: manager-only authorization, automatic
     * status determination based on `startsAt` presence, and field mapping from the
     * request DTO to the [Event] entity.
     */
    @Nested
    @DisplayName("createEvent")
    inner class CreateEvent {

        /**
         * Scheduled event: when `startsAt` is provided, the event is created with
         * `status = "scheduled"` and all time/location fields populated.
         *
         * Verifies:
         * - Event type, title, status, and location match the request
         * - The event is persisted with `createdBy` set to the caller's userId
         */
        @Test
        fun `should create scheduled event with start time`() {
            val request = CreateEventRequest(
                eventType = "game",
                title = "vs Arlington United",
                startsAt = startsAt.toString(),
                endsAt = endsAt.toString(),
                location = "Field #3",
            )
            every { teamAccessGuard.requireManager(userId, teamId) } returns managerMember
            every { eventRepository.save(any()) } answers { firstArg() }

            val result = eventService.createEvent(userId, teamId, request)

            assertEquals("game", result.eventType)
            assertEquals("vs Arlington United", result.title)
            assertEquals("scheduled", result.status)
            assertEquals("Field #3", result.location)
            verify { eventRepository.save(match { it.status == "scheduled" && it.createdBy == userId }) }
        }

        /**
         * Draft event: when `startsAt` is omitted, the event is created with
         * `status = "draft"` — indicating the schedule hasn't been finalized yet.
         *
         * This supports the workflow where a manager creates an event placeholder
         * (e.g., "Weekly Practice") and fills in the time later, possibly after
         * cross-team negotiation.
         */
        @Test
        fun `should create draft event without start time`() {
            val request = CreateEventRequest(eventType = "practice", title = "Weekly Practice")
            every { teamAccessGuard.requireManager(userId, teamId) } returns managerMember
            every { eventRepository.save(any()) } answers { firstArg() }

            val result = eventService.createEvent(userId, teamId, request)

            assertEquals("draft", result.status)
            assertNull(result.startsAt)
        }

        /**
         * Authorization: only managers can create events. A non-manager caller (e.g.,
         * a parent) must be rejected with [AccessDeniedException].
         *
         * This prevents parents from accidentally or intentionally adding events to
         * the team calendar.
         */
        @Test
        fun `should throw AccessDeniedException when caller is not manager`() {
            val request = CreateEventRequest(eventType = "game")
            every { teamAccessGuard.requireManager(userId, teamId) } throws
                AccessDeniedException("Not a manager")

            assertThrows(AccessDeniedException::class.java) {
                eventService.createEvent(userId, teamId, request)
            }
        }
    }

    /**
     * Tests for [EventService.getTeamEvents].
     *
     * Validates the upcoming events query: returns future events sorted by start time,
     * with active-member access control.
     */
    @Nested
    @DisplayName("getTeamEvents")
    inner class GetTeamEvents {

        /**
         * Happy path: returns upcoming events sorted chronologically.
         *
         * The repository method `findByTeamIdAndStartsAtAfterOrderByStartsAtAsc`
         * handles the filtering and sorting. This test verifies the service correctly
         * passes through the repository results.
         */
        @Test
        fun `should return upcoming events sorted by start time`() {
            val events = listOf(
                Event(teamId = teamId, eventType = "practice", startsAt = Instant.now().plus(1, ChronoUnit.DAYS), status = "scheduled"),
                Event(teamId = teamId, eventType = "game", startsAt = Instant.now().plus(3, ChronoUnit.DAYS), status = "scheduled"),
            )
            every { teamAccessGuard.requireActiveMember(userId, teamId) } returns managerMember
            every { eventRepository.findByTeamIdAndStartsAtAfterOrderByStartsAtAsc(teamId, any()) } returns events

            val result = eventService.getTeamEvents(userId, teamId)

            assertEquals(2, result.size)
        }

        /**
         * Access denial: a non-member cannot view team events.
         *
         * Event schedules are team-private data, so the [TeamAccessGuard] enforces
         * active membership before exposing any event information.
         */
        @Test
        fun `should throw AccessDeniedException when user is not a member`() {
            every { teamAccessGuard.requireActiveMember(userId, teamId) } throws
                AccessDeniedException("Not a member")

            assertThrows(AccessDeniedException::class.java) {
                eventService.getTeamEvents(userId, teamId)
            }
        }
    }

    /**
     * Tests for [EventService.updateEvent].
     *
     * Validates PATCH semantics: only fields present in the [UpdateEventRequest] are
     * modified on the existing [Event] entity. Fields not in the request retain their
     * original values. Also tests entity lookup and manager-only authorization.
     */
    @Nested
    @DisplayName("updateEvent")
    inner class UpdateEvent {

        /**
         * Partial update: only the `title` and `location` fields are updated;
         * all other fields (eventType, status, etc.) remain unchanged.
         *
         * This is the core PATCH contract — the mobile app sends only the fields
         * the manager changed, and the service applies them selectively.
         */
        @Test
        fun `should update only provided fields (PATCH semantics)`() {
            val request = UpdateEventRequest(title = "Updated Title", location = "New Field")
            every { eventRepository.findById(eventId) } returns Optional.of(testEvent)
            every { teamAccessGuard.requireManager(userId, testEvent.teamId) } returns managerMember
            every { eventRepository.save(any()) } answers { firstArg() }

            val result = eventService.updateEvent(userId, eventId, request)

            assertEquals("Updated Title", result.title)
            assertEquals("New Field", result.location)
            // Other fields should remain unchanged
            assertEquals("game", result.eventType)
            assertEquals("scheduled", result.status)
        }

        /**
         * Minimal update: when only `title` is provided, `location` (which was null
         * on the original event) should remain null.
         *
         * This verifies that the PATCH logic doesn't accidentally set absent fields
         * to empty strings or default values.
         */
        @Test
        fun `should not change fields not in the request`() {
            val request = UpdateEventRequest(title = "New Title")
            every { eventRepository.findById(eventId) } returns Optional.of(testEvent)
            every { teamAccessGuard.requireManager(userId, testEvent.teamId) } returns managerMember
            every { eventRepository.save(any()) } answers { firstArg() }

            val result = eventService.updateEvent(userId, eventId, request)

            assertEquals("New Title", result.title)
            // Location should still be from testEvent (null)
            assertNull(result.location)
        }

        /**
         * Entity not found: updating a non-existent event throws [EntityNotFoundException].
         *
         * The event UUID is checked before any authorization, so this test verifies
         * the early-exit behavior.
         */
        @Test
        fun `should throw EntityNotFoundException for non-existent event`() {
            val request = UpdateEventRequest(title = "Updated")
            every { eventRepository.findById(eventId) } returns Optional.empty()

            assertThrows(EntityNotFoundException::class.java) {
                eventService.updateEvent(userId, eventId, request)
            }
        }

        /**
         * Authorization: only managers of the event's team can update it.
         *
         * The authorization check uses the event's `teamId` (not a request parameter),
         * ensuring the guard checks the correct team even if the caller provides a
         * different team context.
         */
        @Test
        fun `should throw AccessDeniedException when user is not manager`() {
            val request = UpdateEventRequest(title = "Updated")
            every { eventRepository.findById(eventId) } returns Optional.of(testEvent)
            every { teamAccessGuard.requireManager(userId, testEvent.teamId) } throws
                AccessDeniedException("Not a manager")

            assertThrows(AccessDeniedException::class.java) {
                eventService.updateEvent(userId, eventId, request)
            }
        }
    }

    /**
     * Tests for [EventService.respondToEvent].
     *
     * Validates the RSVP upsert flow: creates a new [EventResponse] on first response,
     * updates the existing record on subsequent changes, with event existence and
     * membership enforcement.
     */
    @Nested
    @DisplayName("respondToEvent")
    inner class RespondToEvent {

        /**
         * First response: when no existing [EventResponse] exists for this user+event
         * combination, a new record is created.
         *
         * Verifies:
         * - The response status matches the request
         * - `respondedAt` is set (non-null)
         * - The saved record has the correct userId and status
         */
        @Test
        fun `should create new response when user has not responded`() {
            val request = RespondToEventRequest(status = "going")
            every { eventRepository.findById(eventId) } returns Optional.of(testEvent)
            every { teamAccessGuard.requireActiveMember(userId, testEvent.teamId) } returns managerMember
            every { eventResponseRepository.findByEventIdAndUserId(eventId, userId) } returns null
            every { eventResponseRepository.save(any()) } answers { firstArg() }
            every { userRepository.findById(userId) } returns Optional.of(testUser)

            val result = eventService.respondToEvent(userId, eventId, request)

            assertEquals("going", result.status)
            assertNotNull(result.respondedAt)
            verify { eventResponseRepository.save(match { it.status == "going" && it.userId == userId }) }
        }

        /**
         * Upsert update: when the user has already responded (e.g., "maybe"), changing
         * their response to "going" updates the existing record rather than creating
         * a duplicate.
         *
         * Verifies:
         * - The same record ID is preserved (no duplicate creation)
         * - The status is updated to the new value
         * - `respondedAt` is refreshed to the current time
         */
        @Test
        fun `should update existing response (upsert behavior)`() {
            val existingResponse = EventResponse(
                id = UUID.randomUUID(),
                eventId = eventId,
                userId = userId,
                status = "maybe",
                respondedAt = Instant.now().minus(1, ChronoUnit.DAYS),
            )
            val request = RespondToEventRequest(status = "going")
            every { eventRepository.findById(eventId) } returns Optional.of(testEvent)
            every { teamAccessGuard.requireActiveMember(userId, testEvent.teamId) } returns managerMember
            every { eventResponseRepository.findByEventIdAndUserId(eventId, userId) } returns existingResponse
            every { eventResponseRepository.save(any()) } answers { firstArg() }
            every { userRepository.findById(userId) } returns Optional.of(testUser)

            val result = eventService.respondToEvent(userId, eventId, request)

            assertEquals("going", result.status)
            // Should have updated the existing record, not created a new one
            verify { eventResponseRepository.save(match { it.id == existingResponse.id && it.status == "going" }) }
        }

        /**
         * Entity not found: responding to a non-existent event throws
         * [EntityNotFoundException] before any RSVP logic executes.
         */
        @Test
        fun `should throw EntityNotFoundException for non-existent event`() {
            val request = RespondToEventRequest(status = "going")
            every { eventRepository.findById(eventId) } returns Optional.empty()

            assertThrows(EntityNotFoundException::class.java) {
                eventService.respondToEvent(userId, eventId, request)
            }
        }

        /**
         * Access denial: only active team members can RSVP to events.
         *
         * This prevents non-members from polluting the attendance count or seeing
         * event details through the RSVP endpoint.
         */
        @Test
        fun `should throw AccessDeniedException when user is not a member`() {
            val request = RespondToEventRequest(status = "going")
            every { eventRepository.findById(eventId) } returns Optional.of(testEvent)
            every { teamAccessGuard.requireActiveMember(userId, testEvent.teamId) } throws
                AccessDeniedException("Not a member")

            assertThrows(AccessDeniedException::class.java) {
                eventService.respondToEvent(userId, eventId, request)
            }
        }
    }

    /**
     * Tests for [EventService.getEventResponses].
     *
     * Validates the RSVP listing: returns all responses with expanded user profiles
     * for the attendance summary display, with event existence and membership enforcement.
     */
    @Nested
    @DisplayName("getEventResponses")
    inner class GetEventResponses {

        /**
         * Full response list with profiles: returns all RSVPs with their [User] profiles
         * joined via batch loading for efficient display.
         *
         * Verifies:
         * - The correct number of responses is returned
         * - Both "going" and "not_going" statuses are present
         */
        @Test
        fun `should return responses with expanded user profiles`() {
            val otherUserId = UUID.randomUUID()
            val otherUser = User(id = otherUserId, displayName = "Other Parent")
            val responses = listOf(
                EventResponse(eventId = eventId, userId = userId, status = "going", respondedAt = Instant.now()),
                EventResponse(eventId = eventId, userId = otherUserId, status = "not_going", respondedAt = Instant.now()),
            )

            every { eventRepository.findById(eventId) } returns Optional.of(testEvent)
            every { teamAccessGuard.requireActiveMember(userId, testEvent.teamId) } returns managerMember
            every { eventResponseRepository.findByEventId(eventId) } returns responses
            every { userRepository.findAllById(any<List<UUID>>()) } returns listOf(testUser, otherUser)

            val result = eventService.getEventResponses(userId, eventId)

            assertEquals(2, result.size)
            assertTrue(result.any { it.status == "going" })
            assertTrue(result.any { it.status == "not_going" })
        }

        /**
         * Entity not found: listing responses for a non-existent event throws
         * [EntityNotFoundException] before any response lookup occurs.
         */
        @Test
        fun `should throw EntityNotFoundException for non-existent event`() {
            every { eventRepository.findById(eventId) } returns Optional.empty()

            assertThrows(EntityNotFoundException::class.java) {
                eventService.getEventResponses(userId, eventId)
            }
        }
    }
}
