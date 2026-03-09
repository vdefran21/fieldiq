import { useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput } from 'react-native';
import { router } from 'expo-router';
import type { CreateEventRequest } from '../../../shared/types';
import { Card } from '../../components/Card';
import { Screen } from '../../components/Screen';
import { usePrimaryTeam } from '../../hooks/usePrimaryTeam';
import { api } from '../../services/api';

/**
 * Lightweight event-creation flow used from the schedule empty state.
 *
 * Phase 1 only needs a fast path to place a draft or scheduled event on the team's
 * calendar so managers can leave the initial empty state without needing desktop setup.
 */
export default function CreateEventScreen() {
  const { primaryTeam: team } = usePrimaryTeam();
  const [eventType, setEventType] = useState<CreateEventRequest['eventType']>('game');
  const [title, setTitle] = useState('');
  const [location, setLocation] = useState('');
  const [startsAt, setStartsAt] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const subtitle = useMemo(() => {
    if (!team) {
      return 'Create a team first so FieldIQ knows where this event belongs.';
    }
    return `Create a draft or scheduled event for ${team.name}. Leave the time blank to save a draft.`;
  }, [team]);

  async function createEvent() {
    if (!team) {
      setError('Create a team before adding events.');
      return;
    }

    setSaving(true);
    setError(null);

    try {
      await api.events.create(team.id, {
        eventType,
        title: title.trim() || undefined,
        location: location.trim() || undefined,
        startsAt: startsAt.trim() || undefined,
        endsAt: endsAt.trim() || undefined,
      });
      router.replace('/(app)');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to create the event.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Screen title="Create event" subtitle={subtitle}>
      <Card>
        <Text style={styles.label}>Event type</Text>
        <TextInput
          style={styles.input}
          value={eventType}
          onChangeText={(value) => setEventType((value as CreateEventRequest['eventType']) || 'game')}
          autoCapitalize="none"
          placeholder="game"
          placeholderTextColor="#8e846f"
        />

        <Text style={styles.label}>Title</Text>
        <TextInput
          style={styles.input}
          value={title}
          onChangeText={setTitle}
          placeholder="vs Arlington United"
          placeholderTextColor="#8e846f"
        />

        <Text style={styles.label}>Location</Text>
        <TextInput
          style={styles.input}
          value={location}
          onChangeText={setLocation}
          placeholder="Field 3"
          placeholderTextColor="#8e846f"
        />

        <Text style={styles.label}>Starts at (optional ISO UTC)</Text>
        <TextInput
          style={styles.input}
          value={startsAt}
          onChangeText={setStartsAt}
          autoCapitalize="none"
          placeholder="2026-04-05T14:00:00Z"
          placeholderTextColor="#8e846f"
        />

        <Text style={styles.label}>Ends at (optional ISO UTC)</Text>
        <TextInput
          style={styles.input}
          value={endsAt}
          onChangeText={setEndsAt}
          autoCapitalize="none"
          placeholder="2026-04-05T15:30:00Z"
          placeholderTextColor="#8e846f"
        />

        {error ? <Text style={styles.error}>{error}</Text> : null}

        <Pressable style={[styles.primaryButton, saving && styles.disabledButton]} onPress={createEvent} disabled={saving}>
          <Text style={styles.primaryButtonText}>{saving ? 'Saving event...' : 'Create event'}</Text>
        </Pressable>

        <Pressable style={styles.secondaryButton} onPress={() => router.back()} disabled={saving}>
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
  error: {
    color: '#b42318',
    fontSize: 14,
  },
  primaryButton: {
    backgroundColor: '#d95d39',
    borderRadius: 14,
    paddingVertical: 14,
    alignItems: 'center',
  },
  primaryButtonText: {
    color: '#fffaf0',
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
