import { useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput } from 'react-native';
import { router } from 'expo-router';
import { Card } from '../../components/Card';
import { Screen } from '../../components/Screen';
import { usePrimaryTeam } from '../../hooks/usePrimaryTeam';
import { API_BASE, api } from '../../services/api';

/**
 * Cross-instance join flow for a manager responding to an invite from another FieldIQ instance.
 *
 * The join request is sent to the initiating instance, while the resulting negotiation is
 * viewed on the manager's local instance after the shadow session is bootstrapped.
 */
export default function JoinNegotiationScreen() {
  const { primaryTeam: team } = usePrimaryTeam();
  const [sessionId, setSessionId] = useState('');
  const [inviteToken, setInviteToken] = useState('');
  const [initiatorBaseUrl, setInitiatorBaseUrl] = useState('');
  const [inviteDetailsText, setInviteDetailsText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [joining, setJoining] = useState(false);

  const subtitle = useMemo(() => {
    if (!team) {
      return 'Create a team first so FieldIQ knows which local team is joining the invitation.';
    }
    return `Use the share details from the initiating manager to join this negotiation for ${team.name}.`;
  }, [team]);

  async function joinNegotiation() {
    if (!team) {
      setError('Create a team before joining a negotiation.');
      return;
    }
    if (!initiatorBaseUrl.trim() || !sessionId.trim() || !inviteToken.trim()) {
      setError('Enter the initiator URL, session ID, and invite token.');
      return;
    }

    setJoining(true);
    setError(null);

    try {
      await api.negotiations.join(
        sessionId.trim(),
        {
          inviteToken: inviteToken.trim(),
          responderTeamId: team.id,
          responderInstance: API_BASE,
        },
        initiatorBaseUrl.trim().replace(/\/$/, ''),
      );
      router.replace(`/(app)/negotiate/${sessionId.trim()}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to join the negotiation.');
    } finally {
      setJoining(false);
    }
  }

  function fillInviteDetails() {
    const parsed = parseInviteDetails(inviteDetailsText);
    if (!parsed) {
      setError('Paste the shared invite text from the initiating manager, then try again.');
      return;
    }

    setInitiatorBaseUrl(parsed.initiatorBaseUrl);
    setSessionId(parsed.sessionId);
    setInviteToken(parsed.inviteToken);
    setError(null);
  }

  return (
    <Screen title="Join negotiation" subtitle={subtitle}>
      <Card>
        <Text style={styles.label}>Paste shared invite details</Text>
        <TextInput
          style={[styles.input, styles.multilineInput]}
          value={inviteDetailsText}
          onChangeText={setInviteDetailsText}
          autoCapitalize="none"
          multiline
          placeholder={'FieldIQ negotiation invite\nInitiator URL: http://192.168.1.42:8080\nSession ID: ...\nInvite Token: ...'}
          placeholderTextColor="#8e846f"
        />

        <Pressable style={styles.secondaryButton} onPress={fillInviteDetails}>
          <Text style={styles.secondaryButtonText}>Fill from pasted invite</Text>
        </Pressable>

        <Text style={styles.label}>Initiator instance URL</Text>
        <TextInput
          style={styles.input}
          value={initiatorBaseUrl}
          onChangeText={setInitiatorBaseUrl}
          autoCapitalize="none"
          placeholder="http://localhost:8080"
          placeholderTextColor="#8e846f"
        />

        <Text style={styles.label}>Session ID</Text>
        <TextInput
          style={styles.input}
          value={sessionId}
          onChangeText={setSessionId}
          autoCapitalize="none"
          placeholder="Session UUID"
          placeholderTextColor="#8e846f"
        />

        <Text style={styles.label}>Invite token</Text>
        <TextInput
          style={styles.input}
          value={inviteToken}
          onChangeText={setInviteToken}
          autoCapitalize="none"
          placeholder="Invite token"
          placeholderTextColor="#8e846f"
        />

        <Text style={styles.meta}>Local responder instance: {API_BASE}</Text>

        {error ? <Text style={styles.error}>{error}</Text> : null}

        <Pressable style={[styles.primaryButton, joining && styles.disabledButton]} onPress={joinNegotiation} disabled={joining}>
          <Text style={styles.primaryButtonText}>{joining ? 'Joining...' : 'Join negotiation'}</Text>
        </Pressable>

        <Pressable style={styles.secondaryButton} onPress={() => router.back()} disabled={joining}>
          <Text style={styles.secondaryButtonText}>Back</Text>
        </Pressable>
      </Card>
    </Screen>
  );
}

const styles = StyleSheet.create({
  label: {
    fontSize: 14,
    fontWeight: '600',
    color: '#2b2d2f',
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
  multilineInput: {
    minHeight: 110,
    textAlignVertical: 'top',
  },
  meta: {
    color: '#6e665a',
    lineHeight: 20,
  },
  error: {
    color: '#b42318',
    fontSize: 14,
  },
  primaryButton: {
    backgroundColor: '#14281d',
    borderRadius: 14,
    paddingVertical: 14,
    alignItems: 'center',
  },
  primaryButtonText: {
    color: '#f6f4ee',
    fontWeight: '700',
    fontSize: 15,
  },
  secondaryButton: {
    borderRadius: 14,
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
  disabledButton: {
    opacity: 0.6,
  },
});

/**
 * Negotiation invite fields extracted from a shared invite payload.
 */
interface ParsedInviteDetails {
  /** Base URL of the instance that created the negotiation. */
  initiatorBaseUrl: string;
  /** Negotiation session UUID to join. */
  sessionId: string;
  /** One-time invite token authorizing the responder to join. */
  inviteToken: string;
}

/**
 * Parses shared invite text from the negotiation screen into join form fields.
 *
 * Supports both the human-readable multiline share format used by FieldIQ and
 * a raw `fieldiq://join?...` deep-link style payload for future compatibility.
 *
 * @param rawInviteText Text pasted by the user from Messages, Notes, Mail, or another share target.
 * @returns Parsed invite details, or `null` when the text does not contain a usable invite.
 */
function parseInviteDetails(rawInviteText: string): ParsedInviteDetails | null {
  const trimmed = rawInviteText.trim();
  if (!trimmed) {
    return null;
  }

  const deepLinkMatch = trimmed.match(/fieldiq:\/\/join\?(.+)/i);
  if (deepLinkMatch) {
    const params = new URLSearchParams(deepLinkMatch[1]);
    const initiatorBaseUrl = params.get('initiatorBaseUrl')?.trim();
    const sessionId = params.get('sessionId')?.trim();
    const inviteToken = params.get('inviteToken')?.trim();
    if (initiatorBaseUrl && sessionId && inviteToken) {
      return { initiatorBaseUrl, sessionId, inviteToken };
    }
  }

  const initiatorBaseUrl = matchInviteLine(trimmed, 'Initiator URL');
  const sessionId = matchInviteLine(trimmed, 'Session ID');
  const inviteToken = matchInviteLine(trimmed, 'Invite Token');

  if (!initiatorBaseUrl || !sessionId || !inviteToken) {
    return null;
  }

  return { initiatorBaseUrl, sessionId, inviteToken };
}

/**
 * Extracts a labeled line value from the shared invite text.
 *
 * @param inviteText Shared invite payload.
 * @param label Human-readable label expected in the shared message.
 * @returns Trimmed line value, or `null` when the label is missing.
 */
function matchInviteLine(inviteText: string, label: string): string | null {
  const escapedLabel = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = inviteText.match(new RegExp(`${escapedLabel}:\\s*(.+)`, 'i'));
  return match?.[1]?.trim() ?? null;
}
