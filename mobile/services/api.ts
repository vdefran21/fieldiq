/**
 * Mobile HTTP client for the FieldIQ Phase 1 backend.
 *
 * This module centralizes all authenticated and anonymous REST calls used by the
 * Expo app so screens and hooks do not duplicate URL construction, auth header
 * handling, refresh-token retries, or backend-specific error parsing.
 *
 * Key behaviors:
 * - Uses `EXPO_PUBLIC_API_URL` when present so local mobile demos can target a
 *   specific backend instance over LAN.
 * - Automatically attaches the stored bearer token when available.
 * - Retries one time after a 401 by calling the refresh-token endpoint.
 * - Normalizes backend error envelopes into `ApiError` messages suitable for UI.
 *
 * Side effects:
 * - Reads and writes the persisted mobile session via `./session`.
 * - Logs development-only request diagnostics to Metro.
 * - Performs outbound network requests to the Kotlin backend.
 *
 * @see ../../shared/types for the TypeScript contracts mirrored from backend DTOs.
 * @see ./session for persisted access-token and refresh-token storage.
 */
import { Platform } from 'react-native';
import type {
  AvailabilityWindowDto,
  AuthResponse,
  CalendarIntegrationStatusResponse,
  CreateAvailabilityWindowRequest,
  CreateTeamRequest,
  CreateEventRequest,
  EventDto,
  GoogleAuthorizeUrlResponse,
  InitiateNegotiationRequest,
  JoinSessionRequest,
  NegotiationProposalDto,
  NegotiationSocketTokenResponse,
  NegotiationSessionDto,
  RequestOtpRequest,
  RespondToProposalRequest,
  RespondToEventRequest,
  TeamDto,
  TeamMemberDto,
  VerifyOtpRequest,
} from '../../shared/types';
import { clearSession, getStoredSession, saveSession } from './session';

/**
 * Resolved backend base URL for the current app session.
 *
 * Local development defaults to `http://localhost:8080`, while device-based
 * testing and the two-instance demo override this with `EXPO_PUBLIC_API_URL`.
 */
const API_BASE = process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080';

/**
 * Enables verbose request diagnostics only in React Native development builds.
 *
 * Production clients should avoid logging request metadata because it adds noise
 * and can expose implementation details that are only useful during debugging.
 */
const API_DEBUG_ENABLED = typeof __DEV__ !== 'undefined' && __DEV__;

/**
 * Tracks whether the resolved API base URL has already been logged for the current app session.
 *
 * Repeating the same base URL on every request would make Metro logs noisy, so the mobile
 * client announces it once and then emits per-request diagnostics separately.
 */
let hasLoggedApiBase = false;

/**
 * Error thrown when an API response is not successful.
 */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

/**
 * Lightweight profile payload returned by `GET /users/me`.
 *
 * The mobile app currently uses this shape to determine whether the manager has
 * completed onboarding and to prefill simple account views. `displayName`,
 * `phone`, and `email` stay optional because the backend can omit them when the
 * user has not supplied that identifier type.
 */
interface CurrentUserProfile {
  /** Stable backend UUID for the authenticated manager account. */
  id: string;
  /** Human-readable name shown in mobile account surfaces when present. */
  displayName?: string;
  /** Verified phone number used for OTP auth when the account is phone-based. */
  phone?: string;
  /** Verified email address used for OTP auth when the account is email-based. */
  email?: string;
}

/**
 * Extracts a readable API error message from a raw HTTP response body.
 *
 * The backend returns JSON `ErrorResponse` envelopes for most failures. Mobile screens
 * should show the human-readable `message` field when present instead of a raw JSON blob.
 *
 * @param body Raw response text from a failed request.
 * @param fallback Default fallback if the body is empty or unparsable.
 * @returns Human-readable error message for the UI.
 */
function parseErrorMessage(body: string, fallback: string): string {
  if (!body) {
    return fallback;
  }

  try {
    const parsed = JSON.parse(body) as { message?: string };
    return parsed.message || fallback;
  } catch {
    return body;
  }
}

/**
 * Writes a development-only API debug line to Metro.
 *
 * The mobile app relies on raw `fetch`, so transport failures can otherwise appear only as
 * generic "Network request failed" exceptions. These logs capture the resolved URL and request
 * lifecycle without exposing request bodies or bearer tokens.
 *
 * @param message Human-readable message fragment to append after the `[api]` prefix.
 */
function logApiDebug(message: string): void {
  if (!API_DEBUG_ENABLED) {
    return;
  }
  console.log(`[api] ${message}`);
}

/**
 * Logs the resolved API base URL once per app launch in development builds.
 */
