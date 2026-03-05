# FieldIQ -- Phase 1 Backend (Kotlin Spring Boot)

---

## `build.gradle.kts` dependencies

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Database
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")

    // Redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // AWS SDK (LocalStack-compatible)
    implementation("software.amazon.awssdk:sqs:2.25.0")
    implementation("software.amazon.awssdk:secretsmanager:2.25.0")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.3")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.3")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.3")

    // HTTP client (for cross-instance negotiation calls)
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Serialization
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:postgresql:1.19.0")
    testImplementation("org.testcontainers:junit-jupiter:1.19.0")
    testImplementation("io.mockk:mockk:1.13.10")
}
```

---

## Multi-Tenancy Enforcement

Every query is scoped by `team_id` + authenticated user membership. This is enforced at the service layer, not by convention.

```kotlin
// Every service method that accesses team data calls this first
@Service
class TeamAccessGuard(
    private val teamMemberRepository: TeamMemberRepository
) {
    fun requireActiveMember(userId: UUID, teamId: UUID): TeamMember {
        return teamMemberRepository.findByUserIdAndTeamIdAndIsActiveTrue(userId, teamId)
            ?: throw AccessDeniedException("User $userId is not an active member of team $teamId")
    }

    fun requireManager(userId: UUID, teamId: UUID): TeamMember {
        val member = requireActiveMember(userId, teamId)
        if (member.role != "manager") {
            throw AccessDeniedException("User $userId is not a manager of team $teamId")
        }
        return member
    }
}
```

**Rule:** No controller method may access team resources without first calling `TeamAccessGuard`. This is the Phase 1 multi-tenancy enforcement layer. Postgres Row Level Security (RLS) is a Phase 2 hardening option once the schema stabilizes.

---

## REST API Surface -- Phase 1 Endpoints

```
AUTH
  POST /auth/request-otp          { phone or email } -> sends OTP (rate limited)
  POST /auth/verify-otp           { channel, value, otp } -> JWT + refresh token
  POST /auth/refresh              { refreshToken } -> new JWT + rotated refresh token
  POST /auth/logout               { refreshToken } -> revokes refresh token

TEAMS
  GET  /teams/:teamId             -> team + members (requires active membership)
  POST /teams                     -> create team (creator becomes manager)
  POST /teams/:teamId/members     -> add member (manager only)

AVAILABILITY
  GET  /teams/:teamId/availability -> aggregate team availability
  POST /users/me/availability      -> declare availability window
  POST /users/me/calendar/connect  -> initiate Google Calendar OAuth
  GET  /auth/google/callback       -> OAuth callback, stores tokens

EVENTS
  GET  /teams/:teamId/events      -> upcoming events
  POST /teams/:teamId/events      -> create event (game/practice)
  PATCH /events/:eventId          -> update event
  POST /events/:eventId/respond   -> RSVP (going/not_going/maybe)

SCHEDULING (deterministic -- no LLM, runs in backend)
  POST /teams/:teamId/suggest-windows
    Body: { dateRangeStart, dateRangeEnd, durationMinutes, preferredDays? }
    Returns: ranked list of available time windows for this team

NEGOTIATION (core IP)
  POST /negotiations              -> initiate cross-team negotiation session
  GET  /negotiations/:sessionId   -> get session state + proposals
  POST /negotiations/:sessionId/join          -> responder joins session
  POST /negotiations/:sessionId/propose       -> send slot proposals
  POST /negotiations/:sessionId/respond       -> accept/reject/counter proposals
  POST /negotiations/:sessionId/confirm       -> human confirms agreed slot
  POST /negotiations/:sessionId/cancel        -> withdraw

  CROSS-INSTANCE (called by remote FieldIQ instances -- not by mobile app)
  POST /api/negotiate/incoming               -> receive negotiation invite from remote
  POST /api/negotiate/:sessionId/relay       -> relay proposal/response from remote
  (Both require HMAC signature validation -- see cross-instance auth)

DEVICES
  POST /users/me/devices          -> register Expo push token
  DELETE /users/me/devices/:token -> unregister push token

WEBSOCKET
  WS /ws/negotiations/:sessionId  -> real-time negotiation status updates
  (See WebSocket protocol below)
```

---

## WebSocket Protocol

The mobile app subscribes to negotiation status updates via WebSocket. This provides real-time UX during active negotiations without polling.

```
Connection: WS /ws/negotiations/:sessionId
Auth: JWT passed as query param ?token=... (validated on connect)

