import { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Linking, Pressable, StyleSheet, Text } from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import type { EventDto, NegotiationProposalDto, NegotiationSessionDto } from '../../../../shared/types';
import { Card } from '../../../components/Card';
import { Screen } from '../../../components/Screen';
import { API_BASE, api } from '../../../services/api';
import { usePrimaryTeam } from '../../../hooks/usePrimaryTeam';
import { useNegotiationSocket } from '../../../services/negotiation-websocket';

/**
 * Negotiation review screen with live WebSocket updates and approval actions.
 *
 * This screen now renders the primary Sprint 6 states directly in the mobile shell:
 * active proposing, pending approval, confirmed, and terminal failure/cancellation.
 */
export default function NegotiationScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const [session, setSession] = useState<NegotiationSessionDto | null>(null);
  const [sessionLoading, setSessionLoading] = useState(true);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [confirmedEvent, setConfirmedEvent] = useState<EventDto | null>(null);
  const [sessionReloadKey, setSessionReloadKey] = useState(0);
  const socketMessage = useNegotiationSocket(id);
  const { teams } = usePrimaryTeam();

  useEffect(() => {
    async function loadSession() {
      if (!id) {
        return;
      }

      setSessionLoading(true);
      setSessionError(null);

      try {
        setSession(await api.negotiations.get(id));
      } catch (err) {
        setSessionError(err instanceof Error ? err.message : 'Unable to load negotiation.');
      } finally {
        setSessionLoading(false);
      }
    }

    void loadSession();
  }, [id, sessionReloadKey]);

  useEffect(() => {
    if (!id || !socketMessage) {
      return;
    }

    setSessionReloadKey((value) => value + 1);
  }, [id, socketMessage]);

  useEffect(() => {
    if (!id || !session || session.status !== 'confirmed') {
      setConfirmedEvent(null);
      return;
    }

    const confirmedSession = session;

    async function loadConfirmedEvent() {
      const relevantTeamIds = teams
        .filter((team) => team.id === confirmedSession.initiatorTeamId || team.id === confirmedSession.responderTeamId)
        .map((team) => team.id);

      for (const teamId of relevantTeamIds) {
        const events = await api.teams.events(teamId);
        const matched = events.find((event) => event.negotiationId === id);
        if (matched) {
          setConfirmedEvent(matched);
          return;
        }
      }

      setConfirmedEvent(null);
    }

    void loadConfirmedEvent().catch(() => setConfirmedEvent(null));
  }, [id, session, teams]);

  const statusLine = useMemo(() => {
    if (socketMessage?.type === 'match_found') {
      return 'Match found, awaiting manager confirmation.';
    }
    return socketMessage?.status ?? session?.status ?? 'Loading negotiation...';
  }, [session?.status, socketMessage]);

  const currentTeamId = teams.find((team) => team.id === session?.initiatorTeamId || team.id === session?.responderTeamId)?.id;
  const hasCurrentManagerConfirmed =
    currentTeamId === session?.initiatorTeamId
      ? session?.initiatorConfirmed
      : currentTeamId === session?.responderTeamId
        ? session?.responderConfirmed
        : false;
  const latestProposal: NegotiationProposalDto | null = session?.proposals?.[session.proposals.length - 1] ?? null;
  const agreedSlot =
    session?.agreedStartsAt && session?.agreedEndsAt
      ? {
          startsAt: session.agreedStartsAt,
          endsAt: session.agreedEndsAt,
          location: session.agreedLocation,
        }
      : null;
  const calendarUrl = confirmedEvent?.icsUrl ? `${API_BASE}${confirmedEvent.icsUrl}` : null;

  if (!id) {
    return <Screen title="Negotiation" subtitle="Missing session identifier." />;
  }

  return (
    <Screen title="Negotiation" subtitle="FieldIQ keeps the session live over WebSocket while managers confirm or cancel the negotiated result.">
      <Card>
        <Text style={styles.heading}>Session {id}</Text>
        <Text style={styles.status}>{statusLine}</Text>
        <Text style={styles.meta}>Round {socketMessage?.currentRound ?? session?.currentRound ?? 0}</Text>
      </Card>

      {sessionLoading ? (
        <Card>
          <ActivityIndicator color="#d95d39" />
          <Text style={styles.meta}>Loading negotiation session...</Text>
        </Card>
      ) : null}

      {sessionError ? (
        <Card>
          <Text style={styles.heading}>Negotiation unavailable</Text>
          <Text style={styles.error}>{sessionError}</Text>
          <Pressable style={styles.secondaryButton} onPress={() => setSessionReloadKey((value) => value + 1)}>
            <Text style={styles.secondaryButtonText}>Retry</Text>
          </Pressable>
        </Card>
      ) : null}

      {session?.status === 'proposing' || session?.status === 'pending_response' ? (
        <>
          <Card>
            <Text style={styles.heading}>Finding mutual time</Text>
            <Text style={styles.meta}>
              {session.status === 'pending_response'
                ? 'Waiting for the opposing manager to join this negotiation.'
                : 'FieldIQ is comparing availability and exchanging proposals.'}
            </Text>
            <Text style={styles.meta}>
              {latestProposal
                ? `Latest proposal: ${formatSlot(latestProposal.slots[0]?.startsAt, latestProposal.slots[0]?.endsAt)}`
                : 'No slot proposal has been recorded yet.'}
            </Text>
          </Card>

          <Card>
            <Text style={styles.heading}>Last event</Text>
            <Text style={styles.meta}>{socketMessage?.lastEvent ?? 'Waiting for updates'}</Text>
          </Card>
        </>
      ) : null}

      {session?.status === 'pending_approval' ? (
        <>
          <Card>
            <Text style={styles.heading}>Match found</Text>
            <Text style={styles.meta}>FieldIQ found a slot that works for both teams.</Text>
            <Text style={styles.slot}>{formatSlot(session.agreedStartsAt, session.agreedEndsAt)}</Text>
            <Text style={styles.meta}>{session.agreedLocation ?? 'Location TBD'}</Text>
          </Card>

          <Card>
            <Text style={styles.heading}>{hasCurrentManagerConfirmed ? 'Waiting for the other manager' : 'Manager approval required'}</Text>
            <Text style={styles.meta}>
              {hasCurrentManagerConfirmed
                ? 'Your side is confirmed. The game will schedule after the other manager confirms.'
                : 'Confirm the matched slot to finalize the game, or cancel if the agreement should be abandoned.'}
            </Text>
          </Card>

          {!hasCurrentManagerConfirmed && agreedSlot ? (
            <Pressable
              style={[styles.primaryButton, submitting && styles.buttonDisabled]}
              onPress={async () => {
                setSubmitting(true);
                setSessionError(null);
                try {
                  const confirmed = await api.negotiations.confirm(id, agreedSlot);
                  setSession(confirmed);
                } catch (err) {
                  setSessionError(err instanceof Error ? err.message : 'Unable to confirm the negotiation.');
                } finally {
                  setSubmitting(false);
                }
              }}
              disabled={submitting}
            >
              <Text style={styles.primaryText}>{submitting ? 'Confirming...' : 'Confirm game'}</Text>
            </Pressable>
          ) : null}
        </>
      ) : null}

      {session?.status === 'confirmed' ? (
        <>
          <Card>
            <Text style={styles.heading}>Game scheduled</Text>
            <Text style={styles.slot}>{formatSlot(session.agreedStartsAt, session.agreedEndsAt)}</Text>
            <Text style={styles.meta}>{session.agreedLocation ?? 'Location TBD'}</Text>
          </Card>

          <Card>
            <Text style={styles.heading}>Calendar</Text>
            <Text style={styles.meta}>
              {calendarUrl ? 'Download the iCalendar file to add the confirmed game to a personal calendar.' : 'Waiting for the confirmed event record to expose the calendar file.'}
            </Text>
            {calendarUrl ? (
              <Pressable style={styles.secondaryButton} onPress={() => void Linking.openURL(calendarUrl)}>
                <Text style={styles.secondaryButtonText}>Add to calendar</Text>
              </Pressable>
            ) : null}
          </Card>
        </>
      ) : null}

      {session?.status === 'failed' || session?.status === 'cancelled' ? (
        <Card>
          <Text style={styles.heading}>{session.status === 'failed' ? 'Negotiation failed' : 'Negotiation cancelled'}</Text>
          <Text style={styles.meta}>
            {session.status === 'failed'
              ? 'FieldIQ did not find a mutual time within the configured rounds.'
              : 'One side cancelled this negotiation before scheduling the game.'}
          </Text>
        </Card>
      ) : null}

      {session && session.status !== 'confirmed' && session.status !== 'cancelled' ? (
        <Pressable
          style={[styles.cancelButton, submitting && styles.buttonDisabled]}
          onPress={async () => {
            setSubmitting(true);
            setSessionError(null);
            try {
              const cancelled = await api.negotiations.cancel(id);
              setSession(cancelled);
            } catch (err) {
              setSessionError(err instanceof Error ? err.message : 'Unable to cancel the negotiation.');
            } finally {
              setSubmitting(false);
            }
          }}
          disabled={submitting}
        >
          <Text style={styles.cancelText}>Cancel negotiation</Text>
        </Pressable>
      ) : null}
    </Screen>
  );
}

