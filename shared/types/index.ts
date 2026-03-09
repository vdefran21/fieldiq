/**
 * FieldIQ Shared Types — API Contract
 *
 * These TypeScript interfaces define the shape of all REST API request and response
 * bodies exchanged between the backend (Kotlin Spring Boot) and frontend (React Native).
 *
 * IMPORTANT: There is no automated code generation between Kotlin and TypeScript.
 * Kotlin data classes (DTOs) are maintained manually to match these interfaces.
 * When changing any interface here, update the corresponding Kotlin DTO in the
 * same commit. See CLAUDE.md "Important Conventions" for the full rule.
 *
 * Naming conventions:
 * - `*Dto`: Response objects returned by the API (read models)
 * - `*Request`: Request bodies sent to the API (write models)
 * - `*Message`: WebSocket push messages from server to client
 */

// ============================================================================
// Auth
// ============================================================================

/**
 * Request body for POST /auth/request-otp.
 * Initiates passwordless authentication by sending a one-time password via SMS or email.
 * Rate limited: max 3 requests per 15-minute window per identifier.
 * Dev bypass: phone numbers matching +1555* are exempt from rate limiting.
 *
 * Corresponds to Kotlin DTO: RequestOtpRequest (to be created in Sprint 2)
 */
export interface RequestOtpRequest {
  /** Delivery channel for the OTP code. */
  channel: 'sms' | 'email';
  /** Phone number (E.164 format, e.g. "+12025551234") or email address. */
  value: string;
}

/**
 * Request body for POST /auth/verify-otp.
 * Validates the OTP code and returns JWT + refresh token on success.
 * If the user doesn't exist, a new User record is created (sign-up flow).
 * After 5 failed verification attempts, the identifier is blocked for 1 hour.
 *
 * Corresponds to Kotlin DTO: VerifyOtpRequest (to be created in Sprint 2)
 */
export interface VerifyOtpRequest {
  /** Must match the channel used in the original OTP request. */
  channel: 'sms' | 'email';
  /** The phone number or email the OTP was sent to. */
  value: string;
  /** The 6-digit OTP code entered by the user. */
  otp: string;
}

/**
 * Response body returned by POST /auth/verify-otp and POST /auth/refresh.
 * Contains a short-lived JWT access token and a long-lived refresh token.
 *
 * Corresponds to Kotlin DTO: AuthResponse (to be created in Sprint 2)
 */
export interface AuthResponse {
  /** JWT access token for the Authorization header. Expires in 15 minutes. */
  accessToken: string;
  /** One-time-use refresh token. Rotated on each use (old token is invalidated). */
  refreshToken: string;
  /** Access token lifetime in seconds (900 = 15 minutes). */
  expiresIn: number;
  /** The authenticated user's profile. */
  user: UserDto;
}

/**
 * Request body for POST /auth/refresh.
 * Exchanges a valid refresh token for a new access token + rotated refresh token.
 * The old refresh token is immediately revoked (one-time use).
 *
 * Corresponds to Kotlin DTO: RefreshTokenRequest (to be created in Sprint 2)
 */
export interface RefreshTokenRequest {
  /** The refresh token received from a previous auth response. */
  refreshToken: string;
}

/**
 * Response body for GET /auth/google/authorize-url.
 * Lets the mobile app open the browser-based Google OAuth flow without needing
 * to attach its bearer token directly to the browser redirect request.
 *
 * Corresponds to Kotlin DTO: GoogleAuthorizeUrlResponse.
 */
export interface GoogleAuthorizeUrlResponse {
  /** Fully qualified Google consent screen URL for the authenticated user. */
  authorizeUrl: string;
}

/**
 * Response body for GET /auth/google/status.
 * Summarizes the current user's Google Calendar connection state.
 *
 * Corresponds to Kotlin DTO: CalendarIntegrationStatusResponse.
 */
export interface CalendarIntegrationStatusResponse {
  /** Whether the user currently has a linked Google Calendar account. */
  connected: boolean;
  /** Provider identifier when connected. */
  provider?: string;
  /** Timestamp of the last successful availability sync. */
  lastSyncedAt?: string;
  /** Access-token expiry time for the linked account. */
  expiresAt?: string;
}

