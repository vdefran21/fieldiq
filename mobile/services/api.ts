import { Platform } from 'react-native';
import type {
  AuthResponse,
  CreateTeamRequest,
  EventDto,
  NegotiationSessionDto,
  RequestOtpRequest,
  RespondToEventRequest,
  TeamDto,
  TeamMemberDto,
  VerifyOtpRequest,
} from '../../shared/types';
import { clearSession, getStoredSession, saveSession } from './session';

const API_BASE = process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080';

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

async function request<T>(path: string, init: RequestInit = {}, retrying = false): Promise<T> {
  const session = await getStoredSession();
  const headers = new Headers(init.headers);
  headers.set('Content-Type', 'application/json');
  if (session?.accessToken) {
    headers.set('Authorization', `Bearer ${session.accessToken}`);
  }

  const response = await fetch(`${API_BASE}${path}`, { ...init, headers });
  if (response.status === 401 && session?.refreshToken && !retrying) {
    await refreshSession(session.refreshToken);
    return request<T>(path, init, true);
  }
  if (!response.ok) {
    const body = await response.text();
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
  const response = await fetch(`${API_BASE}/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  });
  if (!response.ok) {
    await clearSession();
    throw new ApiError('Session expired', response.status);
  }
  const payload = (await response.json()) as AuthResponse;
  await saveSession({ accessToken: payload.accessToken, refreshToken: payload.refreshToken });
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
    respond: (eventId: string, payload: RespondToEventRequest) =>
      request(`/events/${eventId}/respond`, {
        method: 'POST',
        body: JSON.stringify(payload),
      }),
  },
  negotiations: {
    get: (sessionId: string) => request<NegotiationSessionDto>(`/negotiations/${sessionId}`),
    confirm: (sessionId: string, slot: { startsAt: string; endsAt: string; location?: string }) =>
      request<NegotiationSessionDto>(`/negotiations/${sessionId}/confirm`, {
        method: 'POST',
        body: JSON.stringify({ slot }),
      }),
    cancel: (sessionId: string) =>
      request<NegotiationSessionDto>(`/negotiations/${sessionId}/cancel`, {
        method: 'POST',
      }),
  },
};

export { API_BASE };