function logApiBaseOnce(): void {
  if (hasLoggedApiBase) {
    return;
  }
  hasLoggedApiBase = true;
  logApiDebug(`API_BASE=${API_BASE}`);
}

/**
 * Issues an HTTP request against the primary configured backend base URL.
 *
 * Most mobile flows stay on one backend instance, so this helper keeps callers
 * concise while delegating auth headers, retry logic, and JSON parsing to
 * `requestWithBase`.
 *
 * @param path Backend-relative API path beginning with `/`.
 * @param init Fetch configuration, including method and optional JSON body.
 * @param retrying Whether this call is already the post-refresh retry attempt.
 * @returns Parsed JSON response, or `undefined` for empty/204 responses.
 * @throws ApiError When the backend returns a non-success status.
 * @throws Error When the network transport fails before a response is received.
 * @see requestWithBase for the shared implementation details.
 */
async function request<T>(path: string, init: RequestInit = {}, retrying = false): Promise<T> {
  return requestWithBase<T>(API_BASE, path, init, retrying);
}

/**
 * Executes one HTTP request against a specific backend base URL.
 *
 * This helper exists because most mobile calls should use the locally configured
 * backend instance, but negotiation join flows sometimes need to call the remote
 * initiator instance directly. It also owns the mobile auth contract:
 * - load stored bearer credentials
 * - attach JSON headers
 * - retry one time after a 401 by refreshing the session
 * - convert backend error envelopes into `ApiError`
 *
 * @param baseUrl Absolute backend origin to target.
 * @param path Backend-relative API path beginning with `/`.
 * @param init Fetch configuration, including method and optional JSON body.
 * @param retrying Whether the caller is already on the single allowed retry.
 * @returns Parsed JSON response, or `undefined` for empty/204 responses.
 * @throws ApiError When the backend rejects the request or refresh ultimately fails.
 * @throws Error When the transport layer fails before the backend responds.
 * @see refreshSession for the refresh-token retry path.
 */
async function requestWithBase<T>(baseUrl: string, path: string, init: RequestInit = {}, retrying = false): Promise<T> {
  logApiBaseOnce();
  const session = await getStoredSession();
  const headers = new Headers(init.headers);
  headers.set('Content-Type', 'application/json');
  if (session?.accessToken) {
    headers.set('Authorization', `Bearer ${session.accessToken}`);
  }

  const method = init.method ?? 'GET';
  const url = `${baseUrl}${path}`;
  logApiDebug(`request ${method} ${url} retry=${retrying} auth=${session?.accessToken ? 'yes' : 'no'}`);

  let response: Response;
  try {
    response = await fetch(url, { ...init, headers });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    logApiDebug(`transport error ${method} ${url}: ${message}`);
    throw error;
  }

  logApiDebug(`response ${method} ${url} -> ${response.status}`);
  if (response.status === 401 && session?.refreshToken && !retrying) {
    logApiDebug(`refreshing session after 401 from ${method} ${url}`);
    await refreshSession(session.refreshToken);
    return requestWithBase<T>(baseUrl, path, init, true);
  }
  if (!response.ok) {
    const body = await response.text();
    logApiDebug(`api error ${method} ${url} -> ${response.status} ${parseErrorMessage(body, response.statusText)}`);
    throw new ApiError(parseErrorMessage(body, response.statusText), response.status);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  if (!text.trim()) {
    return undefined as T;
  }

  return JSON.parse(text) as T;
}

/**
 * Exchanges a stored refresh token for a new access/refresh token pair.
 *
 * The mobile client uses this only after a 401 response from another request.
 * If refresh fails, the stored session is cleared immediately so subsequent UI
 * flows can route the manager back through OTP login instead of looping.
 *
 * @param refreshToken Previously issued refresh token from a successful login.
 * @returns Promise that resolves after the new session is persisted.
 * @throws ApiError When the refresh endpoint rejects the token.
 * @throws Error When the network transport fails before refresh completes.
 */
async function refreshSession(refreshToken: string): Promise<void> {
  const url = `${API_BASE}/auth/refresh`;
  logApiDebug(`request POST ${url} retry=false auth=no refresh=yes`);

  let response: Response;
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    logApiDebug(`transport error POST ${url}: ${message}`);
    throw error;
  }

  logApiDebug(`response POST ${url} -> ${response.status}`);
  if (!response.ok) {
    await clearSession();
    logApiDebug(`refresh failed -> ${response.status}; clearing stored session`);
    throw new ApiError('Session expired', response.status);
  }
  const payload = (await response.json()) as AuthResponse;
  await saveSession({ accessToken: payload.accessToken, refreshToken: payload.refreshToken });
  logApiDebug('session refresh succeeded');
}