// ============================================================================
// Users
// ============================================================================

/**
 * User profile data returned in API responses.
 * Contains only adult contact info (COPPA compliance — no child data here).
 *
 * Corresponds to Kotlin entity: com.fieldiq.domain.User
 */
export interface UserDto {
  /** UUID of the user. */
  id: string;
  /** Phone number in E.164 format. Present if user registered via SMS. */
  phone?: string;
  /** Email address. Present if user registered via email. */
  email?: string;
  /** User's preferred display name (e.g., "Sarah Johnson"). */
  displayName?: string;
}

// ============================================================================
// Teams
// ============================================================================

/**
 * Team data returned in API responses.
 *
 * Corresponds to Kotlin entity: com.fieldiq.domain.Team
 */
export interface TeamDto {
  /** UUID of the team. */
  id: string;
  /** UUID of the parent organization (club/league). */
  orgId?: string;
  /** Display name (e.g., "Bethesda Fire U12 Boys"). */
  name: string;
  /** Sport type. Defaults to "soccer" in Phase 1. */
  sport: string;
  /** Age division label (e.g., "U10", "U14"). */
  ageGroup?: string;
  /** Season identifier (e.g., "Spring2026"). */
  season?: string;
}

/**
 * Request body for POST /teams.
 * Creates a new team. The authenticated user automatically becomes the team manager.
 *
 * Corresponds to Kotlin DTO: CreateTeamRequest (to be created in Sprint 2)
 */
export interface CreateTeamRequest {
  /** Display name for the team. */
  name: string;
  /** Parent organization UUID. Optional for standalone teams. */
  orgId?: string;
  /** Sport type. Defaults to "soccer" if omitted. */
  sport?: string;
  /** Age division label (e.g., "U12"). */
  ageGroup?: string;
  /** Season identifier (e.g., "Spring2026"). */
  season?: string;
}

/**
 * Team member data returned in API responses.
 * Includes role and optional child name (for parent members).
 *
 * Corresponds to Kotlin entity: com.fieldiq.domain.TeamMember
 */
export interface TeamMemberDto {
  /** UUID of the team membership record. */
  id: string;
  /** UUID of the team. */
  teamId: string;
  /** UUID of the user. */
  userId: string;
  /** User's role on this team. Determines permissions. */
  role: 'manager' | 'coach' | 'parent';
  /**
   * Child's name for roster display (COPPA: only stored here, not in UserDto).
   * Only meaningful when role is "parent".
   */
  playerName?: string;
  /** Whether this membership is currently active. */
  isActive: boolean;
  /** Expanded user profile, included when the API joins user data. */
  user?: UserDto;
}

/**
 * Request body for POST /teams/:teamId/members.
 * Adds a user to a team with a specific role. Requires manager permissions.
 *
 * Corresponds to Kotlin DTO: AddTeamMemberRequest (to be created in Sprint 2)
 */
export interface AddTeamMemberRequest {
  /** UUID of the user to add. */
  userId: string;
  /** Role to assign on this team. */
  role: 'manager' | 'coach' | 'parent';
  /** Child's name (required when role is "parent", for roster display). */
  playerName?: string;
}

// ============================================================================
// Events
// ============================================================================

/**
 * Team event data returned in API responses.
 * Covers games, practices, tournaments, and other scheduled activities.
 *
 * Corresponds to Kotlin entity: com.fieldiq.domain.Event
 */
export interface EventDto {
  /** UUID of the event. */
  id: string;
  /** UUID of the team this event belongs to. */
  teamId: string;
  /** Type of event (determines display and behavior). */
  eventType: 'game' | 'practice' | 'tournament' | 'other';
  /** Display title (e.g., "vs Arlington United"). */
  title?: string;
  /** Venue name or address. */
  location?: string;
  /** Additional directions (e.g., "Park in Lot B"). */
  locationNotes?: string;
  /** Scheduled start time in ISO 8601 UTC format. Null for unscheduled events. */
  startsAt?: string;
  /** Scheduled end time in ISO 8601 UTC format. */
  endsAt?: string;
  /** Lifecycle state of the event. */
  status: 'draft' | 'scheduled' | 'cancelled' | 'completed';
  /** Opponent team name (for games). Display string for cross-instance opponents. */
  opponentName?: string;
  /** UUID of the negotiation session that created this event, if any. */
  negotiationId?: string;
  /** Relative download URL for an iCalendar export when the event is scheduled. */
  icsUrl?: string;
}