/**
 * Formats a proposed or agreed slot for human-readable mobile copy.
 *
 * @param startsAt ISO start timestamp.
 * @param endsAt ISO end timestamp.
 * @returns Concise localised label for the slot, or a fallback when incomplete.
 */
function formatSlot(startsAt?: string, endsAt?: string): string {
  if (!startsAt || !endsAt) {
    return 'Awaiting a complete matched slot.';
  }

  const start = new Date(startsAt);
  const end = new Date(endsAt);
  const date = start.toLocaleDateString(undefined, {
    weekday: 'long',
    month: 'short',
    day: 'numeric',
  });
  const startTime = start.toLocaleTimeString(undefined, {
    hour: 'numeric',
    minute: '2-digit',
  });
  const endTime = end.toLocaleTimeString(undefined, {
    hour: 'numeric',
    minute: '2-digit',
  });

  return `${date} · ${startTime} - ${endTime}`;
}

const styles = StyleSheet.create({
  heading: {
    fontSize: 20,
    fontWeight: '700',
    color: '#14281d',
  },
  status: {
    fontSize: 24,
    fontWeight: '700',
    color: '#d95d39',
  },
  meta: {
    color: '#6e665a',
  },
  slot: {
    fontSize: 18,
    fontWeight: '600',
    color: '#2b2d2f',
  },
  error: {
    color: '#b42318',
  },
  primaryButton: {
    backgroundColor: '#14281d',
    borderRadius: 16,
    paddingVertical: 14,
    alignItems: 'center',
  },
  primaryText: {
    color: '#f6f4ee',
    fontWeight: '700',
  },
  secondaryButton: {
    borderRadius: 16,
    paddingVertical: 14,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#c8c1b1',
    backgroundColor: '#fffdf8',
  },
  secondaryButtonText: {
    color: '#2b2d2f',
    fontWeight: '600',
  },
  cancelButton: {
    backgroundColor: '#fff1eb',
    borderWidth: 1,
    borderColor: '#d95d39',
    borderRadius: 16,
    paddingVertical: 14,
    alignItems: 'center',
  },
  cancelText: {
    color: '#d95d39',
    fontWeight: '700',
  },
  buttonDisabled: {
    opacity: 0.6,
  },
});
