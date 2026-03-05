// FieldIQ Shared Types
// API contract interfaces shared between backend (Kotlin DTOs match manually) and frontend

// ============================================================================
// Auth
// ============================================================================

export interface RequestOtpRequest {
  channel: 'sms' | 'email';
  value: string; // phone number or email
}

export interface VerifyOtpRequest {
  channel: 'sms' | 'email';
  value: string;
  otp: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  user: UserDto;
}

export interface RefreshTokenRequest {
  refreshToken: string;
}

// ============================================================================
// Users
// ============================================================================

export interface UserDto {
  id: string;
  phone?: string;
  email?: string;
  displayName?: string;
}

// ============================================================================
// Teams
// ============================================================================

export interface TeamDto {
  id: string;
  orgId?: string;
  name: string;
  sport: string;
  ageGroup?: string;
  season?: string;
}

export interface CreateTeamRequest {
  name: string;
  orgId?: string;
  sport?: string;
  ageGroup?: string;
  season?: string;
}

export interface TeamMemberDto {
  id: string;
  teamId: string;
  userId: string;
  role: 'manager' | 'coach' | 'parent';
  playerName?: string;
  isActive: boolean;
  user?: UserDto;
}

export interface AddTeamMemberRequest {
  userId: string;
  role: 'manager' | 'coach' | 'parent';
  playerName?: string;
}

// ============================================================================
// Events
// ============================================================================

export interface EventDto {
  id: string;
  teamId: string;
  eventType: 'game' | 'practice' | 'tournament' | 'other';
  title?: string;
  location?: string;
  locationNotes?: string;
  startsAt?: string; // ISO 8601
  endsAt?: string;
  status: 'draft' | 'scheduled' | 'cancelled' | 'completed';
  opponentName?: string;
  negotiationId?: string;
}

export interface CreateEventRequest {
  eventType: 'game' | 'practice' | 'tournament' | 'other';
  title?: string;
  location?: string;
  locationNotes?: string;
  startsAt?: string;
  endsAt?: string;
}

export interface EventResponseDto {
  id: string;
  eventId: string;
  userId: string;
  status: 'going' | 'not_going' | 'maybe' | 'no_response';
  respondedAt?: string;
  user?: UserDto;
}

export interface RespondToEventRequest {
  status: 'going' | 'not_going' | 'maybe';
}

// ============================================================================
// Availability
// ============================================================================

export interface AvailabilityWindowDto {
  id: string;
  teamId: string;
  userId: string;
  dayOfWeek?: number; // 0-6
  specificDate?: string; // YYYY-MM-DD
  startTime: string; // HH:mm
  endTime: string;
  windowType: 'available' | 'unavailable';
  source: 'manual' | 'google_cal' | 'apple_cal';
}

export interface CreateAvailabilityWindowRequest {
  teamId: string;
  dayOfWeek?: number;
  specificDate?: string;
  startTime: string;
  endTime: string;
  windowType: 'available' | 'unavailable';
}

// ============================================================================
// Scheduling
// ============================================================================

export interface SuggestWindowsRequest {
  dateRangeStart: string; // YYYY-MM-DD
  dateRangeEnd: string;
  durationMinutes: number;
  preferredDays?: number[];
}

export interface TimeWindowDto {
  startsAt: string; // ISO 8601
  endsAt: string;
  confidence: number; // 0.0-1.0
}

// ============================================================================
// Negotiation
// ============================================================================

export interface InitiateNegotiationRequest {
  teamId: string;
  dateRangeStart: string;
  dateRangeEnd: string;
  durationMinutes?: number;
  preferredDays?: number[];
}

export interface NegotiationSessionDto {
  id: string;
  initiatorTeamId: string;
  responderTeamId?: string;
  status:
    | 'pending_response'
    | 'proposing'
    | 'pending_approval'
    | 'confirmed'
    | 'failed'
    | 'cancelled';
  requestedDateRangeStart?: string;
  requestedDateRangeEnd?: string;
  requestedDurationMinutes: number;
  agreedStartsAt?: string;
  agreedLocation?: string;
  inviteToken?: string;
  maxRounds: number;
  currentRound: number;
  expiresAt?: string;
  createdAt: string;
}

export interface NegotiationProposalDto {
  id: string;
  sessionId: string;
  proposedBy: 'initiator' | 'responder';
  roundNumber: number;
  slots: TimeSlotDto[];
  responseStatus: 'pending' | 'accepted' | 'rejected' | 'countered';
  rejectionReason?: string;
}

export interface TimeSlotDto {
  startsAt: string;
  endsAt: string;
  location?: string;
}

export interface ProposeRequest {
  slots: TimeSlotDto[];
}

export interface RespondToProposalRequest {
  responseStatus: 'accepted' | 'rejected' | 'countered';
  rejectionReason?: string;
  counterSlots?: TimeSlotDto[];
}

export interface ConfirmNegotiationRequest {
  slot: TimeSlotDto;
}

// ============================================================================
// WebSocket Messages
// ============================================================================

export interface NegotiationUpdateMessage {
  type: 'negotiation_update';
  sessionId: string;
  status: string;
  currentRound: number;
  lastEvent: string;
  timestamp: string;
}

export interface MatchFoundMessage {
  type: 'match_found';
  sessionId: string;
  proposedSlot: TimeSlotDto;
  awaitingConfirmation: boolean;
}

export interface SessionConfirmedMessage {
  type: 'session_confirmed';
  sessionId: string;
  eventId: string;
  agreedStartsAt: string;
  agreedLocation?: string;
}

export interface SessionFailedMessage {
  type: 'session_failed';
  sessionId: string;
  reason: 'max_rounds_exceeded' | 'expired' | 'cancelled';
}

// ============================================================================
// Devices
// ============================================================================

export interface RegisterDeviceRequest {
  expoPushToken: string;
  platform: 'ios' | 'android';
}