/**
 * Request body for POST /teams/:teamId/events.
 * Creates a new event on the team's schedule. Requires manager or coach role.
 *
 * Corresponds to Kotlin DTO: CreateEventRequest (to be created in Sprint 2)
 */
export interface CreateEventRequest {
  /** Type of event to create. */
  eventType: 'game' | 'practice' | 'tournament' | 'other';
  /** Display title. */
  title?: string;
  /** Venue name or address. */
  location?: string;
  /** Additional directions or notes. */
  locationNotes?: string;
  /** Start time in ISO 8601 UTC format. Null creates a draft event. */
  startsAt?: string;
  /** End time in ISO 8601 UTC format. */
  endsAt?: string;
}

/**
 * RSVP response data returned in API responses.
 * Tracks whether a team member is attending an event.
 *
 * Corresponds to Kotlin entity: com.fieldiq.domain.EventResponse
 */
export interface EventResponseDto {
  /** UUID of the response record. */
  id: string;
  /** UUID of the event. */
  eventId: string;
  /** UUID of the user who responded. */
  userId: string;
  /** The user's RSVP status. */
  status: 'going' | 'not_going' | 'maybe' | 'no_response';
  /** When the user last changed their response. Null if they haven't responded. */
  respondedAt?: string;
  /** Expanded user profile, included when the API joins user data. */
  user?: UserDto;
}

/**
 * Request body for POST /events/:eventId/respond.
 * Submits or updates a user's RSVP for an event.
 *
 * Corresponds to Kotlin DTO: RespondToEventRequest (to be created in Sprint 2)
 */
export interface RespondToEventRequest {
  /** The RSVP status to set. "no_response" is not valid here (that's the default). */
  status: 'going' | 'not_going' | 'maybe';
}

// ============================================================================
// Availability
// ============================================================================

/**
 * Availability window data returned in API responses.
 * Represents a time block when a team member is available or unavailable.
 *
 * Corresponds to Kotlin entity: com.fieldiq.domain.AvailabilityWindow
 */
export interface AvailabilityWindowDto {
  /** UUID of the availability window. */
  id: string;
  /** UUID of the team this window applies to. */
  teamId: string;
  /** UUID of the user who declared this window. */
  userId: string;
  /** For recurring windows: day of week (0=Sunday through 6=Saturday). Mutually exclusive with specificDate. */
  dayOfWeek?: number;
  /** For one-time windows: specific date in YYYY-MM-DD format. Mutually exclusive with dayOfWeek. */
  specificDate?: string;
  /** Start time in HH:mm format, interpreted in the org's timezone. */
  startTime: string;
  /** End time in HH:mm format. Must be after startTime. */
  endTime: string;
  /** Whether this block represents free time or a conflict. */
  windowType: 'available' | 'unavailable';
  /** How this window was created. */
  source: 'manual' | 'google_cal' | 'apple_cal';
}

/**
 * Request body for POST /users/me/availability.
 * Declares a new availability window for the authenticated user.
 * Exactly one of dayOfWeek or specificDate must be provided.
 *
 * Corresponds to Kotlin DTO: CreateAvailabilityWindowRequest (to be created in Sprint 2)
 */
export interface CreateAvailabilityWindowRequest {
  /** UUID of the team this availability is for. */
  teamId: string;
  /** For recurring windows: day of week (0=Sunday through 6=Saturday). */
  dayOfWeek?: number;
  /** For one-time windows: date in YYYY-MM-DD format. */
  specificDate?: string;
  /** Start time in HH:mm format. */
  startTime: string;
  /** End time in HH:mm format. Must be after startTime. */
  endTime: string;
  /** Whether this is a free block or a conflict. */
  windowType: 'available' | 'unavailable';
}

// ============================================================================
// Scheduling
// ============================================================================

