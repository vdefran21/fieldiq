package com.fieldiq.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fieldiq.api.dto.ConfirmNegotiationRequest
import com.fieldiq.api.dto.InitiateNegotiationRequest
import com.fieldiq.api.dto.JoinSessionRequest
import com.fieldiq.api.dto.RelayRequest
import com.fieldiq.api.dto.RelayResponse
import com.fieldiq.api.dto.RelaySlot
import com.fieldiq.api.dto.TimeSlotRequest
import com.fieldiq.config.FieldIQProperties
import com.fieldiq.domain.Event
import com.fieldiq.domain.NegotiationProposal
import com.fieldiq.domain.NegotiationSession
import com.fieldiq.repository.EventRepository
import com.fieldiq.repository.NegotiationEventRepository
import com.fieldiq.repository.NegotiationProposalRepository
import com.fieldiq.repository.NegotiationSessionRepository
import com.fieldiq.security.HmacService
import com.fieldiq.security.JwtService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.sql.Date
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Integration test for the cross-team negotiation protocol — FieldIQ's core IP.
 *
 * Simulates two FieldIQ instances (A and B) running in the same JVM, each with its own
 * [NegotiationService], repositories, and configuration. The [CrossInstanceRelayClient]
 * is mocked to bridge between the two services by calling [NegotiationService.processIncomingRelay]
 * directly on the target instance, eliminating HTTP overhead and network dependencies.
 *
 * **What this tests:**
 * - Full protocol flow: initiate → join → propose → match/fail → confirm/cancel
 * - State machine transitions across both instances
 * - Proposal exchange with scheduling intersection logic
 * - Idempotent duplicate handling
 * - Graceful failure on max rounds exceeded
 *
 * **What this does NOT test:**
 * - HTTP transport (tested by Bruno API tests)
 * - HMAC signature validation (tested by [com.fieldiq.security.HmacAuthenticationFilterTest])
 * - Real database persistence (repositories are MockK-backed with in-memory stores)
 * - Redis session key caching internals (tested by unit tests)
 *
 * **Repository simulation:** Each instance uses MockK-backed repositories with
 * [ConcurrentHashMap] stores that simulate two separate databases.
 *
 * @see NegotiationService for the service under test.
 * @see CrossInstanceRelayClient for the relay client that is bridged here.
 */
class NegotiationProtocolIntegrationTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    // Instance A (initiator)
    private lateinit var serviceA: NegotiationService
    private val sessionStoreA = ConcurrentHashMap<UUID, NegotiationSession>()
    private val proposalStoreA = ConcurrentHashMap<UUID, NegotiationProposal>()
    private val eventStoreA = ConcurrentHashMap<UUID, Event>()

    // Instance B (responder)
    private lateinit var serviceB: NegotiationService
    private val sessionStoreB = ConcurrentHashMap<UUID, NegotiationSession>()
    private val proposalStoreB = ConcurrentHashMap<UUID, NegotiationProposal>()
    private val eventStoreB = ConcurrentHashMap<UUID, Event>()

    // Shared services (mocked)
    private val schedulingService: SchedulingService = mockk()
    private val hmacService: HmacService = mockk()

    // Test data
    private val managerA = UUID.randomUUID()
    private val managerB = UUID.randomUUID()
    private val teamAId = UUID.randomUUID()
    private val teamBId = UUID.randomUUID()
    private val instanceAUrl = "http://localhost:8080"
    private val instanceBUrl = "http://localhost:8081"
    private val sessionKey = ByteArray(32) { it.toByte() }
    private val sessionKeyBase64 = Base64.getEncoder().encodeToString(sessionKey)

    /**
     * Wires two NegotiationService instances with in-memory-backed MockK repositories
     * and a bridging relay client. The relay client on each side calls processIncomingRelay
     * on the other side directly.
     */
    @BeforeEach
    fun setup() {
        sessionStoreA.clear()
        proposalStoreA.clear()
        eventStoreA.clear()
        sessionStoreB.clear()
        proposalStoreB.clear()
        eventStoreB.clear()

        val sessionRepoA = mockSessionRepo(sessionStoreA)
        val proposalRepoA = mockProposalRepo(proposalStoreA)
        val eventRepoA = mockEventRepo(eventStoreA)
        val auditRepoA: NegotiationEventRepository = mockk()
        every { auditRepoA.save(any()) } answers { firstArg() }

        val sessionRepoB = mockSessionRepo(sessionStoreB)
        val proposalRepoB = mockProposalRepo(proposalStoreB)
        val eventRepoB = mockEventRepo(eventStoreB)
        val auditRepoB: NegotiationEventRepository = mockk()
        every { auditRepoB.save(any()) } answers { firstArg() }

        val teamAccessGuardA: TeamAccessGuard = mockk()
        val teamAccessGuardB: TeamAccessGuard = mockk()

        // Manager A manages teamA, Manager B manages teamB
        every { teamAccessGuardA.requireManager(managerA, teamAId) } returns mockk()
        every { teamAccessGuardA.requireManager(managerB, teamBId) } returns mockk()
        every { teamAccessGuardA.requireManager(managerB, teamAId) } throws
            org.springframework.security.access.AccessDeniedException("Not a manager")
        every { teamAccessGuardA.requireManager(managerA, teamBId) } throws
            org.springframework.security.access.AccessDeniedException("Not a manager")

        every { teamAccessGuardB.requireManager(managerB, teamBId) } returns mockk()
        every { teamAccessGuardB.requireManager(managerA, teamAId) } returns mockk()
        every { teamAccessGuardB.requireManager(managerA, teamBId) } throws
            org.springframework.security.access.AccessDeniedException("Not a manager")
        every { teamAccessGuardB.requireManager(managerB, teamAId) } throws
            org.springframework.security.access.AccessDeniedException("Not a manager")

        every { hmacService.deriveSessionKey(any()) } returns sessionKey

        // Shared Redis store — both instances share session key cache (simulates real deployment
        // where both instances can access the same Redis cluster or key is replicated)
        val sharedRedisStore = ConcurrentHashMap<String, String>()
        val redisA = mockRedis(sharedRedisStore)
        val redisB = mockRedis(sharedRedisStore)

        val propsA = FieldIQProperties(
            instance = FieldIQProperties.InstanceProperties("instance-a", "secret-a", instanceAUrl),
            jwt = FieldIQProperties.JwtProperties("jwt-secret-a"),
            aws = FieldIQProperties.AwsProperties(),
        )
        val propsB = FieldIQProperties(
            instance = FieldIQProperties.InstanceProperties("instance-b", "secret-b", instanceBUrl),
            jwt = FieldIQProperties.JwtProperties("jwt-secret-b"),
            aws = FieldIQProperties.AwsProperties(),
        )

        val relayClientA: CrossInstanceRelayClient = mockk(relaxed = true)
        val relayClientB: CrossInstanceRelayClient = mockk(relaxed = true)
        val jdbcA: NamedParameterJdbcTemplate = mockk(relaxed = true)
        val jdbcB: NamedParameterJdbcTemplate = mockk(relaxed = true)
        val jwtServiceA = JwtService(propsA)
        val jwtServiceB = JwtService(propsB)

        serviceA = NegotiationService(
            sessionRepoA, proposalRepoA, auditRepoA, eventRepoA,
            schedulingService, relayClientA, teamAccessGuardA, hmacService, jwtServiceA,
            propsA, redisA, objectMapper, jdbcA,
        )
        serviceB = NegotiationService(
            sessionRepoB, proposalRepoB, auditRepoB, eventRepoB,
            schedulingService, relayClientB, teamAccessGuardB, hmacService, jwtServiceB,
            propsB, redisB, objectMapper, jdbcB,
        )

        every { jdbcB.update(any<String>(), any<MapSqlParameterSource>()) } answers {
            val params = secondArg<MapSqlParameterSource>()
            val session = NegotiationSession(
                id = params.getValue("id") as UUID,
                initiatorTeamId = params.getValue("initiatorTeamId") as UUID,
                initiatorInstance = params.getValue("initiatorInstance") as String,
                initiatorManager = params.getValue("initiatorManager") as UUID?,
                responderTeamId = params.getValue("responderTeamId") as UUID?,
                responderInstance = params.getValue("responderInstance") as String?,
                responderExternalId = params.getValue("responderExternalId") as String?,
                status = params.getValue("status") as String,
                requestedDateRangeStart = (params.getValue("requestedDateRangeStart") as Date?)?.toLocalDate(),
                requestedDateRangeEnd = (params.getValue("requestedDateRangeEnd") as Date?)?.toLocalDate(),
                requestedDurationMinutes = params.getValue("requestedDurationMinutes") as Int,
                agreedStartsAt = (params.getValue("agreedStartsAt") as Timestamp?)?.toInstant(),
                agreedEndsAt = (params.getValue("agreedEndsAt") as Timestamp?)?.toInstant(),
                agreedLocation = params.getValue("agreedLocation") as String?,
                inviteToken = params.getValue("inviteToken") as String?,
                sessionKeyHash = params.getValue("sessionKeyHash") as String?,
                maxRounds = params.getValue("maxRounds") as Int,
                currentRound = params.getValue("currentRound") as Int,
                initiatorConfirmed = params.getValue("initiatorConfirmed") as Boolean,
                responderConfirmed = params.getValue("responderConfirmed") as Boolean,
                expiresAt = (params.getValue("expiresAt") as Timestamp?)?.toInstant(),
                createdAt = (params.getValue("createdAt") as Timestamp).toInstant(),
                updatedAt = (params.getValue("updatedAt") as Timestamp).toInstant(),
            )
            sessionStoreB[session.id] = session
            1
        }
        every { jdbcA.update(any<String>(), any<MapSqlParameterSource>()) } answers {
            val params = secondArg<MapSqlParameterSource>()
            val session = NegotiationSession(
                id = params.getValue("id") as UUID,
                initiatorTeamId = params.getValue("initiatorTeamId") as UUID,
                initiatorInstance = params.getValue("initiatorInstance") as String,
                initiatorManager = params.getValue("initiatorManager") as UUID?,
                responderTeamId = params.getValue("responderTeamId") as UUID?,
                responderInstance = params.getValue("responderInstance") as String?,
                responderExternalId = params.getValue("responderExternalId") as String?,
                status = params.getValue("status") as String,
                requestedDateRangeStart = (params.getValue("requestedDateRangeStart") as Date?)?.toLocalDate(),
                requestedDateRangeEnd = (params.getValue("requestedDateRangeEnd") as Date?)?.toLocalDate(),
                requestedDurationMinutes = params.getValue("requestedDurationMinutes") as Int,
                agreedStartsAt = (params.getValue("agreedStartsAt") as Timestamp?)?.toInstant(),
                agreedEndsAt = (params.getValue("agreedEndsAt") as Timestamp?)?.toInstant(),
                agreedLocation = params.getValue("agreedLocation") as String?,
                inviteToken = params.getValue("inviteToken") as String?,
                sessionKeyHash = params.getValue("sessionKeyHash") as String?,
                maxRounds = params.getValue("maxRounds") as Int,
                currentRound = params.getValue("currentRound") as Int,
                initiatorConfirmed = params.getValue("initiatorConfirmed") as Boolean,
                responderConfirmed = params.getValue("responderConfirmed") as Boolean,
                expiresAt = (params.getValue("expiresAt") as Timestamp?)?.toInstant(),
                createdAt = (params.getValue("createdAt") as Timestamp).toInstant(),
                updatedAt = (params.getValue("updatedAt") as Timestamp).toInstant(),
            )
            sessionStoreA[session.id] = session
            1
        }

        // Bridge incoming: A→B bootstrap shadow session on join
        every { relayClientA.sendIncoming(instanceBUrl, any()) } answers {
            serviceB.createShadowSession(secondArg())
            RelayResponse(sessionStatus = "proposing", currentRound = 0)
        }

        // Bridge relay: A→B calls serviceB.processIncomingRelay, B→A calls serviceA
        every { relayClientA.sendRelay(instanceBUrl, any(), any(), any()) } answers {
            val sid = UUID.fromString(secondArg<String>())
            serviceB.processIncomingRelay(sid, arg(3))
        }
        every { relayClientB.sendRelay(instanceAUrl, any(), any(), any()) } answers {
            val sid = UUID.fromString(secondArg<String>())
            serviceA.processIncomingRelay(sid, arg(3))
        }
    }

    // ========================================================================
    // Protocol Flow Tests
    // ========================================================================

    /**
     * Full happy path: initiate → join → propose → match found → both confirm → events created.
     */
    @Test
    @DisplayName("happy path: initiate → join → propose → match → confirm → events on both")
    fun happyPath() {
        // 1. Instance A: Initiate
        val init = serviceA.initiateNegotiation(
            managerA,
            InitiateNegotiationRequest(teamAId, "2026-04-01", "2026-04-15", 90),
        )
        assertEquals("pending_response", init.status)
        val sessionId = UUID.fromString(init.id)
        val inviteToken = init.inviteToken!!

        // 2. Join on Instance A — sendIncoming bridge creates shadow session on B
        val joined = serviceA.joinSession(
            sessionId,
            managerB,
            JoinSessionRequest(inviteToken, teamBId, instanceBUrl),
        )
        assertEquals("proposing", joined.status)

        // 3. Overlapping availability → match
        val slot = SchedulingService.TimeWindow(
            Instant.parse("2026-04-04T14:00:00Z"),
            Instant.parse("2026-04-04T15:30:00Z"),
            0.9,
        )
        every { schedulingService.findAvailableWindows(teamId = teamAId, any(), any(), any()) } returns listOf(slot)
        every { schedulingService.findAvailableWindows(teamId = teamBId, any(), any(), any()) } returns listOf(slot)
        every { schedulingService.intersectWindows(any(), any()) } returns listOf(slot)

        // 4. Instance A proposes → relay to B → B finds match → pending_approval
        //    Relay response propagates pending_approval back to A automatically
        serviceA.generateAndSendProposal(sessionId, managerA)

        // B should have the session in pending_approval
        val bSession = sessionStoreB[sessionId]!!
        assertEquals("pending_approval", bSession.status)
        assertEquals(Instant.parse("2026-04-04T15:30:00Z"), bSession.agreedEndsAt)

        // A should also be in pending_approval via relay response processing
        val aSession = sessionStoreA[sessionId]!!
        assertEquals("pending_approval", aSession.status)

        // 5. Dual confirmation: A confirms first → relay to B sets initiator flag
        val confirmSlot = TimeSlotRequest(
            Instant.parse("2026-04-04T14:00:00Z"),
            Instant.parse("2026-04-04T15:30:00Z"),
            "Bethesda Field #3",
        )
        val confirmA = serviceA.confirmAgreement(sessionId, managerA, ConfirmNegotiationRequest(confirmSlot))
        // A confirmed first — stays in pending_approval, no event yet
        assertEquals("pending_approval", confirmA.status)
        assertTrue(confirmA.initiatorConfirmed)
        assertFalse(confirmA.responderConfirmed)
        assertTrue(eventStoreA.isEmpty(), "No event yet — only initiator confirmed")

        // B's session should have initiatorConfirmed set via relay bridge
        val bAfterAConfirm = sessionStoreB[sessionId]!!
        assertTrue(bAfterAConfirm.initiatorConfirmed, "B should see initiator confirmed via relay")

        // B confirms → both flags true → B transitions to confirmed + creates event
        val confirmB = serviceB.confirmAgreement(sessionId, managerB, ConfirmNegotiationRequest(confirmSlot))
        assertEquals("confirmed", confirmB.status)
        assertTrue(confirmB.initiatorConfirmed)
        assertTrue(confirmB.responderConfirmed)
        assertTrue(eventStoreB.isNotEmpty(), "Instance B should have a game event")

        // B's relay to A should set responderConfirmed → A transitions to confirmed + creates event
        val aAfterBConfirm = sessionStoreA[sessionId]!!
        assertEquals("confirmed", aAfterBConfirm.status)
        assertTrue(eventStoreA.isNotEmpty(), "Instance A should have a game event via relay")
    }

    /**
     * Max rounds exceeded: 3 rounds of non-overlapping proposals → session fails.
     */
    @Test
    @DisplayName("max rounds exceeded: 3 rounds non-overlapping → session fails")
    fun maxRoundsExceeded() {
        // 1. Create and join
        val init = serviceA.initiateNegotiation(
            managerA,
            InitiateNegotiationRequest(teamAId, "2026-04-01", "2026-04-15", 90),
        )
        val sessionId = UUID.fromString(init.id)

        serviceA.joinSession(sessionId, managerB, JoinSessionRequest(init.inviteToken!!, teamBId, instanceBUrl))

        // 2. Non-overlapping availability → no match ever
        every { schedulingService.findAvailableWindows(teamId = teamAId, any(), any(), any()) } returns listOf(
            SchedulingService.TimeWindow(
                Instant.parse("2026-04-04T09:00:00Z"),
                Instant.parse("2026-04-04T10:30:00Z"),
                0.9,
            ),
        )
        every { schedulingService.findAvailableWindows(teamId = teamBId, any(), any(), any()) } returns listOf(
            SchedulingService.TimeWindow(
                Instant.parse("2026-04-04T14:00:00Z"),
                Instant.parse("2026-04-04T15:30:00Z"),
                0.8,
            ),
        )
        every { schedulingService.intersectWindows(any(), any()) } returns emptyList()

        // 3. A proposes → relay chain of counter-proposals exhausts maxRounds
        //    Relay response propagates terminal status back to A
        serviceA.generateAndSendProposal(sessionId, managerA)

        // Both instances should have failed via relay response propagation
        val statusA = sessionStoreA[sessionId]?.status
        val statusB = sessionStoreB[sessionId]?.status
        assertEquals("failed", statusB, "Instance B should be 'failed' after max rounds")
        assertEquals("failed", statusA, "Instance A should be 'failed' via relay response")
    }

    /**
     * Cancellation: initiate → join → cancel → both instances show cancelled.
     */
    @Test
    @DisplayName("cancellation: initiate → join → cancel → both cancelled")
    fun cancellation() {
        val init = serviceA.initiateNegotiation(
            managerA,
            InitiateNegotiationRequest(teamAId, "2026-04-01", "2026-04-15", 90),
        )
        val sessionId = UUID.fromString(init.id)

        serviceA.joinSession(sessionId, managerB, JoinSessionRequest(init.inviteToken!!, teamBId, instanceBUrl))

        // Cancel from Instance A → relay to Instance B
        val result = serviceA.cancelSession(sessionId, managerA)
        assertEquals("cancelled", result.status)

        // Instance B should also be cancelled via relay bridge
        assertEquals("cancelled", sessionStoreB[sessionId]?.status)
    }

    /**
     * Idempotent duplicate: sending the same proposal twice should not create a duplicate record.
     *
     * Uses overlapping availability so the first relay triggers a match (pending_approval)
     * instead of a counter-proposal chain. The duplicate relay is then safely ignored because
     * the proposal for (session, round=1, actor=initiator) already exists.
     */
    @Test
    @DisplayName("idempotent: duplicate relay proposal does not create duplicate record")
    fun idempotentDuplicate() {
        val init = serviceA.initiateNegotiation(
            managerA,
            InitiateNegotiationRequest(teamAId, "2026-04-01", "2026-04-15", 90),
        )
        val sessionId = UUID.fromString(init.id)

        serviceA.joinSession(sessionId, managerB, JoinSessionRequest(init.inviteToken!!, teamBId, instanceBUrl))

        // Overlapping availability → match found (no counter-proposal chain)
        val matchWindow = SchedulingService.TimeWindow(
            Instant.parse("2026-04-04T14:00:00Z"),
            Instant.parse("2026-04-04T15:30:00Z"),
            0.9,
        )
        every { schedulingService.findAvailableWindows(any(), any(), any(), any()) } returns listOf(matchWindow)
        every { schedulingService.intersectWindows(any(), any()) } returns listOf(matchWindow)

        val relay = RelayRequest(
            action = "propose",
            roundNumber = 1,
            proposalId = UUID.randomUUID().toString(),
            actor = "initiator",
            slots = listOf(
                RelaySlot(Instant.parse("2026-04-04T14:00:00Z"), Instant.parse("2026-04-04T15:30:00Z")),
            ),
        )

        // First relay → match found, transitions to pending_approval
        val firstResult = serviceB.processIncomingRelay(sessionId, relay)
        assertEquals("pending_approval", firstResult.sessionStatus)

        val countAfterFirst = proposalStoreB.values.count {
            it.sessionId == sessionId && it.roundNumber == 1 && it.proposedBy == "initiator"
        }
        assertEquals(1, countAfterFirst)

        // Reset session to proposing to allow second relay attempt (simulating state before match)
        sessionStoreB[sessionId] = sessionStoreB[sessionId]!!.copy(status = "proposing")

        // Second relay (duplicate) → idempotently ignored
        val secondResult = serviceB.processIncomingRelay(sessionId, relay)
        assertEquals("proposing", secondResult.sessionStatus)

        val countAfterSecond = proposalStoreB.values.count {
            it.sessionId == sessionId && it.roundNumber == 1 && it.proposedBy == "initiator"
        }

        assertEquals(countAfterFirst, countAfterSecond, "Duplicate proposal should not create a new record")
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Creates a MockK [NegotiationSessionRepository] backed by a [ConcurrentHashMap].
     */
    private fun mockSessionRepo(store: ConcurrentHashMap<UUID, NegotiationSession>): NegotiationSessionRepository {
        val repo: NegotiationSessionRepository = mockk()
        every { repo.save(any()) } answers {
            val session = firstArg<NegotiationSession>()
            store[session.id] = session
            session
        }
        every { repo.saveAndFlush(any()) } answers {
            val session = firstArg<NegotiationSession>()
            store[session.id] = session
            session
        }
        every { repo.findById(any()) } answers {
            Optional.ofNullable(store[firstArg()])
        }
        every { repo.findByInviteToken(any()) } answers {
            store.values.find { it.inviteToken == firstArg() }
        }
        return repo
    }

    /**
     * Creates a MockK [NegotiationProposalRepository] backed by a [ConcurrentHashMap].
     */
    private fun mockProposalRepo(store: ConcurrentHashMap<UUID, NegotiationProposal>): NegotiationProposalRepository {
        val repo: NegotiationProposalRepository = mockk()
        every { repo.save(any()) } answers {
            val proposal = firstArg<NegotiationProposal>()
            store[proposal.id] = proposal
            proposal
        }
        every { repo.findBySessionId(any()) } answers {
            store.values.filter { it.sessionId == firstArg() }
        }
        every { repo.findBySessionIdAndRoundNumber(any(), any()) } answers {
            val sid: UUID = firstArg()
            val round: Int = secondArg()
            store.values.filter { it.sessionId == sid && it.roundNumber == round }
        }
        return repo
    }

    /**
     * Creates a MockK [EventRepository] backed by a [ConcurrentHashMap].
     */
    private fun mockEventRepo(store: ConcurrentHashMap<UUID, Event>): EventRepository {
        val repo: EventRepository = mockk()
        every { repo.save(any()) } answers {
            val event = firstArg<Event>()
            store[event.id] = event
            event
        }
        every { repo.findByTeamIdAndNegotiationId(any(), any()) } answers {
            val tid: UUID = firstArg()
            val nid: UUID = secondArg()
            store.values.find { it.teamId == tid && it.negotiationId == nid }
        }
        return repo
    }

    /**
     * Creates a MockK [StringRedisTemplate] backed by a [ConcurrentHashMap].
     *
     * @param store Shared or isolated store for session key caching.
     */
    private fun mockRedis(store: ConcurrentHashMap<String, String> = ConcurrentHashMap()): StringRedisTemplate {
        val redis: StringRedisTemplate = mockk()
        val ops: ValueOperations<String, String> = mockk()
        every { redis.opsForValue() } returns ops
        every { ops.set(any(), any(), any<Duration>()) } answers {
            store[firstArg()] = secondArg()
        }
        every { ops.get(any()) } answers { store[firstArg()] }
        return redis
    }
}
