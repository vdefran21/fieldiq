import { useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput } from 'react-native';
import { router } from 'expo-router';
import { Card } from '../../components/Card';
import { Screen } from '../../components/Screen';
import { usePrimaryTeam } from '../../hooks/usePrimaryTeam';
import { api } from '../../services/api';

/**
 * Minimal negotiation-initiation flow for Phase 1 mobile validation.
 *
 * The goal is not rich scheduling authoring. The goal is letting a manager start
 * real negotiation work from mobile and land directly in the negotiation screen.
 */
export default function StartNegotiationScreen() {
  const { primaryTeam: team } = usePrimaryTeam();
  const [dateRangeStart, setDateRangeStart] = useState(defaultDateOffset(3));
  const [dateRangeEnd, setDateRangeEnd] = useState(defaultDateOffset(17));
  const [durationMinutes, setDurationMinutes] = useState('90');
  const [preferredDays, setPreferredDays] = useState('6');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const subtitle = useMemo(() => {
    if (!team) {
      return 'Create a team first so FieldIQ can start a negotiation on the right schedule.';
    }
    return `Create a negotiation for ${team.name}, then jump straight into the live session.`;
  }, [team]);

  async function startNegotiation() {
    if (!team) {
      setError('Create a team before starting a negotiation.');
      return;
    }

    setSubmitting(true);
    setError(null);

    try {
      const session = await api.negotiations.initiate({
        teamId: team.id,
        dateRangeStart: dateRangeStart.trim(),
        dateRangeEnd: dateRangeEnd.trim(),
        durationMinutes: Number.parseInt(durationMinutes, 10) || 90,
        preferredDays: preferredDays
          .split(',')
          .map((value) => Number.parseInt(value.trim(), 10))
          .filter((value) => Number.isInteger(value) && value >= 0 && value <= 6),
      });
      router.replace(`/(app)/negotiate/${session.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to start the negotiation.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Screen title="Start negotiation" subtitle={subtitle}>
      <Card>
        <Text style={styles.label}>Earliest date</Text>
        <TextInput
          style={styles.input}
          value={dateRangeStart}
          onChangeText={setDateRangeStart}
          autoCapitalize="none"
          placeholder="2026-04-01"
          placeholderTextColor="#8e846f"
        />

        <Text style={styles.label}>Latest date</Text>
        <TextInput
          style={styles.input}
          value={dateRangeEnd}
          onChangeText={setDateRangeEnd}
          autoCapitalize="none"
          placeholder="2026-04-30"
          placeholderTextColor="#8e846f"
        />

        <Text style={styles.label}>Duration (minutes)</Text>
        <TextInput
          style={styles.input}
          value={durationMinutes}
          onChangeText={setDurationMinutes}
          keyboardType="numeric"
          placeholder="90"
          placeholderTextColor="#8e846f"
        />

        <Text style={styles.label}>Preferred days (0-6, comma separated)</Text>
        <TextInput
          style={styles.input}
          value={preferredDays}
          onChangeText={setPreferredDays}
          autoCapitalize="none"
          placeholder="6"
          placeholderTextColor="#8e846f"
        />

        {error ? <Text style={styles.error}>{error}</Text> : null}

        <Pressable style={[styles.primaryButton, submitting && styles.disabledButton]} onPress={startNegotiation} disabled={submitting}>
          <Text style={styles.primaryButtonText}>{submitting ? 'Starting...' : 'Start negotiation'}</Text>
        </Pressable>

        <Pressable style={styles.secondaryButton} onPress={() => router.back()} disabled={submitting}>
          <Text style={styles.secondaryButtonText}>Back</Text>
        </Pressable>
      </Card>
    </Screen>
  );
}

function defaultDateOffset(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return date.toISOString().slice(0, 10);
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