/**
 * Request body for POST /teams/:teamId/suggest-windows.
 * Asks SchedulingService to compute optimal meeting times based on team availability.
 * This is a deterministic algorithm (no LLM) — runs in the Kotlin backend.
 *
 * Corresponds to Kotlin DTO: SuggestWindowsRequest (to be created in Sprint 3)
 */
export interface SuggestWindowsRequest {
  /** Earliest acceptable date in YYYY-MM-DD format. */
  dateRangeStart: string;
  /** Latest acceptable date in YYYY-MM-DD format. */
  dateRangeEnd: string;
  /** Required duration in minutes (e.g., 90 for a standard youth soccer game). */
  durationMinutes: number;
  /** Preferred days of week (0-6). Windows on these days get a score boost. */
  preferredDays?: number[];
}

/**
 * A computed time window returned by the scheduling algorithm.
 * Ranked by confidence (what % of team members are available during this window).
 *
 * Corresponds to Kotlin data class: SchedulingService.TimeWindow
 */
export interface TimeWindowDto {
  /** Window start time in ISO 8601 UTC format. */
  startsAt: string;
  /** Window end time in ISO 8601 UTC format. */
  endsAt: string;
  /** Fraction of team members available during this window (0.0 to 1.0). */
  confidence: number;
}

// ============================================================================
// Negotiation
// ============================================================================

/**
 * Request body for POST /negotiations.
 * Initiates a cross-team scheduling negotiation. Creates a session with an
 * invite token that can be shared with the opposing team's manager.
 * Requires manager role on the specified team.
 *
 * Corresponds to Kotlin DTO: InitiateNegotiationRequest (to be created in Sprint 4)
 */
export interface InitiateNegotiationRequest {
  /** UUID of the initiating team. */
  teamId: string;
  /** Earliest acceptable game date in YYYY-MM-DD format. */
  dateRangeStart: string;
  /** Latest acceptable game date in YYYY-MM-DD format. */
  dateRangeEnd: string;
  /** Desired game duration in minutes. Defaults to 90 if omitted. */
  durationMinutes?: number;
  /** Preferred days of week (0-6) for the game. */
  preferredDays?: number[];
}

/**
 * Negotiation session data returned in API responses.
 * Tracks the full state of a cross-team scheduling negotiation.
 *
 * Corresponds to Kotlin entity: com.fieldiq.domain.NegotiationSession
 */
export interface NegotiationSessionDto {
  /** UUID of the session (also used as X-FieldIQ-Session-Id in cross-instance calls). */
  id: string;
  /** UUID of the team that started this negotiation. */
  initiatorTeamId: string;
  /** UUID of the responding team. Null until responder joins. */
  responderTeamId?: string;
  /**
   * Current state machine status:
   * - pending_response: waiting for opponent to join via invite token
   * - proposing: exchanging time slot proposals
   * - pending_approval: match found, awaiting human confirmation
   * - confirmed: game scheduled (terminal)
   * - failed: no match found or expired (terminal)
   * - cancelled: one side withdrew (terminal)
   */
  status:
    | 'pending_response'
    | 'proposing'
    | 'pending_approval'
    | 'confirmed'
    | 'failed'
    | 'cancelled';
  /** Earliest acceptable date set by initiator. */
  requestedDateRangeStart?: string;
  /** Latest acceptable date set by initiator. */
  requestedDateRangeEnd?: string;
  /** Desired game duration in minutes. */
  requestedDurationMinutes: number;
  /** Agreed game start time. Set when match found (pending_approval) or on confirmation. */
  agreedStartsAt?: string;
  /** Agreed game end time. Set alongside agreedStartsAt. */
  agreedEndsAt?: string;
  /** Agreed game location. Set on confirmation. */
  agreedLocation?: string;
  /** Invite token for sharing with the opposing team (only visible to initiator, null after join). */
  inviteToken?: string;
  /** Maximum proposal rounds before the negotiation fails. */
  maxRounds: number;
  /** Current round number (0 = no proposals yet). */
  currentRound: number;
  /** Whether the initiating manager has confirmed the agreed slot. */
  initiatorConfirmed: boolean;
  /** Whether the responding manager has confirmed the agreed slot. */
  responderConfirmed: boolean;
  /** Session expiration time in ISO 8601 format. */
  expiresAt?: string;
  /** When the session was created. */
  createdAt: string;
  /** Proposal history for this session. */
  proposals?: NegotiationProposalDto[];
}

