import { useEffect, useState } from 'react';
import { API_BASE } from './api';
import { getStoredSession } from './session';

/**
 * Shared negotiation update payload shape from the backend WebSocket stream.
 */
export interface NegotiationSocketMessage {
  type: 'negotiation_update' | 'match_found' | 'session_confirmed' | 'session_failed';
  status?: string;
  lastEvent?: string;
  currentRound?: number;
  sessionId: string;
  agreedStartsAt?: string;
  agreedLocation?: string;
}

/**
 * Subscribes to the backend's negotiation WebSocket for one session.
 *
 * @param sessionId Negotiation session to subscribe to.
 * @returns Most recent message received on the socket, or null.
 */
export function useNegotiationSocket(sessionId: string) {
  const [message, setMessage] = useState<NegotiationSocketMessage | null>(null);

  useEffect(() => {
    let socket: WebSocket | null = null;
    let cancelled = false;

    async function connect() {
      const session = await getStoredSession();
      if (!session || cancelled) {
        return;
      }

      const wsBase = API_BASE.replace(/^http/, 'ws');
      socket = new WebSocket(`${wsBase}/ws/negotiations/${sessionId}?token=${session.accessToken}`);
      socket.onmessage = (event) => {
        setMessage(JSON.parse(event.data) as NegotiationSocketMessage);
      };
    }

    void connect();

    return () => {
      cancelled = true;
      socket?.close();
    };
  }, [sessionId]);

  return message;
}
