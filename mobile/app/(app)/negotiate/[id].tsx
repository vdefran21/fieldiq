import { useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Animated, Linking, Pressable, Share, StyleSheet, Text, TextInput, View } from 'react-native';
import { File, Paths } from 'expo-file-system';
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
 * This screen exposes the core manager loop for Phase 1: invite sharing, proposal
 * generation, counter-suggestions, confirmation, and `.ics` download after scheduling.
 */
export default function NegotiationScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const [session, setSession] = useState<NegotiationSessionDto | null>(null);
  const [sessionLoading, setSessionLoading] = useState(true);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [confirmedEvent, setConfirmedEvent] = useState<EventDto | null>(null);
  const [sessionReloadKey, setSessionReloadKey] = useState(0);
  const [showCounterForm, setShowCounterForm] = useState(false);
  const [counterStartsAt, setCounterStartsAt] = useState('');
  const [counterEndsAt, setCounterEndsAt] = useState('');
  const [counterLocation, setCounterLocation] = useState('');
  const socketMessage = useNegotiationSocket(id);
  const { teams } = usePrimaryTeam();
  const pulse = useRef(new Animated.Value(0.85)).current;

  useEffect(() => {
    const animation = Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, { toValue: 1, duration: 900, useNativeDriver: true }),
        Animated.timing(pulse, { toValue: 0.85, duration: 900, useNativeDriver: true }),
      ]),
    );

    animation.start();
    return () => animation.stop();
  }, [pulse]);

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
    if (!id || !session || ['confirmed', 'failed', 'cancelled'].includes(session.status)) {
      return;
    }

    const interval = setInterval(() => {
      setSessionReloadKey((value) => value + 1);
    }, 5000);

    return () => clearInterval(interval);
  }, [id, session]);

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
  const currentActor =
    currentTeamId === session?.initiatorTeamId ? 'initiator' : currentTeamId === session?.responderTeamId ? 'responder' : null;
  const hasCurrentManagerConfirmed =
    currentTeamId === session?.initiatorTeamId
      ? session?.initiatorConfirmed
      : currentTeamId === session?.responderTeamId
        ? session?.responderConfirmed
        : false;
  const hasCounterpartManagerConfirmed =
    currentTeamId === session?.initiatorTeamId
      ? session?.responderConfirmed
      : currentTeamId === session?.responderTeamId
        ? session?.initiatorConfirmed
        : false;
  const latestProposal: NegotiationProposalDto | null = session?.proposals?.[session.proposals.length - 1] ?? null;
  const latestCounterpartProposal =
    session?.proposals
      ?.filter((proposal) => proposal.proposedBy !== currentActor)
      .sort((left, right) => right.roundNumber - left.roundNumber)?.[0] ?? null;
  const agreedSlot =
    session?.agreedStartsAt && session?.agreedEndsAt
      ? {
          startsAt: session.agreedStartsAt,
          endsAt: session.agreedEndsAt,
          location: session.agreedLocation,
        }
      : null;
  const calendarUrl = confirmedEvent?.icsUrl ? `${API_BASE}${confirmedEvent.icsUrl}` : null;
  const inviteShareText = session?.inviteToken
    ? buildInviteShareText(API_BASE, session.id, session.inviteToken)
    : null;

  if (!id) {
    return <Screen title="Negotiation" subtitle="Missing session identifier." />;
  }

  function refreshSession() {
    setSessionReloadKey((value) => value + 1);
  }

  async function openCalendarFile() {
    if (!confirmedEvent?.id) {
      return;
    }

    setSubmitting(true);
    setSessionError(null);

    try {
      const exportPayload = await api.events.downloadIcs(confirmedEvent.id);
      const fileName = exportPayload.filename ?? `fieldiq-event-${confirmedEvent.id}.ics`;
      const localFile = new File(Paths.cache, fileName);

      if (localFile.exists) {
        localFile.delete();
      }

      localFile.write(exportPayload.body);

      const canOpen = await Linking.canOpenURL(localFile.uri);
      if (canOpen) {
        await Linking.openURL(localFile.uri);
        return;
      }

      await Share.share({
        title: 'FieldIQ calendar export',
        url: localFile.uri,
        message: 'FieldIQ calendar export',
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Unable to download the calendar file.';
      setSessionError(message);
      Alert.alert('Calendar export failed', message);
    } finally {
      setSubmitting(false);
    }
  }

  async function submitCounterProposal() {
    if (!counterStartsAt.trim() || !counterEndsAt.trim()) {
      setSessionError('Counter proposals need both a start and end time.');
      return;
    }

    setSubmitting(true);
    setSessionError(null);

    try {
      const updated = await api.negotiations.respond(id, {
        responseStatus: 'countered',
        counterSlots: [
          {
            startsAt: counterStartsAt.trim(),
            endsAt: counterEndsAt.trim(),
            location: counterLocation.trim() || undefined,
          },
        ],
      });
      setSession(updated);
      setShowCounterForm(false);
      setCounterStartsAt('');
      setCounterEndsAt('');
      setCounterLocation('');
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Unable to send the counter proposal.';
      setSessionError(message);
      Alert.alert('Counter proposal failed', message);
    } finally {
      setSubmitting(false);
    }
  }

  async function shareInviteDetails() {
    if (!inviteShareText) {
      setSessionError('Invite details are unavailable until the session is ready to share.');
      return;
    }

    try {
      await Share.share({
        message: inviteShareText,
        title: 'FieldIQ negotiation invite',
      });
    } catch (err) {
      setSessionError(err instanceof Error ? err.message : 'Unable to share the invite details.');
    }
  }

  return (
    <Screen title="Negotiation" subtitle="FieldIQ keeps the session live while managers share, join, propose, counter, and confirm the result.">
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
          <Pressable style={styles.secondaryButton} onPress={() => void refreshSession()}>
            <Text style={styles.secondaryButtonText}>Retry</Text>
          </Pressable>
        </Card>
      ) : null}

      {session?.status === 'pending_response' ? (
        <>
          <Card>
            <Text style={styles.heading}>Share invite details</Text>
            <Text style={styles.meta}>The other manager needs the initiating instance, session ID, and invite token to join.</Text>
            <Text style={styles.detailLabel}>Initiator instance</Text>
            <Text style={styles.detailValue}>{API_BASE}</Text>
            <Text style={styles.detailLabel}>Session ID</Text>
            <Text style={styles.detailValue}>{session.id}</Text>
            <Text style={styles.detailLabel}>Invite token</Text>
            <Text style={styles.inviteToken}>{session.inviteToken ?? 'Invite token unavailable after join.'}</Text>
            <Pressable style={styles.primaryButton} onPress={() => void shareInviteDetails()} disabled={!inviteShareText}>
              <Text style={styles.primaryText}>Share invite details</Text>
            </Pressable>
            {inviteShareText ? <Text selectable style={styles.sharePayload}>{inviteShareText}</Text> : null}
          </Card>

          <Card>
            <Text style={styles.heading}>Waiting for join</Text>
            <Text style={styles.meta}>After the other manager joins from their instance, return here and start a proposal round.</Text>
          </Card>
        </>
      ) : null}

      {session?.status === 'proposing' ? (
        <>
          <Card>
            <Text style={styles.heading}>Finding mutual time</Text>
            <Animated.View style={[styles.findingPulse, { transform: [{ scale: pulse }] }]} />
            <Text style={styles.meta}>FieldIQ is comparing availability and exchanging proposal rounds.</Text>
            <Text style={styles.meta}>
              {latestProposal
                ? `Latest proposal: ${formatSlot(latestProposal.slots[0]?.startsAt, latestProposal.slots[0]?.endsAt)}`
                : 'No slot proposal has been recorded yet.'}
            </Text>
            <Text style={styles.meta}>Last event: {socketMessage?.lastEvent ?? 'Waiting for updates'}</Text>
          </Card>

          <Card>
            <Text style={styles.heading}>Proposal controls</Text>
            <Text style={styles.meta}>Kick off or advance a proposal round from this device.</Text>
            <Pressable
              style={[styles.primaryButton, submitting && styles.buttonDisabled]}
              onPress={async () => {
                setSubmitting(true);
                setSessionError(null);
                try {
                  await api.negotiations.propose(id);
                  await refreshSession();
                } catch (err) {
                  setSessionError(err instanceof Error ? err.message : 'Unable to generate the next proposal round.');
                } finally {
                  setSubmitting(false);
                }
              }}
              disabled={submitting}
            >
              <Text style={styles.primaryText}>{submitting ? 'Generating...' : 'Send proposal round'}</Text>
            </Pressable>
          </Card>

          {latestCounterpartProposal ? (
            <Card>
              <Text style={styles.heading}>Suggest a different time</Text>
              <Text style={styles.meta}>Latest counterpart slot: {formatSlot(latestCounterpartProposal.slots[0]?.startsAt, latestCounterpartProposal.slots[0]?.endsAt)}</Text>
              <Pressable style={styles.secondaryButton} onPress={() => setShowCounterForm((value) => !value)}>
                <Text style={styles.secondaryButtonText}>{showCounterForm ? 'Hide counter form' : 'Counter with another slot'}</Text>
              </Pressable>
              {showCounterForm ? (
                <CounterForm
                  startsAt={counterStartsAt}
                  endsAt={counterEndsAt}
                  location={counterLocation}
                  onStartsAtChange={setCounterStartsAt}
                  onEndsAtChange={setCounterEndsAt}
                  onLocationChange={setCounterLocation}
                  onSubmit={submitCounterProposal}
                  submitting={submitting}
                />
              ) : null}
            </Card>
          ) : null}
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
                ? 'Your side is confirmed. The game schedules after the other manager confirms.'
                : hasCounterpartManagerConfirmed
                  ? 'The other manager already confirmed this slot. Your approval will schedule the game immediately.'
                  : 'Confirm the matched slot now, or counter with a different time if this match should be adjusted.'}
            </Text>
          </Card>

          <Card>
            <Text style={styles.heading}>Confirmation progress</Text>
            <View style={styles.confirmationRow}>
              <Text style={styles.detailLabel}>Your side</Text>
              <Text style={hasCurrentManagerConfirmed ? styles.confirmedBadge : styles.pendingBadge}>
                {hasCurrentManagerConfirmed ? 'Confirmed' : 'Pending'}
              </Text>
            </View>
            <View style={styles.confirmationRow}>
              <Text style={styles.detailLabel}>Other side</Text>
              <Text style={hasCounterpartManagerConfirmed ? styles.confirmedBadge : styles.pendingBadge}>
                {hasCounterpartManagerConfirmed ? 'Confirmed' : 'Pending'}
              </Text>
            </View>
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

          {!hasCurrentManagerConfirmed ? (
            <Card>
              <Text style={styles.heading}>Suggest different time</Text>
              <Text style={styles.meta}>Countering moves the session back into proposal exchange with your new slot.</Text>
              <CounterForm
                startsAt={counterStartsAt}
                endsAt={counterEndsAt}
                location={counterLocation}
                onStartsAtChange={setCounterStartsAt}
                onEndsAtChange={setCounterEndsAt}
                onLocationChange={setCounterLocation}
                onSubmit={submitCounterProposal}
                submitting={submitting}
              />
            </Card>
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
              <Pressable style={[styles.secondaryButton, submitting && styles.buttonDisabled]} onPress={() => void openCalendarFile()} disabled={submitting}>
                <Text style={styles.secondaryButtonText}>{submitting ? 'Preparing file...' : 'Add to calendar'}</Text>
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
 * Minimal counter-proposal form shared by the proposing and pending-approval states.
 */
function CounterForm({
  startsAt,
  endsAt,
  location,
  onStartsAtChange,
  onEndsAtChange,
  onLocationChange,
  onSubmit,
  submitting,
}: {
  startsAt: string;
  endsAt: string;
  location: string;
  onStartsAtChange: (value: string) => void;
  onEndsAtChange: (value: string) => void;
  onLocationChange: (value: string) => void;
  onSubmit: () => void;
  submitting: boolean;
}) {
  return (
    <View style={styles.counterForm}>
      <Text style={styles.detailLabel}>Counter start (ISO UTC)</Text>
      <TextInput
        style={styles.input}
        value={startsAt}
        onChangeText={onStartsAtChange}
        autoCapitalize="none"
        placeholder="2026-04-05T14:00:00Z"
        placeholderTextColor="#8e846f"
      />

      <Text style={styles.detailLabel}>Counter end (ISO UTC)</Text>
      <TextInput
        style={styles.input}
        value={endsAt}
        onChangeText={onEndsAtChange}
        autoCapitalize="none"
        placeholder="2026-04-05T15:30:00Z"
        placeholderTextColor="#8e846f"
      />

      <Text style={styles.detailLabel}>Location (optional)</Text>
      <TextInput
        style={styles.input}
        value={location}
        onChangeText={onLocationChange}
        placeholder="Field 3"
        placeholderTextColor="#8e846f"
      />

      <Pressable style={[styles.secondaryButton, submitting && styles.buttonDisabled]} onPress={onSubmit} disabled={submitting}>
        <Text style={styles.secondaryButtonText}>{submitting ? 'Sending...' : 'Send counter proposal'}</Text>
      </Pressable>
    </View>
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

/**
 * Builds a shareable invite payload that another device can paste into the join screen.
 *
 * The text stays readable for humans while remaining deterministic enough for the
 * join screen parser to extract the required fields automatically.
 *
 * @param initiatorBaseUrl Base URL of the instance that created the negotiation.
 * @param sessionId Negotiation session UUID.
 * @param inviteToken One-time join token for the responder.
 * @returns Multiline invite text suitable for share sheets, Messages, Mail, or Notes.
 */
function buildInviteShareText(
  initiatorBaseUrl: string,
  sessionId: string,
  inviteToken: string,
): string {
  const deepLink = `fieldiq://join?initiatorBaseUrl=${encodeURIComponent(initiatorBaseUrl)}&sessionId=${encodeURIComponent(sessionId)}&inviteToken=${encodeURIComponent(inviteToken)}`;

  return [
    'FieldIQ negotiation invite',
    `Initiator URL: ${initiatorBaseUrl}`,
    `Session ID: ${sessionId}`,
    `Invite Token: ${inviteToken}`,
    '',
    `Join link payload: ${deepLink}`,
  ].join('\n');
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
    lineHeight: 20,
  },
  slot: {
    fontSize: 18,
    fontWeight: '600',
    color: '#2b2d2f',
  },
  error: {
    color: '#b42318',
    lineHeight: 20,
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
  findingPulse: {
    width: 72,
    height: 72,
    borderRadius: 36,
    backgroundColor: '#d95d39',
    alignSelf: 'center',
    opacity: 0.22,
  },
  detailLabel: {
    fontSize: 13,
    fontWeight: '700',
    color: '#14281d',
    textTransform: 'uppercase',
    letterSpacing: 1,
  },
  detailValue: {
    color: '#2b2d2f',
    fontSize: 15,
  },
  inviteToken: {
    color: '#14281d',
    fontSize: 16,
    fontWeight: '700',
  },
  sharePayload: {
    marginTop: 12,
    color: '#6e665a',
    fontSize: 13,
    lineHeight: 19,
  },
  confirmationRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  confirmedBadge: {
    color: '#126a3a',
    fontWeight: '700',
  },
  pendingBadge: {
    color: '#9a6700',
    fontWeight: '700',
  },
  counterForm: {
    gap: 10,
  },
  input: {
    borderWidth: 1,
    borderColor: '#c8c1b1',
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 16,
    backgroundColor: '#f9f4eb',
  },
});
