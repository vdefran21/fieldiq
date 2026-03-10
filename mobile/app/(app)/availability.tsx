import { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { router } from 'expo-router';
import type { AvailabilityWindowDto, CreateAvailabilityWindowRequest } from '../../../shared/types';
import { Card } from '../../components/Card';
import { Screen } from '../../components/Screen';
import { usePrimaryTeam } from '../../hooks/usePrimaryTeam';
import { api } from '../../services/api';

const SUGGESTED_WINDOWS = [
  { dayOfWeek: 6, startTime: '09:00', endTime: '12:00' },
  { dayOfWeek: 2, startTime: '18:00', endTime: '20:00' },
];

const DAY_OPTIONS = [
  { value: 0, label: 'Sunday' },
  { value: 1, label: 'Monday' },
  { value: 2, label: 'Tuesday' },
  { value: 3, label: 'Wednesday' },
  { value: 4, label: 'Thursday' },
  { value: 5, label: 'Friday' },
  { value: 6, label: 'Saturday' },
];

/**
 * Minimal recurring-availability setup screen for the logged-in manager.
 *
 * Phase 1 keeps scope intentionally narrow: the manager declares their own baseline
 * recurring availability for the current team so scheduling and negotiation have
 * real manual input data to work with.
 */
export default function AvailabilityScreen() {
  const { primaryTeam: team } = usePrimaryTeam();
  const [windows, setWindows] = useState<AvailabilityWindowDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [selectedDay, setSelectedDay] = useState<number>(6);
  const [startTime, setStartTime] = useState('09:00');
  const [endTime, setEndTime] = useState('12:00');
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    async function loadWindows() {
      if (!team) {
        setWindows([]);
        setError(null);
        return;
      }

      setLoading(true);
      setError(null);

      try {
        const ownWindows = await api.availability.listMine();
        setWindows(
          ownWindows
            .filter((window) => window.teamId === team.id && window.windowType === 'available' && window.dayOfWeek !== undefined)
            .sort((left, right) => {
              if ((left.dayOfWeek ?? 0) !== (right.dayOfWeek ?? 0)) {
                return (left.dayOfWeek ?? 0) - (right.dayOfWeek ?? 0);
              }
              return left.startTime.localeCompare(right.startTime);
            }),
        );
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Unable to load your availability.');
      } finally {
        setLoading(false);
      }
    }

    void loadWindows();
  }, [team, reloadKey]);

  const subtitle = useMemo(() => {
    if (!team) {
      return 'Create a team first so FieldIQ knows which baseline schedule this availability belongs to.';
    }
    return `Set your recurring weekly availability for ${team.name}. Google Calendar can later subtract conflicts, but this baseline is what makes the scheduler useful.`;
  }, [team]);

  async function createWindow(payload: CreateAvailabilityWindowRequest) {
    const window = await api.availability.create(payload);
    setWindows((current) =>
      [...current, window].sort((left, right) => {
        if ((left.dayOfWeek ?? 0) !== (right.dayOfWeek ?? 0)) {
          return (left.dayOfWeek ?? 0) - (right.dayOfWeek ?? 0);
        }
        return left.startTime.localeCompare(right.startTime);
      }),
    );
  }

  async function submitAvailability() {
    if (!team) {
      setError('Create a team before setting availability.');
      return;
    }

    const validationError = validateRecurringWindow(startTime, endTime);
    if (validationError) {
      setError(validationError);
      return;
    }

    setSubmitting(true);
    setError(null);

    try {
      await createWindow({
        teamId: team.id,
        dayOfWeek: selectedDay,
        startTime: startTime.trim(),
        endTime: endTime.trim(),
        windowType: 'available',
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to create the availability window.');
    } finally {
      setSubmitting(false);
    }
  }

  async function seedSuggestedAvailability() {
    if (!team) {
      setError('Create a team before using suggested availability.');
      return;
    }

    setSubmitting(true);
    setError(null);

    try {
      for (const window of SUGGESTED_WINDOWS) {
        await createWindow({
          teamId: team.id,
          dayOfWeek: window.dayOfWeek,
          startTime: window.startTime,
          endTime: window.endTime,
          windowType: 'available',
        });
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to add the suggested availability.');
      setReloadKey((value) => value + 1);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Screen title="Availability" subtitle={subtitle}>
      <Card>
        <Text style={styles.heading}>Recurring weekly availability</Text>
        <Text style={styles.meta}>This creates baseline manual availability windows for your current team.</Text>

        <Text style={styles.label}>Day of week</Text>
        <View style={styles.dayGrid}>
          {DAY_OPTIONS.map((day) => (
            <Pressable
              key={day.value}
              style={[styles.dayChip, selectedDay === day.value && styles.dayChipSelected]}
              onPress={() => setSelectedDay(day.value)}
            >
              <Text style={[styles.dayChipText, selectedDay === day.value && styles.dayChipTextSelected]}>{day.label}</Text>
            </Pressable>
          ))}
        </View>

        <Text style={styles.label}>Start time (HH:mm)</Text>
        <TextInput
          style={styles.input}
          value={startTime}
          onChangeText={setStartTime}
          autoCapitalize="none"
          placeholder="09:00"
          placeholderTextColor="#8e846f"
        />

        <Text style={styles.label}>End time (HH:mm)</Text>
        <TextInput
          style={styles.input}
          value={endTime}
          onChangeText={setEndTime}
          autoCapitalize="none"
          placeholder="12:00"
          placeholderTextColor="#8e846f"
        />

        {error ? <Text style={styles.error}>{error}</Text> : null}

        <Pressable style={[styles.primaryButton, submitting && styles.buttonDisabled]} onPress={() => void submitAvailability()} disabled={submitting}>
          <Text style={styles.primaryButtonText}>{submitting ? 'Saving...' : 'Add recurring availability'}</Text>
        </Pressable>

        {windows.length === 0 ? (
          <Pressable style={[styles.secondaryButton, submitting && styles.buttonDisabled]} onPress={() => void seedSuggestedAvailability()} disabled={submitting}>
            <Text style={styles.secondaryButtonText}>Use suggested availability</Text>
          </Pressable>
        ) : null}

        <Pressable style={styles.tertiaryButton} onPress={() => router.back()} disabled={submitting}>
          <Text style={styles.tertiaryButtonText}>Back</Text>
        </Pressable>
      </Card>

      {loading ? (
        <Card>
          <ActivityIndicator color="#d95d39" />
          <Text style={styles.meta}>Loading your availability...</Text>
        </Card>
      ) : null}

      {!loading && windows.length === 0 ? (
        <Card>
          <Text style={styles.heading}>No availability yet</Text>
          <Text style={styles.meta}>Add at least one recurring window so scheduling suggestions and negotiations have real baseline data.</Text>
        </Card>
      ) : null}

      {windows.map((window) => (
        <Card key={window.id}>
          <Text style={styles.windowTitle}>{DAY_OPTIONS.find((day) => day.value === window.dayOfWeek)?.label ?? 'Recurring'}</Text>
          <Text style={styles.meta}>{window.startTime} - {window.endTime}</Text>
          <Text style={styles.meta}>Source: {window.source}</Text>
        </Card>
      ))}
    </Screen>
  );
}

/**
 * Validates a recurring availability time range.
 *
 * @param startTime User-supplied start time in HH:mm format.
 * @param endTime User-supplied end time in HH:mm format.
 * @returns Validation error message, or `null` when the time range is valid.
 */
function validateRecurringWindow(startTime: string, endTime: string): string | null {
  const timePattern = /^([01]\d|2[0-3]):([0-5]\d)$/;
  if (!timePattern.test(startTime.trim()) || !timePattern.test(endTime.trim())) {
    return 'Enter start and end times in HH:mm format.';
  }

  if (startTime.trim() >= endTime.trim()) {
    return 'End time must be after start time.';
  }

  return null;
}

const styles = StyleSheet.create({
  heading: {
    fontSize: 22,
    fontWeight: '700',
    color: '#14281d',
  },
  meta: {
    color: '#6e665a',
    lineHeight: 20,
  },
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
  dayGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
  },
  dayChip: {
    borderRadius: 999,
    borderWidth: 1,
    borderColor: '#c8c1b1',
    backgroundColor: '#fffdf8',
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  dayChipSelected: {
    backgroundColor: '#14281d',
    borderColor: '#14281d',
  },
  dayChipText: {
    color: '#2b2d2f',
    fontWeight: '600',
  },
  dayChipTextSelected: {
    color: '#f6f4ee',
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
    paddingVertical: 12,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#d95d39',
    backgroundColor: '#fff1eb',
  },
  secondaryButtonText: {
    color: '#d95d39',
    fontWeight: '700',
  },
  tertiaryButton: {
    borderRadius: 14,
    paddingVertical: 12,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#c8c1b1',
    backgroundColor: '#fffdf8',
  },
  tertiaryButtonText: {
    color: '#2b2d2f',
    fontWeight: '600',
  },
  buttonDisabled: {
    opacity: 0.6,
  },
  error: {
    color: '#b42318',
    fontSize: 14,
    lineHeight: 20,
  },
  windowTitle: {
    fontSize: 17,
    fontWeight: '600',
    color: '#2b2d2f',
  },
});