/**
 * Response body for POST /negotiations/:sessionId/socket-token.
 * Provides a short-lived token scoped to one negotiation WebSocket subscription.
 *
 * Corresponds to Kotlin DTO: NegotiationSocketTokenResponse.
 */
export interface NegotiationSocketTokenResponse {
  /** Signed token used only for the WebSocket handshake. */
  token: string;
  /** Token lifetime in seconds. */
  expiresInSeconds: number;
}

/**
 * Proposal data returned in API responses.
 * Contains the time slots one side proposed during a negotiation round.
 *
 * Corresponds to Kotlin entity: com.fieldiq.domain.NegotiationProposal
 */
export interface NegotiationProposalDto {
  /** UUID of the proposal. */
  id: string;
  /** UUID of the parent negotiation session. */
  sessionId: string;
  /** Which side sent this proposal. */
  proposedBy: 'initiator' | 'responder';
  /** The negotiation round this proposal belongs to (1-indexed). */
  roundNumber: number;
  /** Array of proposed time windows. */
  slots: TimeSlotDto[];
  /** How the other side responded to these slots. */
  responseStatus: 'pending' | 'accepted' | 'rejected' | 'countered';
  /** Reason for rejection (e.g., "no_availability", "location_conflict"). */
  rejectionReason?: string;
}

/**
 * A single proposed time slot within a negotiation proposal.
 * Used in both proposals and confirmation requests.
 */
export interface TimeSlotDto {
  /** Slot start time in ISO 8601 UTC format. */
  startsAt: string;
  /** Slot end time in ISO 8601 UTC format. */
  endsAt: string;
  /** Proposed venue (optional — may be decided separately). */
  location?: string;
}

/**
 * @deprecated Backend auto-computes proposals via SchedulingService.
 * POST /negotiations/:id/propose takes no request body.
 */
export interface ProposeRequest {
  /** Array of proposed time windows for the game. */
  slots: TimeSlotDto[];
}

/**
 * Request body for POST /negotiations/:sessionId/join.
 * Allows the opposing team's manager to join an existing negotiation session
 * using the single-use invite token.
 *
 * Corresponds to Kotlin DTO: JoinSessionRequest
 */
export interface JoinSessionRequest {
  /** The single-use bearer token from the invite link. */
  inviteToken: string;
  /** UUID of the responding team on their FieldIQ instance. */
  responderTeamId: string;
  /** Base URL of the responder's FieldIQ instance (e.g., "http://localhost:8081"). */
  responderInstance: string;
}

/**
 * Request body for POST /negotiations/:sessionId/respond.
 * Responds to the other team's proposal — accept, reject, or counter.
 *
 * Corresponds to Kotlin DTO: RespondToProposalRequest (to be created in Sprint 4)
 */
export interface RespondToProposalRequest {
  /** How to respond to the proposal. */
  responseStatus: 'accepted' | 'rejected' | 'countered';
  /** Reason for rejection (required when responseStatus is "rejected"). */
  rejectionReason?: string;
  /** Counter-proposal slots (required when responseStatus is "countered"). */
  counterSlots?: TimeSlotDto[];
}

/**
 * Request body for POST /negotiations/:sessionId/confirm.
 * Human confirmation of an agreed-upon time slot. Both managers must confirm
 * before the game is officially scheduled.
 *
 * Corresponds to Kotlin DTO: ConfirmNegotiationRequest (to be created in Sprint 4)
 */
export interface ConfirmNegotiationRequest {
  /** The specific time slot being confirmed. */
  slot: TimeSlotDto;
}

/**
 * Request body for POST /api/negotiate/incoming (cross-instance).
 * Sent by Instance A to Instance B during the join handshake to bootstrap a
 * shadow session. The invite token serves as a bearer credential.
 *
 * Corresponds to Kotlin DTO: IncomingNegotiationRequest
 */
