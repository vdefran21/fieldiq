import { Platform } from 'react-native';
import type {
  AuthResponse,
  CalendarIntegrationStatusResponse,
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

const API_BASE = process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080';
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

async function request<T>(path: string, init: RequestInit = {}, retrying = false): Promise<T> {
  return requestWithBase<T>(API_BASE, path, init, retrying);
}

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
 */
export const api = {
  auth: {
    requestOtp: (payload: RequestOtpRequest) =>
      request<void>('/auth/request-otp', {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
    verifyOtp: async (payload: VerifyOtpRequest) => {
      const result = await request<AuthResponse>('/auth/verify-otp', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
      await saveSession({ accessToken: result.accessToken, refreshToken: result.refreshToken });
      return result;
    },
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
  user: {
    me: () => request<{ id: string; displayName?: string; phone?: string; email?: string }>('/users/me'),
    registerDevice: (expoPushToken: string) =>
      request<void>('/users/me/devices', {
        method: 'POST',
        body: JSON.stringify({
          expoPushToken,
          platform: Platform.OS === 'ios' ? 'ios' : 'android',
        }),
      }),
  },
  teams: {
    list: () => request<TeamDto[]>('/teams'),
    create: (payload: CreateTeamRequest) =>
      request<TeamDto>('/teams', {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
    get: (teamId: string) => request<TeamDto>(`/teams/${teamId}`),
    members: (teamId: string) => request<TeamMemberDto[]>(`/teams/${teamId}/members`),
    events: (teamId: string) => request<EventDto[]>(`/teams/${teamId}/events`),
  },
  events: {
    create: (teamId: string, payload: CreateEventRequest) =>
      request<EventDto>(`/teams/${teamId}/events`, {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
    respond: (eventId: string, payload: RespondToEventRequest) =>
      request(`/events/${eventId}/respond`, {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
  },
  negotiations: {
    initiate: (payload: InitiateNegotiationRequest) =>
      request<NegotiationSessionDto>('/negotiations', {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
    get: (sessionId: string) => request<NegotiationSessionDto>(`/negotiations/${sessionId}`),
    join: (sessionId: string, payload: JoinSessionRequest, initiatorBaseUrl: string = API_BASE) =>
      requestWithBase<NegotiationSessionDto>(
        initiatorBaseUrl,
        `/negotiations/${sessionId}/join`,
        {
          method: 'POST',
          body: JSON.stringify(payload),
        },
      ),
    propose: (sessionId: string) =>
      request<NegotiationProposalDto>(`/negotiations/${sessionId}/propose`, {
        method: 'POST',
      }),
    respond: (sessionId: string, payload: RespondToProposalRequest) =>
      request<NegotiationSessionDto>(`/negotiations/${sessionId}/respond`, {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
    confirm: (sessionId: string, slot: { startsAt: string; endsAt: string; location?: string }) =>
      request<NegotiationSessionDto>(`/negotiations/${sessionId}/confirm`, {
        method: 'POST',
        body: JSON.stringify({ slot }),
      }),
    socketToken: (sessionId: string) =>
      request<NegotiationSocketTokenResponse>(`/negotiations/${sessionId}/socket-token`, {
        method: 'POST',
      }),
    cancel: (sessionId: string) =>
      request<NegotiationSessionDto>(`/negotiations/${sessionId}/cancel`, {
        method: 'POST',
      }),
  },
  calendar: {
    status: () => request<CalendarIntegrationStatusResponse>('/auth/google/status'),
    authorizeUrl: () => request<GoogleAuthorizeUrlResponse>('/auth/google/authorize-url'),
    disconnect: () =>
      request<void>('/auth/google/disconnect', {
        method: 'DELETE',
      }),
  },
};

export { API_BASE };