/**
 * Mobile API client matching the current backend surface.
 *
 * Each namespace mirrors a backend feature area so screens can depend on a
 * stable, typed interface instead of constructing ad hoc `fetch` calls.
 */
export const api = {
  /**
   * Authentication endpoints used during OTP sign-in and logout.
   */
  auth: {
    /**
     * Starts an OTP login challenge for a phone number or email identifier.
     *
     * @param payload Identifier to challenge, matching the backend auth contract.
     * @returns Promise that resolves when the backend accepts the OTP request.
     * @throws ApiError When the identifier is invalid or rate limited.
     */
    requestOtp: (payload: RequestOtpRequest) =>
      request<void>('/auth/request-otp', {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
    /**
     * Verifies an OTP code and persists the returned mobile session.
     *
     * @param payload Identifier and one-time code supplied by the manager.
     * @returns Auth tokens and profile metadata returned by the backend.
     * @throws ApiError When the OTP is invalid, expired, or bound to a different identifier.
     */
    verifyOtp: async (payload: VerifyOtpRequest) => {
      const result = await request<AuthResponse>('/auth/verify-otp', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
      await saveSession({ accessToken: result.accessToken, refreshToken: result.refreshToken });
      return result;
    },
    /**
     * Revokes the current refresh token when present and clears local session state.
     *
     * The backend call is skipped when no refresh token is stored so local logout
     * still succeeds for partially initialized sessions.
     *
     * @returns Promise that resolves after local credentials are removed.
     * @throws ApiError When the backend rejects the logout request.
     */
    logout: async () => {
      const session = await getStoredSession();
      if (session?.refreshToken) {
        await request<void>('/auth/logout', {
          method: 'POST',
          body: JSON.stringify({ refreshToken: session.refreshToken }),
        });
      }
      await clearSession();
    },
  },
  /**
   * Endpoints scoped to the currently authenticated manager account.
   */
  user: {
    /**
     * Loads the authenticated manager profile used by onboarding and settings flows.
     *
     * @returns Current user profile for the stored session.
     * @throws ApiError When the session is missing or no longer valid.
     */
    me: () => request<CurrentUserProfile>('/users/me'),
    /**
     * Registers the current device for Expo push notifications.
     *
     * The platform value is derived from React Native so backend notification
     * records stay aligned with the physical device that received the token.
     *
     * @param expoPushToken Expo-issued push token for this device install.
     * @returns Promise that resolves when the backend stores the token.
     * @throws ApiError When the current manager is unauthorized or the token is invalid.
     */
    registerDevice: (expoPushToken: string) =>
      request<void>('/users/me/devices', {
        method: 'POST',
        body: JSON.stringify({
          expoPushToken,
          platform: Platform.OS === 'ios' ? 'ios' : 'android',
        }),
      }),
  },
  /**
   * Team and roster endpoints used by schedule, roster, and onboarding screens.
   */
  teams: {
    /** Lists the teams the current manager can access on this backend instance. */
    list: () => request<TeamDto[]>('/teams'),
    /**
     * Creates a team and makes the current manager its initial manager member.
     *
     * @param payload Team creation request from mobile onboarding.
     * @returns Newly created team DTO.
     */
    create: (payload: CreateTeamRequest) =>
      request<TeamDto>('/teams', {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
    /** Loads one team by ID after backend access-control checks. */
    get: (teamId: string) => request<TeamDto>(`/teams/${teamId}`),
    /** Lists roster members visible to the current manager for one team. */
    members: (teamId: string) => request<TeamMemberDto[]>(`/teams/${teamId}/members`),
    /** Lists scheduled events already attached to one team. */
    events: (teamId: string) => request<EventDto[]>(`/teams/${teamId}/events`),
  },
  /**
   * Event lifecycle endpoints for creating and responding to team events.
   */
  events: {
    /**
     * Creates one event on the selected team.
     *
     * @param teamId Team that will own the new event.
     * @param payload Event details from the create-event screen.
     * @returns Persisted event DTO from the backend.
     */
    create: (teamId: string, payload: CreateEventRequest) =>
      request<EventDto>(`/teams/${teamId}/events`, {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
    /**
     * Sends one RSVP decision for an event invitation.
     *
     * @param eventId Event being answered by the current manager/member context.
     * @param payload Attendance decision and optional response metadata.
     * @returns Promise that resolves when the backend stores the RSVP.
     */
    respond: (eventId: string, payload: RespondToEventRequest) =>
      request(`/events/${eventId}/respond`, {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
  },
  /**
   * Availability endpoints used by the recurring baseline setup flow.
   */
  availability: {
    /** Lists the current manager's own availability windows. */
    listMine: () => request<AvailabilityWindowDto[]>('/users/me/availability'),
    /** Lists the aggregated availability windows for a specific team. */
    listTeam: (teamId: string) => request<AvailabilityWindowDto[]>(`/teams/${teamId}/availability`),
    /**
     * Creates one availability window owned by the current manager.
     *
     * @param payload Manual or recurring availability window definition.
     * @returns Persisted availability window DTO.
     */
    create: (payload: CreateAvailabilityWindowRequest) =>
      request<AvailabilityWindowDto>('/users/me/availability', {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
  },
  /**
   * Cross-team negotiation endpoints backing the Phase 1 scheduling workflow.
   */
  negotiations: {
    /**
     * Starts a new negotiation session from the current backend instance.
     *
     * @param payload Initial negotiation request, including the local and remote teams.
     * @returns Newly created negotiation session state.
     */
    initiate: (payload: InitiateNegotiationRequest) =>
      request<NegotiationSessionDto>('/negotiations', {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
    /** Loads the latest persisted state for one negotiation session. */
    get: (sessionId: string) => request<NegotiationSessionDto>(`/negotiations/${sessionId}`),
    /**
     * Joins a negotiation by calling the initiator's backend instance directly.
     *
     * This is the main reason `requestWithBase` exists: responders may need to
     * talk to a different FieldIQ instance than the one configured as `API_BASE`.
     *
     * @param sessionId Negotiation session identifier created by the initiator.
     * @param payload Invite token and responder context required by the backend.
     * @param initiatorBaseUrl Absolute origin for the initiating backend instance.
     * @returns Updated negotiation session after the join succeeds.
     */
    join: (sessionId: string, payload: JoinSessionRequest, initiatorBaseUrl: string = API_BASE) =>
      requestWithBase<NegotiationSessionDto>(
        initiatorBaseUrl,
        `/negotiations/${sessionId}/join`,
        {
          method: 'POST',
          body: JSON.stringify(payload),
        },
      ),
    /** Requests the backend to generate or select the next proposal round. */
    propose: (sessionId: string) =>
      request<NegotiationProposalDto>(`/negotiations/${sessionId}/propose`, {
        method: 'POST',
      }),
    /**
     * Sends an accept, reject, or counter response to the active proposal.
     *
     * @param sessionId Negotiation session being advanced.
     * @param payload Proposal response chosen by the manager.
     * @returns Updated session snapshot after backend state transitions.
     */
    respond: (sessionId: string, payload: RespondToProposalRequest) =>
      request<NegotiationSessionDto>(`/negotiations/${sessionId}/respond`, {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
    /**
     * Confirms a mutually agreed slot so both managers can finalize the game.
     *
     * @param sessionId Negotiation session entering confirmed state.
     * @param slot Matched time window and optional location string.
     * @returns Updated session reflecting confirmation progress or completion.
     */
    confirm: (sessionId: string, slot: { startsAt: string; endsAt: string; location?: string }) =>
      request<NegotiationSessionDto>(`/negotiations/${sessionId}/confirm`, {
        method: 'POST',
        body: JSON.stringify({ slot }),
      }),
    /**
     * Exchanges the mobile JWT for a short-lived websocket token scoped to one
     * negotiation session.
     *
     * @param sessionId Negotiation session the realtime channel will subscribe to.
     * @returns Short-lived websocket auth token and connection metadata.
     */
    socketToken: (sessionId: string) =>
      request<NegotiationSocketTokenResponse>(`/negotiations/${sessionId}/socket-token`, {
        method: 'POST',
      }),
    /** Cancels a negotiation session from the mobile approval flow. */
    cancel: (sessionId: string) =>
      request<NegotiationSessionDto>(`/negotiations/${sessionId}/cancel`, {
        method: 'POST',
      }),
  },
  /**
   * Google Calendar connection endpoints shown in mobile settings.
   */
  calendar: {
    /** Loads the current Google Calendar connection state for the manager. */
    status: () => request<CalendarIntegrationStatusResponse>('/auth/google/status'),
    /** Requests the browser handoff URL used to start Google OAuth. */
    authorizeUrl: () => request<GoogleAuthorizeUrlResponse>('/auth/google/authorize-url'),
    /** Disconnects the current Google Calendar integration from the account. */
    disconnect: () =>
      request<void>('/auth/google/disconnect', {
        method: 'DELETE',
      }),
  },
};

/**
 * Exported backend base URL for modules that need to display or reuse the
 * resolved origin chosen for the current mobile session.
 */
export { API_BASE };