export interface IncomingNegotiationRequest {
  /** UUID of the negotiation session (created by Instance A). */
  sessionId: string;
  /** Bearer credential and key derivation material. */
  inviteToken: string;
  /** UUID of the initiating team on Instance A. */
  initiatorTeamId: string;
  /** Base URL of Instance A. */
  initiatorInstance: string;
  /** UUID of the responding team on Instance B. */
  responderTeamId: string;
  /** Base URL of Instance B. */
  responderInstance: string;
  /** Earliest acceptable game date in YYYY-MM-DD format. */
  requestedDateRangeStart?: string;
  /** Latest acceptable game date in YYYY-MM-DD format. */
  requestedDateRangeEnd?: string;
  /** Desired game duration in minutes. */
  requestedDurationMinutes?: number;
  /** Maximum proposal rounds before failure. */
  maxRounds?: number;
  /** Session expiration time in ISO 8601 format. */
  expiresAt?: string;
}

/**
 * Response from a FieldIQ instance after receiving a relay request.
 * Reports the session's current state after processing.
 *
 * Corresponds to Kotlin DTO: RelayResponse
 */
export interface RelayResponse {
  /** Always "received" for successful processing. */
  status: string;
  /** Session status after processing (e.g., "proposing", "pending_approval"). */
  sessionStatus: string;
  /** Current round number after processing. */
  currentRound: number;
  /** Agreed game start time (set when sessionStatus is "pending_approval"). */
  agreedStartsAt?: string;
  /** Agreed game end time (set when sessionStatus is "pending_approval"). */
  agreedEndsAt?: string;
  /** Agreed game location (set when sessionStatus is "pending_approval"). */
  agreedLocation?: string;
}

// ============================================================================
// WebSocket Messages (server → client only in Phase 1)
// ============================================================================

/**
 * Generic negotiation status update pushed via WebSocket.
 * Sent whenever the negotiation state machine transitions.
 * Clients subscribe at: WS /ws/negotiations/:sessionId?token=JWT
 */
export interface NegotiationUpdateMessage {
  type: 'negotiation_update';
  /** UUID of the negotiation session. */
  sessionId: string;
  /** New state machine status. */
  status: string;
  /** Current round number after this update. */
  currentRound: number;
  /** Description of what just happened (e.g., "proposal_received"). */
  lastEvent: string;
  /** ISO 8601 timestamp of the event. */
  timestamp: string;
}

/**
 * Pushed when the system finds overlapping availability between both teams.
 * Prompts both managers to confirm or reject the proposed time.
 * This is the key UX moment — the "magic" that FieldIQ provides.
 */
export interface MatchFoundMessage {
  type: 'match_found';
  /** UUID of the negotiation session. */
  sessionId: string;
  /** The mutually available time slot found by the algorithm. */
  proposedSlot: TimeSlotDto;
  /** Always true — both managers must confirm before the game is scheduled. */
  awaitingConfirmation: boolean;
}

/**
 * Pushed when both managers have confirmed and the game is officially scheduled.
 * At this point, Event records have been created on both teams.
 */
export interface SessionConfirmedMessage {
  type: 'session_confirmed';
  /** UUID of the negotiation session. */
  sessionId: string;
  /** UUID of the newly created Event on this team. */
  eventId: string;
  /** Confirmed game start time in ISO 8601 UTC format. */
  agreedStartsAt: string;
  /** Confirmed venue, if set. */
  agreedLocation?: string;
}

/**
 * Pushed when a negotiation ends without scheduling a game.
 * The session moves to a terminal state and cannot be resumed.
 */
export interface SessionFailedMessage {
  type: 'session_failed';
  /** UUID of the negotiation session. */
  sessionId: string;
  /** Why the negotiation failed. */
  reason: 'max_rounds_exceeded' | 'expired' | 'cancelled';
}

// ============================================================================
// Devices
// ============================================================================

/**
 * Request body for POST /users/me/devices.
 * Registers an Expo push notification token for the authenticated user.
 * Called on app launch to ensure the server has the current push token.
 *
 * Corresponds to Kotlin DTO: RegisterDeviceRequest (to be created in Sprint 2)
 */
export interface RegisterDeviceRequest {
  /** Expo push token (e.g., "ExponentPushToken[...]"). */
  expoPushToken: string;
  /** Device platform. iOS only in Phase 1, but "android" is accepted for forward compat. */
  platform: 'ios' | 'android';
}