Server -> Client messages:
{
  "type": "negotiation_update",
  "sessionId": "uuid",
  "status": "proposing",          // current state machine status
  "currentRound": 2,
  "lastEvent": "proposal_received",
  "timestamp": "ISO8601"
}

{
  "type": "match_found",
  "sessionId": "uuid",
  "proposedSlot": {
    "startsAt": "ISO8601",
    "endsAt": "ISO8601",
    "location": "string|null"
  },
  "awaitingConfirmation": true
}

{
  "type": "session_confirmed",
  "sessionId": "uuid",
  "eventId": "uuid",             // the created FieldIQ event
  "agreedStartsAt": "ISO8601",
  "agreedLocation": "string"
}

{
  "type": "session_failed",
  "sessionId": "uuid",
  "reason": "max_rounds_exceeded" | "expired" | "cancelled"
}
```

**Scope for Phase 1:** Server-to-client only (no client-to-server messages over WS). All mutations go through REST endpoints. The WebSocket is purely for push updates.

---

## `NegotiationService.kt` -- Key Methods

```kotlin
@Service
class NegotiationService(
    private val sessionRepo: NegotiationSessionRepository,
    private val proposalRepo: NegotiationProposalRepository,
    private val eventRepo: NegotiationEventRepository,
    private val schedulingService: SchedulingService,
    private val relayClient: CrossInstanceRelayClient,
    private val teamAccessGuard: TeamAccessGuard,
    private val sqsClient: SqsClient,
) {
    // Initiates a new negotiation session
    suspend fun initiateNegotiation(
        initiatorTeamId: UUID,
        managerId: UUID,
        request: InitiateNegotiationRequest
    ): NegotiationSession

    // Called when responder joins (may be on remote instance)
    // Consumes invite_token (single-use), derives session key
    suspend fun joinSession(
        sessionId: UUID,
        inviteToken: String,
        responderTeamId: UUID,
        responderInstance: String
    ): NegotiationSession

    // Computes available windows and sends to counterpart
    // Called by SchedulingService, relayed via CrossInstanceRelayClient
    suspend fun generateAndSendProposal(
        sessionId: UUID,
        actor: NegotiationActor    // INITIATOR or RESPONDER
    ): NegotiationProposal

    // Processes incoming relay from counterpart instance
    // Returns: matched slots (if any) or counter-proposal
    // Idempotent: rejects duplicate (session, round, actor)
    suspend fun processIncomingRelay(
        sessionId: UUID,
        relay: RelayRequest
    ): RelayResponse

    // Seals confirmed agreement, creates FieldIQ events on both teams
    // Enqueues SEND_NOTIFICATION task for team-wide confirmation
    suspend fun confirmAgreement(
        sessionId: UUID,
        confirmedSlot: TimeSlot,
        confirmedBy: UUID
    ): Event
}
```

---

## `SchedulingService.kt` -- Deterministic Window Computation

```kotlin
@Service
class SchedulingService(
    private val availabilityRepo: AvailabilityWindowRepository,
    private val calendarIntegrationRepo: CalendarIntegrationRepository,
    private val eventRepo: EventRepository,
) {
    data class TimeWindow(
        val startsAt: Instant,
        val endsAt: Instant,
        val confidence: Double,   // 0.0-1.0, % of members available
    )

    // Given a team + constraints, return ranked available windows
    suspend fun findAvailableWindows(
        teamId: UUID,
        dateRangeStart: LocalDate,
        dateRangeEnd: LocalDate,
        durationMinutes: Int,
        preferredDays: List<Int>? = null,
    ): List<TimeWindow> {
        // 1. Fetch availability_windows from DB for all active team members
        // 2. Merge with Google Calendar busy blocks (from last SYNC_CALENDAR)
        // 3. Build per-member timeline of busy/free blocks
        // 4. Find contiguous free blocks >= durationMinutes
        // 5. Score each window by member availability %
        // 6. Boost score for preferred days
        // 7. Return top 10 windows sorted by score DESC
    }

    // Finds the intersection of two sets of available windows
    // Used during cross-team negotiation
    fun intersectWindows(
        teamAWindows: List<TimeWindow>,
        teamBWindows: List<TimeWindow>,
        minimumConfidence: Double = 0.5,
    ): List<TimeWindow> {
        // Overlap detection: find time ranges where both teams
        // have availability above the confidence threshold
        // Returns intersection windows sorted by combined confidence
    }
}
```
