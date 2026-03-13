package com.fieldiq.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fieldiq.api.dto.ConfirmNegotiationRequest
import com.fieldiq.api.dto.IncomingNegotiationRequest
import com.fieldiq.api.dto.InitiateNegotiationRequest
import com.fieldiq.api.dto.JoinSessionRequest
import com.fieldiq.api.dto.RelayRequest
import com.fieldiq.api.dto.RelayResponse
import com.fieldiq.api.dto.RelaySlot
import com.fieldiq.api.dto.RespondToProposalRequest
import com.fieldiq.api.dto.TimeSlotRequest
import com.fieldiq.config.FieldIQProperties
import com.fieldiq.domain.Event
import com.fieldiq.domain.NegotiationEvent
import com.fieldiq.domain.NegotiationProposal
import com.fieldiq.domain.NegotiationSession
import com.fieldiq.repository.EventRepository
import com.fieldiq.repository.NegotiationEventRepository
import com.fieldiq.repository.NegotiationProposalRepository
import com.fieldiq.repository.NegotiationSessionRepository
import com.fieldiq.security.HmacAuthenticationFilter
import com.fieldiq.security.HmacService
import com.fieldiq.security.JwtService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.persistence.EntityNotFoundException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.security.access.AccessDeniedException
import java.sql.Date
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Unit tests for [NegotiationService] — the core orchestration service for FieldIQ's
 * cross-team scheduling negotiation protocol.
 *
 * Tests cover the full negotiation lifecycle:
 * 1. **Session creation** (initiate with invite token)
 * 2. **Session retrieval** (with access control)
 * 3. **Join handshake** (token validation, key derivation, Redis caching)
 * 4. **State machine enforcement** (allowed and disallowed transitions)
 * 5. **Proposal generation** (scheduling integration, relay dispatch)
 * 6. **Incoming relay processing** (intersection, match detection, counter-proposals, max rounds)
 * 7. **Confirmation** (event creation, terminal transition)
 * 8. **Response handling** (proposal response status update)
 * 9. **Cancellation** (terminal transition, relay notification)
 *
 * **Testing approach:** All dependencies are mocked via MockK. The service is tested
 * via its public methods, asserting on return values, captured repository save arguments,
 * and relay client invocations.
 *
 * @see NegotiationService for the service under test.
 * @see SchedulingService for the availability computation dependency.
 * @see CrossInstanceRelayClient for the outbound relay client dependency.
 */
class NegotiationServiceTest {

    private val sessionRepo: NegotiationSessionRepository = mockk()
    private val proposalRepo: NegotiationProposalRepository = mockk()
    private val negotiationEventRepo: NegotiationEventRepository = mockk()
    private val eventRepository: EventRepository = mockk()
    private val schedulingService: SchedulingService = mockk()
    private val relayClient: CrossInstanceRelayClient = mockk(relaxed = true)
    private val teamAccessGuard: TeamAccessGuard = mockk()
    private val hmacService: HmacService = mockk()
    private val redisTemplate: StringRedisTemplate = mockk()
    private val valueOps: ValueOperations<String, String> = mockk()
    private val namedParameterJdbcTemplate: NamedParameterJdbcTemplate = mockk()

    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    private lateinit var service: NegotiationService

    // Common test data
    private val managerId = UUID.randomUUID()
    private val teamAId = UUID.randomUUID()
    private val teamBId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val instanceAUrl = "http://localhost:8080"
    private val instanceBUrl = "http://localhost:8081"
    private val sessionKey = ByteArray(32) { it.toByte() }
    private val sessionKeyBase64 = Base64.getEncoder().encodeToString(sessionKey)

    /**
     * Creates the service and sets up common mock behavior before each test.
     */
    @BeforeEach
    fun setup() {
        every { redisTemplate.opsForValue() } returns valueOps
        every { negotiationEventRepo.save(any()) } answers { firstArg() }
        every { valueOps.set(any(), any(), any<Duration>()) } returns Unit
        every { namedParameterJdbcTemplate.update(any<String>(), any<MapSqlParameterSource>()) } returns 1

        val properties = FieldIQProperties(
            instance = FieldIQProperties.InstanceProperties(
                id = "instance-a",
                secret = "test-secret-key-for-hmac-derivation",
                baseUrl = instanceAUrl,
            ),
            jwt = FieldIQProperties.JwtProperties(secret = "test-jwt-secret"),
            aws = FieldIQProperties.AwsProperties(),
        )
        val jwtService = JwtService(properties)

        service = NegotiationService(
            sessionRepo = sessionRepo,
            proposalRepo = proposalRepo,
            negotiationEventRepo = negotiationEventRepo,
            eventRepository = eventRepository,
            schedulingService = schedulingService,
            relayClient = relayClient,
            teamAccessGuard = teamAccessGuard,
            hmacService = hmacService,
            jwtService = jwtService,
            properties = properties,
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
            namedParameterJdbcTemplate = namedParameterJdbcTemplate,
        )
    }

    // ========================================================================
    // Phase B: Session Lifecycle
    // ========================================================================

    @Nested
    @DisplayName("initiateNegotiation")
    inner class InitiateNegotiation {

        /**
         * Happy path: creates a session with status "pending_response" and a generated invite token.
         */
        @Test
        @DisplayName("creates session with invite token and pending_response status")
        fun happyPath() {
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            val sessionSlot = slot<NegotiationSession>()
            every { sessionRepo.save(capture(sessionSlot)) } answers { firstArg() }

            val request = InitiateNegotiationRequest(
                teamId = teamAId,
                dateRangeStart = "2026-04-01",
                dateRangeEnd = "2026-04-15",
                durationMinutes = 90,
            )

            val result = service.initiateNegotiation(managerId, request)

            assertEquals("pending_response", result.status)
            assertNotNull(result.inviteToken)
            assertEquals(teamAId.toString(), result.initiatorTeamId)
            assertEquals(90, result.requestedDurationMinutes)
        }

        /**
         * The session should have a 48-hour expiry from creation time.
         */
        @Test
        @DisplayName("sets 48-hour expiration on the session")
        fun setsExpiry() {
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            val sessionSlot = slot<NegotiationSession>()
            every { sessionRepo.save(capture(sessionSlot)) } answers { firstArg() }

            val before = Instant.now()
            val request = InitiateNegotiationRequest(
                teamId = teamAId,
                dateRangeStart = "2026-04-01",
                dateRangeEnd = "2026-04-15",
            )

            service.initiateNegotiation(managerId, request)

            val saved = sessionSlot.captured
            assertNotNull(saved.expiresAt)
            val expectedMin = before.plus(47, ChronoUnit.HOURS)
            val expectedMax = before.plus(49, ChronoUnit.HOURS)
            assertTrue(saved.expiresAt!!.isAfter(expectedMin), "Expiry should be ~48h from now")
            assertTrue(saved.expiresAt!!.isBefore(expectedMax), "Expiry should be ~48h from now")
        }

        /**
         * The initiator's instance base URL should be recorded on the session.
         */
        @Test
        @DisplayName("stores initiator instance URL from FieldIQProperties")
        fun storesInstanceUrl() {
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            val sessionSlot = slot<NegotiationSession>()
            every { sessionRepo.save(capture(sessionSlot)) } answers { firstArg() }

            val request = InitiateNegotiationRequest(
                teamId = teamAId,
                dateRangeStart = "2026-04-01",
                dateRangeEnd = "2026-04-15",
            )

            service.initiateNegotiation(managerId, request)

            assertEquals(instanceAUrl, sessionSlot.captured.initiatorInstance)
        }

        /**
         * A "session_created" audit event should be logged.
         */
        @Test
        @DisplayName("logs session_created audit event")
        fun logsAuditEvent() {
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            every { sessionRepo.save(any()) } answers { firstArg() }

            val request = InitiateNegotiationRequest(
                teamId = teamAId,
                dateRangeStart = "2026-04-01",
                dateRangeEnd = "2026-04-15",
            )

            service.initiateNegotiation(managerId, request)

            val eventSlot = slot<NegotiationEvent>()
            verify { negotiationEventRepo.save(capture(eventSlot)) }
            assertEquals("session_created", eventSlot.captured.eventType)
            assertEquals("initiator", eventSlot.captured.actor)
        }

        /**
         * Non-managers should be rejected by TeamAccessGuard.
         */
        @Test
        @DisplayName("rejects non-manager with AccessDeniedException")
        fun rejectsNonManager() {
            every { teamAccessGuard.requireManager(managerId, teamAId) } throws
                AccessDeniedException("Not a manager")

            val request = InitiateNegotiationRequest(
                teamId = teamAId,
                dateRangeStart = "2026-04-01",
                dateRangeEnd = "2026-04-15",
            )

            assertThrows(AccessDeniedException::class.java) {
                service.initiateNegotiation(managerId, request)
            }
        }
    }

    @Nested
    @DisplayName("getSession")
    inner class GetSession {

        /**
         * Happy path: returns the session with proposals for an authorized user.
         */
        @Test
        @DisplayName("returns session with proposals for authorized user")
        fun happyPath() {
            val session = makeSession(status = "proposing", responderTeamId = teamBId)
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            every { proposalRepo.findBySessionId(sessionId) } returns emptyList()

            val result = service.getSession(sessionId, managerId)

            assertEquals(sessionId.toString(), result.id)
            assertEquals("proposing", result.status)
            assertTrue(result.proposals.isEmpty())
        }

        /**
         * Non-member user should be rejected.
         */
        @Test
        @DisplayName("rejects user who is not a manager of either team")
        fun rejectsNonMember() {
            val otherUser = UUID.randomUUID()
            val session = makeSession(status = "proposing", responderTeamId = teamBId)
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(otherUser, teamAId) } throws
                AccessDeniedException("Not a manager")
            every { teamAccessGuard.requireManager(otherUser, teamBId) } throws
                AccessDeniedException("Not a manager")

            assertThrows(AccessDeniedException::class.java) {
                service.getSession(sessionId, otherUser)
            }
        }
    }

    @Nested
    @DisplayName("createShadowSession")
    inner class CreateShadowSession {

        /**
         * Bootstraps a responder-side shadow session using the caller-supplied remote UUID.
         */
        @Test
        @DisplayName("persists a new shadow session with the remote session id")
        fun persistsShadowSessionAsNew() {
            every { sessionRepo.findById(sessionId) } returns Optional.empty()
            every { hmacService.deriveSessionKey("invite-token") } returns sessionKey

            val paramSource = slot<MapSqlParameterSource>()
            every { namedParameterJdbcTemplate.update(any<String>(), capture(paramSource)) } answers {
                1
            }

            val savedEvent = slot<NegotiationEvent>()
            every { negotiationEventRepo.save(capture(savedEvent)) } answers { firstArg() }

            val request = IncomingNegotiationRequest(
                sessionId = sessionId,
                inviteToken = "invite-token",
                initiatorTeamId = teamAId,
                initiatorInstance = instanceAUrl,
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
                requestedDateRangeStart = "2026-04-01",
                requestedDateRangeEnd = "2026-04-15",
                requestedDurationMinutes = 90,
                maxRounds = 3,
                expiresAt = Instant.now().plus(48, ChronoUnit.HOURS).toString(),
            )

            val result = service.createShadowSession(request)

            assertEquals(sessionId.toString(), result.id)
            assertEquals(sessionId, paramSource.captured.getValue("id"))
            assertEquals(teamAId, paramSource.captured.getValue("initiatorTeamId"))
            assertEquals(teamBId, paramSource.captured.getValue("responderTeamId"))
            assertEquals("proposing", paramSource.captured.getValue("status"))
            assertEquals(Date.valueOf("2026-04-01"), paramSource.captured.getValue("requestedDateRangeStart"))
            assertEquals(Date.valueOf("2026-04-15"), paramSource.captured.getValue("requestedDateRangeEnd"))
            assertTrue(paramSource.captured.getValue("expiresAt") is Timestamp)
            assertTrue(paramSource.captured.getValue("createdAt") is Timestamp)
            assertTrue(paramSource.captured.getValue("updatedAt") is Timestamp)
            assertEquals(sessionId, savedEvent.captured.sessionId)
            verify { namedParameterJdbcTemplate.update(any<String>(), any<MapSqlParameterSource>()) }
        }
    }

    @Nested
    @DisplayName("joinSession")
    inner class JoinSession {

        /**
         * Happy path: valid invite token transitions session to "proposing".
         */
        @Test
        @DisplayName("transitions session to proposing on valid token")
        fun happyPath() {
            val inviteToken = "valid-invite-token-abc123"
            val session = makeSession(
                status = "pending_response",
                inviteToken = inviteToken,
                expiresAt = Instant.now().plus(24, ChronoUnit.HOURS),
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { hmacService.deriveSessionKey(inviteToken) } returns sessionKey
            every { valueOps.set(any(), any(), any<Duration>()) } returns Unit
            every { teamAccessGuard.requireManager(managerId, teamBId) } returns mockk()
            val savedSlot = slot<NegotiationSession>()
            every { sessionRepo.save(capture(savedSlot)) } answers { firstArg() }

            val request = JoinSessionRequest(
                inviteToken = inviteToken,
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )

            val result = service.joinSession(sessionId, managerId, request)

            assertEquals("proposing", result.status)
            assertEquals(teamBId.toString(), result.responderTeamId)
        }

        /**
         * The invite token should be nullified after consumption.
         */
        @Test
        @DisplayName("nullifies invite token after consumption")
        fun nullifiesToken() {
            val inviteToken = "valid-invite-token-abc123"
            val session = makeSession(
                status = "pending_response",
                inviteToken = inviteToken,
                expiresAt = Instant.now().plus(24, ChronoUnit.HOURS),
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { hmacService.deriveSessionKey(inviteToken) } returns sessionKey
            every { valueOps.set(any(), any(), any<Duration>()) } returns Unit
            every { teamAccessGuard.requireManager(managerId, teamBId) } returns mockk()
            val savedSlot = slot<NegotiationSession>()
            every { sessionRepo.save(capture(savedSlot)) } answers { firstArg() }

            val request = JoinSessionRequest(
                inviteToken = inviteToken,
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )

            service.joinSession(sessionId, managerId, request)

            assertNull(savedSlot.captured.inviteToken)
        }

        /**
         * The derived session key should be cached in Redis with 72h TTL.
         */
        @Test
        @DisplayName("caches session key in Redis with 72h TTL")
        fun cachesSessionKey() {
            val inviteToken = "valid-invite-token-abc123"
            val session = makeSession(
                status = "pending_response",
                inviteToken = inviteToken,
                expiresAt = Instant.now().plus(24, ChronoUnit.HOURS),
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { hmacService.deriveSessionKey(inviteToken) } returns sessionKey
            every { valueOps.set(any(), any(), any<Duration>()) } returns Unit
            every { teamAccessGuard.requireManager(managerId, teamBId) } returns mockk()
            every { sessionRepo.save(any()) } answers { firstArg() }

            val request = JoinSessionRequest(
                inviteToken = inviteToken,
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )

            service.joinSession(sessionId, managerId, request)

            verify {
                valueOps.set(
                    "${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId",
                    sessionKeyBase64,
                    HmacAuthenticationFilter.SESSION_KEY_TTL,
                )
            }
        }

        /**
         * An invalid invite token should be rejected.
         */
        @Test
        @DisplayName("rejects invalid invite token")
        fun rejectsInvalidToken() {
            val session = makeSession(
                status = "pending_response",
                inviteToken = "correct-token",
                expiresAt = Instant.now().plus(24, ChronoUnit.HOURS),
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)

            val request = JoinSessionRequest(
                inviteToken = "wrong-token",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )

            assertThrows(IllegalArgumentException::class.java) {
                service.joinSession(sessionId, managerId, request)
            }
        }

        /**
         * An expired session should transition to "failed" and throw.
         */
        @Test
        @DisplayName("rejects expired session and transitions to failed")
        fun rejectsExpired() {
            val inviteToken = "valid-token"
            val session = makeSession(
                status = "pending_response",
                inviteToken = inviteToken,
                expiresAt = Instant.now().minus(1, ChronoUnit.HOURS),
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { sessionRepo.save(any()) } answers { firstArg() }

            val request = JoinSessionRequest(
                inviteToken = inviteToken,
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )

            assertThrows(InvalidStateTransitionException::class.java) {
                service.joinSession(sessionId, managerId, request)
            }
        }

        /**
         * A "responder_joined" audit event should be logged.
         */
        @Test
        @DisplayName("logs responder_joined audit event")
        fun logsAuditEvent() {
            val inviteToken = "valid-invite-token"
            val session = makeSession(
                status = "pending_response",
                inviteToken = inviteToken,
                expiresAt = Instant.now().plus(24, ChronoUnit.HOURS),
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { hmacService.deriveSessionKey(inviteToken) } returns sessionKey
            every { valueOps.set(any(), any(), any<Duration>()) } returns Unit
            every { teamAccessGuard.requireManager(managerId, teamBId) } returns mockk()
            every { sessionRepo.save(any()) } answers { firstArg() }

            service.joinSession(
                sessionId,
                managerId,
                JoinSessionRequest(inviteToken, teamBId, instanceBUrl),
            )

            val events = mutableListOf<NegotiationEvent>()
            verify { negotiationEventRepo.save(capture(events)) }
            assertTrue(events.any { it.eventType == "responder_joined" })
        }

        /**
         * Same-instance joins must still authorize the responder team locally.
         */
        @Test
        @DisplayName("rejects non-manager of same-instance responder team with AccessDeniedException")
        fun rejectsNonManagerResponder() {
            val inviteToken = "valid-invite-token"
            val nonManagerId = UUID.randomUUID()
            val session = makeSession(
                status = "pending_response",
                inviteToken = inviteToken,
                expiresAt = Instant.now().plus(24, ChronoUnit.HOURS),
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(nonManagerId, teamBId) } throws
                AccessDeniedException("Not a manager of responder team")

            val request = JoinSessionRequest(
                inviteToken = inviteToken,
                responderTeamId = teamBId,
                responderInstance = instanceAUrl,
            )

            assertThrows(AccessDeniedException::class.java) {
                service.joinSession(sessionId, nonManagerId, request)
            }
        }

        /**
         * Cross-instance joins should not attempt to authorize the remote responder team locally.
         */
        @Test
        @DisplayName("skips local responder-team authorization for cross-instance joins")
        fun skipsLocalAuthorizationForCrossInstanceJoin() {
            val inviteToken = "valid-invite-token"
            val session = makeSession(
                status = "pending_response",
                inviteToken = inviteToken,
                expiresAt = Instant.now().plus(24, ChronoUnit.HOURS),
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { hmacService.deriveSessionKey(inviteToken) } returns sessionKey
            every { sessionRepo.save(any()) } answers { firstArg() }

            service.joinSession(
                sessionId,
                managerId,
                JoinSessionRequest(inviteToken, teamBId, instanceBUrl),
            )

            verify(exactly = 0) { teamAccessGuard.requireManager(managerId, teamBId) }
        }

        /**
         * Join should fail and token should remain unconsumed if sendIncoming() throws.
         */
        @Test
        @DisplayName("join fails if sendIncoming throws, token stays valid")
        fun joinFailsIfSendIncomingThrows() {
            val inviteToken = "valid-invite-token"
            val session = makeSession(
                status = "pending_response",
                inviteToken = inviteToken,
                expiresAt = Instant.now().plus(24, ChronoUnit.HOURS),
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamBId) } returns mockk()
            every { relayClient.sendIncoming(instanceBUrl, any()) } throws
                RelayException("Instance B unreachable")

            val request = JoinSessionRequest(
                inviteToken = inviteToken,
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )

            assertThrows(RelayException::class.java) {
                service.joinSession(sessionId, managerId, request)
            }

            // Token should NOT be consumed — no save with null inviteToken
            verify(exactly = 0) { sessionRepo.save(any()) }
        }
    }

    @Nested
    @DisplayName("state machine enforcement")
    inner class StateMachine {

        /**
         * Valid transitions should not throw.
         */
        @Test
        @DisplayName("allows valid transitions")
        fun allowedTransitions() {
            // pending_response → proposing (tested via joinSession above)
            // proposing → pending_approval (tested via processIncomingRelay match)
            // pending_approval → confirmed (tested via confirmAgreement)
            // These are all covered by individual method tests.
            // Here we verify the explicit disallowed case.
        }

        /**
         * Invalid transitions should throw InvalidStateTransitionException.
         */
        @Test
        @DisplayName("rejects invalid state transitions")
        fun rejectsInvalidTransition() {
            // Cannot confirm a session in "proposing" status
            val session = makeSession(status = "proposing", responderTeamId = teamBId)
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)

            assertThrows(InvalidStateTransitionException::class.java) {
                service.confirmAgreement(
                    sessionId,
                    managerId,
                    ConfirmNegotiationRequest(
                        slot = TimeSlotRequest(
                            startsAt = Instant.now(),
                            endsAt = Instant.now().plus(90, ChronoUnit.MINUTES),
                        ),
                    ),
                )
            }
        }

        /**
         * Terminal states (confirmed, failed, cancelled) should reject all transitions.
         */
        @Test
        @DisplayName("rejects transitions from terminal states")
        fun terminalStates() {
            for (terminalStatus in listOf("confirmed", "failed", "cancelled")) {
                val session = makeSession(status = terminalStatus, responderTeamId = teamBId)
                every { sessionRepo.findById(sessionId) } returns Optional.of(session)
                every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()

                assertThrows(InvalidStateTransitionException::class.java) {
                    service.cancelSession(sessionId, managerId)
                }
            }
        }
    }

    // ========================================================================
    // Phase C: Proposal Exchange
    // ========================================================================

    @Nested
    @DisplayName("generateAndSendProposal")
    inner class GenerateAndSendProposal {

        /**
         * Happy path: calls SchedulingService, stores proposal, relays to counterpart.
         */
        @Test
        @DisplayName("generates proposal, stores it, and relays to counterpart")
        fun happyPath() {
            val session = makeSession(
                status = "proposing",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()

            val windows = listOf(
                SchedulingService.TimeWindow(
                    startsAt = Instant.parse("2026-04-04T14:00:00Z"),
                    endsAt = Instant.parse("2026-04-04T15:30:00Z"),
                    confidence = 0.9,
                ),
            )
            every {
                schedulingService.findAvailableWindows(
                    teamId = teamAId,
                    dateRangeStart = any(),
                    dateRangeEnd = any(),
                    durationMinutes = 90,
                )
            } returns windows

            every { proposalRepo.save(any()) } answers { firstArg() }
            every { sessionRepo.save(any()) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64
            every { relayClient.sendRelay(any(), any(), any(), any()) } returns
                RelayResponse(sessionStatus = "proposing", currentRound = 1)

            val result = service.generateAndSendProposal(sessionId, managerId)

            assertEquals(1, result.roundNumber)
            assertEquals("initiator", result.proposedBy)
            assertEquals(1, result.slots.size)

            verify { relayClient.sendRelay(instanceBUrl, sessionId.toString(), sessionKey, any()) }
        }

        /**
         * The session's currentRound should be incremented.
         */
        @Test
        @DisplayName("increments session currentRound")
        fun incrementsRound() {
            val session = makeSession(
                status = "proposing",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
                currentRound = 1,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            every {
                schedulingService.findAvailableWindows(any(), any(), any(), any())
            } returns listOf(
                SchedulingService.TimeWindow(
                    Instant.parse("2026-04-04T14:00:00Z"),
                    Instant.parse("2026-04-04T15:30:00Z"),
                    0.9,
                ),
            )
            every { proposalRepo.save(any()) } answers { firstArg() }
            val sessionSaveSlot = slot<NegotiationSession>()
            every { sessionRepo.save(capture(sessionSaveSlot)) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64
            every { relayClient.sendRelay(any(), any(), any(), any()) } returns
                RelayResponse(sessionStatus = "proposing", currentRound = 2)

            service.generateAndSendProposal(sessionId, managerId)

            // First save is the round increment; relay response may trigger a second save
            assertTrue(sessionSaveSlot.captured.currentRound >= 2)
        }

        /**
         * Cannot propose when session is not in "proposing" status.
         */
        @Test
        @DisplayName("rejects proposal when session is not in proposing status")
        fun rejectsNonProposing() {
            val session = makeSession(status = "pending_approval", responderTeamId = teamBId)
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)

            assertThrows(IllegalArgumentException::class.java) {
                service.generateAndSendProposal(sessionId, managerId)
            }
        }

        /**
         * A "proposal_sent" audit event should be logged.
         */
        @Test
        @DisplayName("logs proposal_sent audit event")
        fun logsAuditEvent() {
            val session = makeSession(
                status = "proposing",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            every {
                schedulingService.findAvailableWindows(any(), any(), any(), any())
            } returns listOf(
                SchedulingService.TimeWindow(
                    Instant.parse("2026-04-04T14:00:00Z"),
                    Instant.parse("2026-04-04T15:30:00Z"),
                    0.9,
                ),
            )
            every { proposalRepo.save(any()) } answers { firstArg() }
            every { sessionRepo.save(any()) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64
            every { relayClient.sendRelay(any(), any(), any(), any()) } returns
                RelayResponse(sessionStatus = "proposing", currentRound = 1)

            service.generateAndSendProposal(sessionId, managerId)

            val events = mutableListOf<NegotiationEvent>()
            verify(atLeast = 1) { negotiationEventRepo.save(capture(events)) }
            assertTrue(events.any { it.eventType == "proposal_sent" })
        }

        /**
         * When the relay returns "pending_approval", the local session should
         * transition to pending_approval with the agreed slot details.
         */
        @Test
        @DisplayName("transitions to pending_approval when relay returns match")
        fun transitionsToMatchWhenRelayReturnsPendingApproval() {
            val session = makeSession(
                status = "proposing",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            every {
                schedulingService.findAvailableWindows(any(), any(), any(), any())
            } returns listOf(
                SchedulingService.TimeWindow(
                    Instant.parse("2026-04-04T14:00:00Z"),
                    Instant.parse("2026-04-04T15:30:00Z"),
                    0.9,
                ),
            )
            every { proposalRepo.save(any()) } answers { firstArg() }
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64

            val agreedStart = Instant.parse("2026-04-04T14:00:00Z")
            val agreedEnd = Instant.parse("2026-04-04T15:30:00Z")
            every { relayClient.sendRelay(any(), any(), any(), any()) } returns
                RelayResponse(
                    sessionStatus = "pending_approval",
                    currentRound = 1,
                    agreedStartsAt = agreedStart,
                    agreedEndsAt = agreedEnd,
                )

            service.generateAndSendProposal(sessionId, managerId)

            // The last save should be the match transition
            val matchedSession = savedSessions.last()
            assertEquals("pending_approval", matchedSession.status)
            assertEquals(agreedStart, matchedSession.agreedStartsAt)
            assertEquals(agreedEnd, matchedSession.agreedEndsAt)
        }

        /**
         * When the relay returns "failed", the local session should transition to failed.
         */
        @Test
        @DisplayName("transitions to failed when relay returns failed")
        fun transitionsToFailedWhenRelayReturnsFailed() {
            val session = makeSession(
                status = "proposing",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
                maxRounds = 3,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            every {
                schedulingService.findAvailableWindows(any(), any(), any(), any())
            } returns listOf(
                SchedulingService.TimeWindow(
                    Instant.parse("2026-04-04T14:00:00Z"),
                    Instant.parse("2026-04-04T15:30:00Z"),
                    0.9,
                ),
            )
            every { proposalRepo.save(any()) } answers { firstArg() }
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64
            every { relayClient.sendRelay(any(), any(), any(), any()) } returns
                RelayResponse(sessionStatus = "failed", currentRound = 3)

            service.generateAndSendProposal(sessionId, managerId)

            val failedSession = savedSessions.last()
            assertEquals("failed", failedSession.status)
            assertEquals(3, failedSession.currentRound)
        }

        /**
         * When the relay returns "proposing" (counter coming), no local state change needed.
         */
        @Test
        @DisplayName("stays in proposing when relay returns proposing (counter incoming)")
        fun staysInProposingWhenRelayReturnsProposing() {
            val session = makeSession(
                status = "proposing",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            every {
                schedulingService.findAvailableWindows(any(), any(), any(), any())
            } returns listOf(
                SchedulingService.TimeWindow(
                    Instant.parse("2026-04-04T14:00:00Z"),
                    Instant.parse("2026-04-04T15:30:00Z"),
                    0.9,
                ),
            )
            every { proposalRepo.save(any()) } answers { firstArg() }
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64
            every { relayClient.sendRelay(any(), any(), any(), any()) } returns
                RelayResponse(sessionStatus = "proposing", currentRound = 2)

            service.generateAndSendProposal(sessionId, managerId)

            // Only one session save: the round increment. No additional state change.
            assertEquals(1, savedSessions.size)
            assertEquals("proposing", savedSessions.first().status)
        }
    }

    @Nested
    @DisplayName("processIncomingRelay — propose action")
    inner class ProcessIncomingProposal {

        /**
         * When incoming slots overlap with local availability, session should transition
         * to "pending_approval".
         */
        @Test
        @DisplayName("transitions to pending_approval when match found")
        fun matchFound() {
            val session = makeSession(status = "proposing", responderTeamId = teamBId)
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { proposalRepo.findBySessionIdAndRoundNumber(sessionId, 1) } returns emptyList()
            every { proposalRepo.save(any()) } answers { firstArg() }

            val incomingSlots = listOf(
                RelaySlot(
                    startsAt = Instant.parse("2026-04-04T14:00:00Z"),
                    endsAt = Instant.parse("2026-04-04T15:30:00Z"),
                ),
            )

            // Local availability overlaps with incoming
            val localWindows = listOf(
                SchedulingService.TimeWindow(
                    Instant.parse("2026-04-04T13:00:00Z"),
                    Instant.parse("2026-04-04T16:00:00Z"),
                    0.9,
                ),
            )
            every {
                schedulingService.findAvailableWindows(any(), any(), any(), any())
            } returns localWindows

            // Intersection returns a match
            every {
                schedulingService.intersectWindows(any(), any())
            } returns listOf(
                SchedulingService.TimeWindow(
                    Instant.parse("2026-04-04T14:00:00Z"),
                    Instant.parse("2026-04-04T15:30:00Z"),
                    0.9,
                ),
            )

            val savedSession = slot<NegotiationSession>()
            every { sessionRepo.save(capture(savedSession)) } answers { firstArg() }

            val relay = RelayRequest(
                action = "propose",
                roundNumber = 1,
                proposalId = UUID.randomUUID().toString(),
                actor = "initiator",
                slots = incomingSlots,
            )

            val result = service.processIncomingRelay(sessionId, relay)

            assertEquals("pending_approval", result.sessionStatus)
            assertEquals("pending_approval", savedSession.captured.status)
            // Verify agreed slot is included in relay response
            assertEquals(Instant.parse("2026-04-04T14:00:00Z"), result.agreedStartsAt)
            assertEquals(Instant.parse("2026-04-04T15:30:00Z"), result.agreedEndsAt)
            // Verify agreed slot is persisted on session
            assertEquals(Instant.parse("2026-04-04T14:00:00Z"), savedSession.captured.agreedStartsAt)
            assertEquals(Instant.parse("2026-04-04T15:30:00Z"), savedSession.captured.agreedEndsAt)
        }

        /**
         * When no match and rounds remain, should auto-counter with local availability.
         */
        @Test
        @DisplayName("auto-counters when no match and rounds remain")
        fun autoCounter() {
            val session = makeSession(
                status = "proposing",
                responderTeamId = teamBId,
                maxRounds = 3,
                initiatorInstance = instanceAUrl,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { proposalRepo.findBySessionIdAndRoundNumber(sessionId, 1) } returns emptyList()
            every { proposalRepo.save(any()) } answers { firstArg() }
            every { sessionRepo.save(any()) } answers { firstArg() }

            // Local windows don't overlap with incoming
            every {
                schedulingService.findAvailableWindows(any(), any(), any(), any())
            } returns listOf(
                SchedulingService.TimeWindow(
                    Instant.parse("2026-04-05T09:00:00Z"),
                    Instant.parse("2026-04-05T12:00:00Z"),
                    0.8,
                ),
            )
            every {
                schedulingService.intersectWindows(any(), any())
            } returns emptyList()

            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64
            every { relayClient.sendRelay(instanceAUrl, sessionId.toString(), sessionKey, any()) } returns
                RelayResponse(sessionStatus = "proposing", currentRound = 2)

            val relay = RelayRequest(
                action = "propose",
                roundNumber = 1,
                proposalId = UUID.randomUUID().toString(),
                actor = "initiator",
                slots = listOf(
                    RelaySlot(
                        Instant.parse("2026-04-04T14:00:00Z"),
                        Instant.parse("2026-04-04T15:30:00Z"),
                    ),
                ),
            )

            val result = service.processIncomingRelay(sessionId, relay)

            assertEquals("proposing", result.sessionStatus)
            assertEquals(2, result.currentRound)

            // Should have saved both the incoming proposal and the counter-proposal
            verify(atLeast = 2) { proposalRepo.save(any()) }
        }

        /**
         * Auto-counter should propagate the agreed slot when the downstream relay finds a match.
         */
        @Test
        @DisplayName("auto-counter returns agreed slot when downstream relay finds match")
        fun autoCounterPropagatesAgreedSlot() {
            val session = makeSession(
                status = "proposing",
                responderTeamId = teamBId,
                initiatorInstance = instanceAUrl,
                maxRounds = 3,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { proposalRepo.findBySessionIdAndRoundNumber(sessionId, 1) } returns emptyList()
            every { proposalRepo.save(any()) } answers { firstArg() }
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }
            every { schedulingService.findAvailableWindows(any(), any(), any(), any()) } returns listOf(
                SchedulingService.TimeWindow(
                    Instant.parse("2026-04-05T09:00:00Z"),
                    Instant.parse("2026-04-05T10:30:00Z"),
                    0.8,
                ),
            )
            every { schedulingService.intersectWindows(any(), any()) } returns emptyList()
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64
            every { relayClient.sendRelay(instanceAUrl, sessionId.toString(), sessionKey, any()) } returns
                RelayResponse(
                    sessionStatus = "pending_approval",
                    currentRound = 2,
                    agreedStartsAt = Instant.parse("2026-04-05T09:00:00Z"),
                    agreedEndsAt = Instant.parse("2026-04-05T10:30:00Z"),
                )

            val relay = RelayRequest(
                action = "propose",
                roundNumber = 1,
                proposalId = UUID.randomUUID().toString(),
                actor = "initiator",
                slots = listOf(
                    RelaySlot(
                        Instant.parse("2026-04-04T14:00:00Z"),
                        Instant.parse("2026-04-04T15:30:00Z"),
                    ),
                ),
            )

            val result = service.processIncomingRelay(sessionId, relay)

            assertEquals("pending_approval", result.sessionStatus)
            assertEquals(Instant.parse("2026-04-05T09:00:00Z"), result.agreedStartsAt)
            assertEquals(Instant.parse("2026-04-05T10:30:00Z"), result.agreedEndsAt)
            assertEquals(Instant.parse("2026-04-05T10:30:00Z"), savedSessions.last().agreedEndsAt)
        }

        /**
         * When no match and max rounds exceeded, session should transition to "failed".
         */
        @Test
        @DisplayName("transitions to failed when max rounds exceeded")
        fun maxRoundsExceeded() {
            val session = makeSession(
                status = "proposing",
                responderTeamId = teamBId,
                maxRounds = 3,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { proposalRepo.findBySessionIdAndRoundNumber(sessionId, 3) } returns emptyList()
            every { proposalRepo.save(any()) } answers { firstArg() }

            every {
                schedulingService.findAvailableWindows(any(), any(), any(), any())
            } returns listOf(
                SchedulingService.TimeWindow(
                    Instant.parse("2026-04-05T09:00:00Z"),
                    Instant.parse("2026-04-05T12:00:00Z"),
                    0.8,
                ),
            )
            every {
                schedulingService.intersectWindows(any(), any())
            } returns emptyList()

            val savedSession = slot<NegotiationSession>()
            every { sessionRepo.save(capture(savedSession)) } answers { firstArg() }

            val relay = RelayRequest(
                action = "propose",
                roundNumber = 3, // equals maxRounds
                proposalId = UUID.randomUUID().toString(),
                actor = "initiator",
                slots = listOf(
                    RelaySlot(
                        Instant.parse("2026-04-04T14:00:00Z"),
                        Instant.parse("2026-04-04T15:30:00Z"),
                    ),
                ),
            )

            val result = service.processIncomingRelay(sessionId, relay)

            assertEquals("failed", result.sessionStatus)
            assertEquals("failed", savedSession.captured.status)
        }

        /**
         * Duplicate proposals (same session, round, actor) should be idempotently ignored.
         */
        @Test
        @DisplayName("ignores duplicate proposals idempotently")
        fun idempotent() {
            val session = makeSession(status = "proposing", responderTeamId = teamBId)
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)

            // Existing proposal from same actor and round
            val existingProposal = NegotiationProposal(
                sessionId = sessionId,
                proposedBy = "initiator",
                roundNumber = 1,
                slots = "[]",
            )
            every { proposalRepo.findBySessionIdAndRoundNumber(sessionId, 1) } returns
                listOf(existingProposal)

            val relay = RelayRequest(
                action = "propose",
                roundNumber = 1,
                proposalId = UUID.randomUUID().toString(),
                actor = "initiator",
                slots = emptyList(),
            )

            val result = service.processIncomingRelay(sessionId, relay)

            assertEquals("proposing", result.sessionStatus)
            // Should NOT save a new proposal
            verify(exactly = 0) { proposalRepo.save(any()) }
        }

        /**
         * Unknown relay action should throw IllegalArgumentException.
         */
        @Test
        @DisplayName("rejects unknown relay action")
        fun unknownAction() {
            val session = makeSession(status = "proposing", responderTeamId = teamBId)
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)

            val relay = RelayRequest(
                action = "unknown_action",
                roundNumber = 1,
                proposalId = UUID.randomUUID().toString(),
                actor = "initiator",
            )

            assertThrows(IllegalArgumentException::class.java) {
                service.processIncomingRelay(sessionId, relay)
            }
        }

        /**
         * A "cancel" relay action should transition the session to cancelled.
         */
        @Test
        @DisplayName("cancel relay action transitions to cancelled")
        fun cancelAction() {
            val session = makeSession(status = "proposing", responderTeamId = teamBId)
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            val savedSession = slot<NegotiationSession>()
            every { sessionRepo.save(capture(savedSession)) } answers { firstArg() }

            val relay = RelayRequest(
                action = "cancel",
                roundNumber = 1,
                proposalId = UUID.randomUUID().toString(),
                actor = "initiator",
            )

            val result = service.processIncomingRelay(sessionId, relay)

            assertEquals("cancelled", result.sessionStatus)
            assertEquals("cancelled", savedSession.captured.status)
        }
    }

    // ========================================================================
    // Phase D: Confirm / Respond / Cancel
    // ========================================================================

    @Nested
    @DisplayName("confirmAgreement — dual confirmation")
    inner class ConfirmAgreement {

        private val confirmRequest = ConfirmNegotiationRequest(
            slot = TimeSlotRequest(
                startsAt = Instant.parse("2026-04-04T14:00:00Z"),
                endsAt = Instant.parse("2026-04-04T15:30:00Z"),
                location = "Bethesda Field #3",
            ),
        )

        /**
         * First confirm sets the actor's flag but stays in pending_approval. No event created.
         */
        @Test
        @DisplayName("first confirm sets flag, stays in pending_approval, no event")
        fun firstConfirmSetsFlag() {
            val session = makeSession(
                status = "pending_approval",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64

            val result = service.confirmAgreement(sessionId, managerId, confirmRequest)

            assertEquals("pending_approval", result.status)
            assertTrue(result.initiatorConfirmed)
            assertFalse(result.responderConfirmed)
            // No event should be created — only one side confirmed
            verify(exactly = 0) { eventRepository.save(any()) }
        }

        /**
         * Second confirm (both flags true) transitions to confirmed and creates event.
         */
        @Test
        @DisplayName("second confirm transitions to confirmed and creates event")
        fun secondConfirmCreatesEvent() {
            // Session where responder already confirmed
            val session = makeSession(
                status = "pending_approval",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
                responderConfirmed = true,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            every { eventRepository.findByTeamIdAndNegotiationId(teamAId, sessionId) } returns null
            every { eventRepository.save(any()) } answers { firstArg() }
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64

            val result = service.confirmAgreement(sessionId, managerId, confirmRequest)

            assertEquals("confirmed", result.status)
            assertTrue(result.initiatorConfirmed)
            assertTrue(result.responderConfirmed)
            // Event should be created
            verify(exactly = 1) { eventRepository.save(any()) }
        }

        /**
         * Duplicate confirm from the same actor is a no-op.
         */
        @Test
        @DisplayName("duplicate local confirm is no-op")
        fun duplicateConfirmIsNoOp() {
            val session = makeSession(
                status = "pending_approval",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
                initiatorConfirmed = true,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()

            val result = service.confirmAgreement(sessionId, managerId, confirmRequest)

            assertEquals("pending_approval", result.status)
            // No session save or event creation
            verify(exactly = 0) { sessionRepo.save(any()) }
            verify(exactly = 0) { eventRepository.save(any()) }
        }

        /**
         * Incoming confirm relay sets remote flag. No event if only one flag true.
         */
        @Test
        @DisplayName("handleIncomingConfirm sets remote flag, no event when one-sided")
        fun incomingConfirmSetsFlag() {
            val session = makeSession(
                status = "pending_approval",
                responderTeamId = teamBId,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }

            val relay = RelayRequest(
                action = "confirm",
                roundNumber = 1,
                proposalId = UUID.randomUUID().toString(),
                actor = "responder",
                slots = listOf(RelaySlot(
                    Instant.parse("2026-04-04T14:00:00Z"),
                    Instant.parse("2026-04-04T15:30:00Z"),
                    "Bethesda Field #3",
                )),
            )

            val result = service.processIncomingRelay(sessionId, relay)

            assertEquals("pending_approval", result.sessionStatus)
            assertTrue(savedSessions.last().responderConfirmed)
            assertFalse(savedSessions.last().initiatorConfirmed)
            verify(exactly = 0) { eventRepository.save(any()) }
        }

        /**
         * Incoming confirm relay creates event when both flags become true.
         */
        @Test
        @DisplayName("handleIncomingConfirm creates event when both confirmed")
        fun incomingConfirmCreatesEvent() {
            val session = makeSession(
                status = "pending_approval",
                responderTeamId = teamBId,
                initiatorConfirmed = true,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }
            // When relay.actor == "responder", local team = initiator = teamAId
            every { eventRepository.findByTeamIdAndNegotiationId(teamAId, sessionId) } returns null
            every { eventRepository.save(any()) } answers { firstArg() }

            val relay = RelayRequest(
                action = "confirm",
                roundNumber = 1,
                proposalId = UUID.randomUUID().toString(),
                actor = "responder",
                slots = listOf(RelaySlot(
                    Instant.parse("2026-04-04T14:00:00Z"),
                    Instant.parse("2026-04-04T15:30:00Z"),
                    "Bethesda Field #3",
                )),
            )

            val result = service.processIncomingRelay(sessionId, relay)

            assertEquals("confirmed", result.sessionStatus)
            assertEquals("confirmed", savedSessions.last().status)
            verify(exactly = 1) { eventRepository.save(any()) }
        }

        /**
         * Shadow-session confirmations should create a system event without using a team UUID as createdBy.
         */
        @Test
        @DisplayName("handleIncomingConfirm creates remote event with null createdBy when initiator manager is unknown")
        fun incomingConfirmCreatesEventWithNullCreatedBy() {
            val session = makeSession(
                status = "pending_approval",
                responderTeamId = teamBId,
                initiatorConfirmed = true,
                initiatorManager = null,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { sessionRepo.save(any()) } answers { firstArg() }
            every { eventRepository.findByTeamIdAndNegotiationId(teamAId, sessionId) } returns null
            val savedEvent = slot<Event>()
            every { eventRepository.save(capture(savedEvent)) } answers { firstArg() }

            val relay = RelayRequest(
                action = "confirm",
                roundNumber = 1,
                proposalId = UUID.randomUUID().toString(),
                actor = "responder",
                slots = listOf(RelaySlot(
                    Instant.parse("2026-04-04T14:00:00Z"),
                    Instant.parse("2026-04-04T15:30:00Z"),
                    "Bethesda Field #3",
                )),
            )

            service.processIncomingRelay(sessionId, relay)

            assertNull(savedEvent.captured.createdBy)
        }

        /**
         * Confirm persists agreed slot including endsAt.
         */
        @Test
        @DisplayName("confirm persists agreed slot including endsAt")
        fun persistsAgreedSlot() {
            val session = makeSession(
                status = "pending_approval",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64

            service.confirmAgreement(sessionId, managerId, confirmRequest)

            val saved = savedSessions.last()
            assertEquals(Instant.parse("2026-04-04T14:00:00Z"), saved.agreedStartsAt)
            assertEquals(Instant.parse("2026-04-04T15:30:00Z"), saved.agreedEndsAt)
            assertEquals("Bethesda Field #3", saved.agreedLocation)
        }

        /**
         * Confirmation relay failure should not prevent local confirmation from succeeding.
         */
        @Test
        @DisplayName("succeeds even if relay to remote instance fails")
        fun relayFailureDoesNotBlock() {
            val session = makeSession(
                status = "pending_approval",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            every { sessionRepo.save(any()) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64
            every {
                relayClient.sendRelay(any(), any(), any(), any())
            } throws RuntimeException("Connection refused")

            // Should not throw — first confirm sets flag
            val result = service.confirmAgreement(sessionId, managerId, confirmRequest)
            assertEquals("pending_approval", result.status)
        }
    }

    @Nested
    @DisplayName("respondToProposal")
    inner class RespondToProposal {

        /**
         * Happy path: updates the latest counterpart proposal's response status.
         */
        @Test
        @DisplayName("updates proposal response status")
        fun happyPath() {
            val session = makeSession(
                status = "proposing",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()

            val counterpartProposal = NegotiationProposal(
                sessionId = sessionId,
                proposedBy = "responder",
                roundNumber = 1,
                slots = objectMapper.writeValueAsString(
                    listOf(
                        RelaySlot(
                            Instant.parse("2026-04-04T14:00:00Z"),
                            Instant.parse("2026-04-04T15:30:00Z"),
                        ),
                    ),
                ),
            )
            every { proposalRepo.findBySessionId(sessionId) } returns listOf(counterpartProposal)
            val savedProposal = slot<NegotiationProposal>()
            every { proposalRepo.save(capture(savedProposal)) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64

            val request = RespondToProposalRequest(
                responseStatus = "rejected",
                rejectionReason = "no_availability",
            )

            service.respondToProposal(sessionId, managerId, request)

            assertEquals("rejected", savedProposal.captured.responseStatus)
            assertEquals("no_availability", savedProposal.captured.rejectionReason)
        }

        /**
         * Counter responses should create a local proposal record so proposal history stays symmetric.
         */
        @Test
        @DisplayName("records local counter proposal and increments round")
        fun recordsLocalCounterProposal() {
            val session = makeSession(
                status = "proposing",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
                currentRound = 1,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()

            val counterpartProposal = NegotiationProposal(
                sessionId = sessionId,
                proposedBy = "responder",
                roundNumber = 1,
                slots = "[]",
            )
            every { proposalRepo.findBySessionId(sessionId) } returns listOf(counterpartProposal)
            val savedProposals = mutableListOf<NegotiationProposal>()
            every { proposalRepo.save(capture(savedProposals)) } answers { firstArg() }
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64
            every { relayClient.sendRelay(any(), any(), any(), any()) } returns
                RelayResponse(sessionStatus = "proposing", currentRound = 2)

            val request = RespondToProposalRequest(
                responseStatus = "countered",
                counterSlots = listOf(
                    TimeSlotRequest(
                        Instant.parse("2026-04-05T10:00:00Z"),
                        Instant.parse("2026-04-05T11:30:00Z"),
                        "Counter Field",
                    ),
                ),
            )

            service.respondToProposal(sessionId, managerId, request)

            assertEquals(2, savedProposals.size)
            assertEquals("countered", savedProposals.first().responseStatus)
            assertEquals("initiator", savedProposals.last().proposedBy)
            assertEquals(2, savedProposals.last().roundNumber)
            assertEquals(2, savedSessions.last().currentRound)
        }

        /**
         * Managers should be able to counter after a matched slot is found, which moves
         * the session back out of pending_approval and resumes proposal exchange.
         */
        @Test
        @DisplayName("counter from pending approval resets the agreed slot and returns to proposing")
        fun counterFromPendingApproval() {
            val session = makeSession(
                status = "pending_approval",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
                currentRound = 1,
                initiatorConfirmed = false,
                responderConfirmed = false,
            ).copy(
                agreedStartsAt = Instant.parse("2026-04-05T10:00:00Z"),
                agreedEndsAt = Instant.parse("2026-04-05T11:30:00Z"),
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()

            val counterpartProposal = NegotiationProposal(
                sessionId = sessionId,
                proposedBy = "responder",
                roundNumber = 1,
                slots = "[]",
            )
            every { proposalRepo.findBySessionId(sessionId) } returns listOf(counterpartProposal)
            val savedProposals = mutableListOf<NegotiationProposal>()
            every { proposalRepo.save(capture(savedProposals)) } answers { firstArg() }
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64
            every { relayClient.sendRelay(any(), any(), any(), any()) } returns
                RelayResponse(sessionStatus = "proposing", currentRound = 2)

            val request = RespondToProposalRequest(
                responseStatus = "countered",
                counterSlots = listOf(
                    TimeSlotRequest(
                        Instant.parse("2026-04-05T12:00:00Z"),
                        Instant.parse("2026-04-05T13:30:00Z"),
                        "Counter Field",
                    ),
                ),
            )

            service.respondToProposal(sessionId, managerId, request)

            assertEquals("proposing", savedSessions.last().status)
            assertNull(savedSessions.last().agreedStartsAt)
            assertNull(savedSessions.last().agreedEndsAt)
            assertFalse(savedSessions.last().initiatorConfirmed)
            assertFalse(savedSessions.last().responderConfirmed)
            assertEquals(2, savedSessions.last().currentRound)
            assertEquals("initiator", savedProposals.last().proposedBy)
        }

        /**
         * Managers can revise a matched slot they originally proposed, so pending-approval
         * counters must not require a prior counterpart proposal to exist locally.
         */
        @Test
        @DisplayName("counter from pending approval can revise a self-originated matched slot")
        fun counterFromPendingApprovalWithoutCounterpartProposal() {
            val session = makeSession(
                status = "pending_approval",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
                currentRound = 1,
            ).copy(
                agreedStartsAt = Instant.parse("2026-04-05T10:00:00Z"),
                agreedEndsAt = Instant.parse("2026-04-05T11:30:00Z"),
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()

            val localMatchedProposal = NegotiationProposal(
                sessionId = sessionId,
                proposedBy = "initiator",
                roundNumber = 1,
                slots = "[]",
            )
            every { proposalRepo.findBySessionId(sessionId) } returns listOf(localMatchedProposal)
            val savedProposals = mutableListOf<NegotiationProposal>()
            every { proposalRepo.save(capture(savedProposals)) } answers { firstArg() }
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64
            every { relayClient.sendRelay(any(), any(), any(), any()) } returns
                RelayResponse(sessionStatus = "proposing", currentRound = 2)

            val request = RespondToProposalRequest(
                responseStatus = "countered",
                counterSlots = listOf(
                    TimeSlotRequest(
                        Instant.parse("2026-04-05T12:00:00Z"),
                        Instant.parse("2026-04-05T13:30:00Z"),
                        "Counter Field",
                    ),
                ),
            )

            service.respondToProposal(sessionId, managerId, request)

            assertEquals(1, savedProposals.size)
            assertEquals("initiator", savedProposals.last().proposedBy)
            assertEquals(2, savedProposals.last().roundNumber)
            assertEquals("proposing", savedSessions.last().status)
            assertEquals(2, savedSessions.last().currentRound)
            assertNull(savedSessions.last().agreedStartsAt)
            assertNull(savedSessions.last().agreedEndsAt)
        }
    }

    @Nested
    @DisplayName("handleIncomingResponse — counter path")
    inner class HandleIncomingResponse {

        /**
         * Counter response with slots creates a proposal record.
         */
        @Test
        @DisplayName("counter creates proposal from counter slots")
        fun counterCreatesProposal() {
            val session = makeSession(status = "proposing", responderTeamId = teamBId, maxRounds = 3)
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { proposalRepo.findBySessionIdAndRoundNumber(sessionId, 1) } returns emptyList()
            every { proposalRepo.save(any()) } answers { firstArg() }
            every { sessionRepo.save(any()) } answers { firstArg() }
            every { schedulingService.findAvailableWindows(any(), any(), any(), any()) } returns emptyList()
            every { schedulingService.intersectWindows(any(), any()) } returns emptyList()

            val relay = RelayRequest(
                action = "respond",
                roundNumber = 1,
                proposalId = UUID.randomUUID().toString(),
                actor = "responder",
                responseStatus = "countered",
                slots = listOf(
                    RelaySlot(Instant.parse("2026-04-05T10:00:00Z"), Instant.parse("2026-04-05T11:30:00Z")),
                ),
            )

            service.processIncomingRelay(sessionId, relay)

            // Should save: 1) proposal status update (null proposal since none found), 2) counter proposal
            verify(atLeast = 1) { proposalRepo.save(any()) }
        }

        /**
         * Counter with overlapping slots finds match → pending_approval.
         */
        @Test
        @DisplayName("counter match transitions to pending_approval")
        fun counterMatchTransitions() {
            val session = makeSession(status = "proposing", responderTeamId = teamBId, maxRounds = 3)
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { proposalRepo.findBySessionIdAndRoundNumber(sessionId, 1) } returns emptyList()
            every { proposalRepo.save(any()) } answers { firstArg() }
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }

            val matchWindow = SchedulingService.TimeWindow(
                Instant.parse("2026-04-05T10:00:00Z"),
                Instant.parse("2026-04-05T11:30:00Z"),
                0.9,
            )
            every { schedulingService.findAvailableWindows(any(), any(), any(), any()) } returns listOf(matchWindow)
            every { schedulingService.intersectWindows(any(), any()) } returns listOf(matchWindow)

            val relay = RelayRequest(
                action = "respond",
                roundNumber = 1,
                proposalId = UUID.randomUUID().toString(),
                actor = "responder",
                responseStatus = "countered",
                slots = listOf(
                    RelaySlot(Instant.parse("2026-04-05T10:00:00Z"), Instant.parse("2026-04-05T11:30:00Z")),
                ),
            )

            val result = service.processIncomingRelay(sessionId, relay)

            assertEquals("pending_approval", result.sessionStatus)
            assertNotNull(result.agreedStartsAt)
        }

        /**
         * Counter at max rounds with no match → failed.
         */
        @Test
        @DisplayName("counter at max rounds with no match transitions to failed")
        fun counterAtMaxRoundsFails() {
            val session = makeSession(status = "proposing", responderTeamId = teamBId, maxRounds = 2)
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { proposalRepo.findBySessionIdAndRoundNumber(sessionId, 1) } returns emptyList()
            every { proposalRepo.save(any()) } answers { firstArg() }
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }
            every { schedulingService.findAvailableWindows(any(), any(), any(), any()) } returns emptyList()
            every { schedulingService.intersectWindows(any(), any()) } returns emptyList()

            val relay = RelayRequest(
                action = "respond",
                roundNumber = 1,
                proposalId = UUID.randomUUID().toString(),
                actor = "responder",
                responseStatus = "countered",
                slots = listOf(
                    RelaySlot(Instant.parse("2026-04-05T10:00:00Z"), Instant.parse("2026-04-05T11:30:00Z")),
                ),
            )

            val result = service.processIncomingRelay(sessionId, relay)

            assertEquals("failed", result.sessionStatus)
        }

        /**
         * When a counter arrives after both sides had a matched slot, the receiver must clear the
         * old agreement and move back to proposing so the next round can continue on both instances.
         */
        @Test
        @DisplayName("counter from pending approval clears agreed slot and reopens proposals")
        fun counterFromPendingApprovalReopensProposals() {
            val session = makeSession(
                status = "pending_approval",
                responderTeamId = teamBId,
                currentRound = 1,
                initiatorConfirmed = true,
                responderConfirmed = false,
            ).copy(
                agreedStartsAt = Instant.parse("2026-04-05T10:00:00Z"),
                agreedEndsAt = Instant.parse("2026-04-05T11:30:00Z"),
                agreedLocation = "Original Field",
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { proposalRepo.findBySessionIdAndRoundNumber(sessionId, 1) } returns emptyList()
            every { proposalRepo.save(any()) } answers { firstArg() }
            val savedSessions = mutableListOf<NegotiationSession>()
            every { sessionRepo.save(capture(savedSessions)) } answers { firstArg() }
            every { schedulingService.findAvailableWindows(any(), any(), any(), any()) } returns emptyList()
            every { schedulingService.intersectWindows(any(), any()) } returns emptyList()

            val relay = RelayRequest(
                action = "respond",
                roundNumber = 1,
                proposalId = UUID.randomUUID().toString(),
                actor = "initiator",
                responseStatus = "countered",
                slots = listOf(
                    RelaySlot(Instant.parse("2026-04-05T13:00:00Z"), Instant.parse("2026-04-05T14:30:00Z")),
                ),
            )

            val result = service.processIncomingRelay(sessionId, relay)

            assertEquals("proposing", result.sessionStatus)
            assertEquals(2, result.currentRound)
            assertEquals("proposing", savedSessions.last().status)
            assertEquals(2, savedSessions.last().currentRound)
            assertNull(savedSessions.last().agreedStartsAt)
            assertNull(savedSessions.last().agreedEndsAt)
            assertNull(savedSessions.last().agreedLocation)
            assertFalse(savedSessions.last().initiatorConfirmed)
            assertFalse(savedSessions.last().responderConfirmed)
        }

        /**
         * "accepted" response does not create a new proposal.
         */
        @Test
        @DisplayName("accepted response does not create counter proposal")
        fun acceptedNoCounter() {
            val existingProposal = NegotiationProposal(
                sessionId = sessionId,
                proposedBy = "initiator",
                roundNumber = 1,
                slots = "[]",
            )
            val session = makeSession(status = "proposing", responderTeamId = teamBId)
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { proposalRepo.findBySessionIdAndRoundNumber(sessionId, 1) } returns listOf(existingProposal)
            val savedProposals = mutableListOf<NegotiationProposal>()
            every { proposalRepo.save(capture(savedProposals)) } answers { firstArg() }

            val relay = RelayRequest(
                action = "respond",
                roundNumber = 1,
                proposalId = UUID.randomUUID().toString(),
                actor = "responder",
                responseStatus = "accepted",
            )

            service.processIncomingRelay(sessionId, relay)

            // Only the status update save, no new counter proposal
            assertEquals(1, savedProposals.size)
            assertEquals("accepted", savedProposals.first().responseStatus)
        }
    }

    @Nested
    @DisplayName("cancelSession")
    inner class CancelSession {

        /**
         * Happy path: transitions to cancelled and relays to remote instance.
         */
        @Test
        @DisplayName("transitions to cancelled and relays cancellation")
        fun happyPath() {
            val session = makeSession(
                status = "proposing",
                responderTeamId = teamBId,
                responderInstance = instanceBUrl,
            )
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            val savedSession = slot<NegotiationSession>()
            every { sessionRepo.save(capture(savedSession)) } answers { firstArg() }
            every {
                valueOps.get("${HmacAuthenticationFilter.SESSION_KEY_PREFIX}$sessionId")
            } returns sessionKeyBase64

            val result = service.cancelSession(sessionId, managerId)

            assertEquals("cancelled", result.status)
            assertEquals("cancelled", savedSession.captured.status)
            verify { relayClient.sendRelay(instanceBUrl, sessionId.toString(), sessionKey, any()) }
        }

        /**
         * Cancellation of a session in "pending_response" (no responder yet) should work
         * without trying to relay (no remote instance URL).
         */
        @Test
        @DisplayName("cancels pending_response session without relay")
        fun cancelsPendingWithoutRelay() {
            val session = makeSession(status = "pending_response")
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()
            every { sessionRepo.save(any()) } answers { firstArg() }

            val result = service.cancelSession(sessionId, managerId)

            assertEquals("cancelled", result.status)
            // No relay should be attempted (no responder instance)
            verify(exactly = 0) { relayClient.sendRelay(any(), any(), any(), any()) }
        }

        /**
         * Cannot cancel a session already in a terminal state.
         */
        @Test
        @DisplayName("rejects cancelling an already-confirmed session")
        fun rejectsTerminal() {
            val session = makeSession(status = "confirmed", responderTeamId = teamBId)
            every { sessionRepo.findById(sessionId) } returns Optional.of(session)
            every { teamAccessGuard.requireManager(managerId, teamAId) } returns mockk()

            assertThrows(InvalidStateTransitionException::class.java) {
                service.cancelSession(sessionId, managerId)
            }
        }
    }

    // ========================================================================
    // Test Helpers
    // ========================================================================

    /**
     * Creates a [NegotiationSession] with sensible defaults for testing.
     *
     * @param status Session status.
     * @param inviteToken Invite token, null by default.
     * @param expiresAt Expiration time, null by default.
     * @param responderTeamId Responder team, null by default.
     * @param responderInstance Responder instance URL, null by default.
     * @param maxRounds Maximum rounds, default 3.
     * @param currentRound Current round, default 0.
     * @return A test [NegotiationSession] entity.
     */
    private fun makeSession(
        status: String = "pending_response",
        inviteToken: String? = null,
        expiresAt: Instant? = null,
        responderTeamId: UUID? = null,
        responderInstance: String? = null,
        initiatorInstance: String = instanceAUrl,
        maxRounds: Int = 3,
        currentRound: Int = 0,
        initiatorConfirmed: Boolean = false,
        responderConfirmed: Boolean = false,
        initiatorManager: UUID? = managerId,
    ): NegotiationSession = NegotiationSession(
        id = sessionId,
        initiatorTeamId = teamAId,
        initiatorInstance = initiatorInstance,
        initiatorManager = initiatorManager,
        responderTeamId = responderTeamId,
        responderInstance = responderInstance,
        status = status,
        requestedDateRangeStart = java.time.LocalDate.of(2026, 4, 1),
        requestedDateRangeEnd = java.time.LocalDate.of(2026, 4, 15),
        requestedDurationMinutes = 90,
        inviteToken = inviteToken,
        maxRounds = maxRounds,
        currentRound = currentRound,
        initiatorConfirmed = initiatorConfirmed,
        responderConfirmed = responderConfirmed,
        expiresAt = expiresAt,
    )
}
