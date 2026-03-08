import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text } from 'react-native';
import { router } from 'expo-router';
import type { EventDto } from '../../../shared/types';
import { Card } from '../../components/Card';
import { Screen } from '../../components/Screen';
import { api } from '../../services/api';
import { usePrimaryTeam } from '../../hooks/usePrimaryTeam';

/**
 * Schedule feed showing the user's first team and its upcoming events.
 */
export default function ScheduleScreen() {
  const { primaryTeam: team, loading: teamLoading, error: teamError, reload: reloadTeam } = usePrimaryTeam();
  const [events, setEvents] = useState<EventDto[]>([]);
  const [eventsLoading, setEventsLoading] = useState(false);
  const [eventsError, setEventsError] = useState<string | null>(null);
  const [eventsReloadKey, setEventsReloadKey] = useState(0);
  const teamId = team?.id ?? null;

  useEffect(() => {
    async function loadEvents() {
      if (!teamId) {
        setEvents([]);
        setEventsError(null);
        return;
      }

      setEventsLoading(true);
      setEventsError(null);

      try {
        setEvents(await api.teams.events(teamId));
      } catch (err) {
        setEventsError(err instanceof Error ? err.message : 'Unable to load events.');
      } finally {
        setEventsLoading(false);
      }
    }

    void loadEvents();
  }, [teamId, eventsReloadKey]);

  return (
    <Screen
      title="Schedule feed"
      subtitle="Sprint 5 focuses on the manager dashboard basics: teams, upcoming events, and a path into negotiation review."
    >
      <Card>
        <Text style={styles.sectionTitle}>{team?.name ?? 'No team yet'}</Text>
        {teamLoading ? <ActivityIndicator color="#d95d39" /> : null}
        <Text style={styles.muted}>
          {team ? `${team.sport} · ${team.ageGroup ?? 'Unspecified age group'}` : 'Create your first team to unlock schedule and negotiation flows.'}
        </Text>
        {teamError ? <Text style={styles.error}>{teamError}</Text> : null}
        {!team && !teamLoading ? (
          <Pressable style={styles.primaryButton} onPress={() => router.push('./create-team')}>
            <Text style={styles.primaryButtonText}>Create your first team</Text>
          </Pressable>
        ) : null}
        {teamError ? (
          <Pressable style={styles.secondaryButton} onPress={() => void reloadTeam()}>
            <Text style={styles.secondaryButtonText}>Retry team load</Text>
          </Pressable>
        ) : null}
      </Card>

      {team && eventsLoading ? (
        <Card>
          <ActivityIndicator color="#d95d39" />
          <Text style={styles.muted}>Loading upcoming events...</Text>
        </Card>
      ) : null}

      {team && eventsError ? (
        <Card>
          <Text style={styles.sectionTitle}>Events unavailable</Text>
          <Text style={styles.error}>{eventsError}</Text>
          <Pressable style={styles.secondaryButton} onPress={() => setEventsReloadKey((value) => value + 1)}>
            <Text style={styles.secondaryButtonText}>Retry events</Text>
          </Pressable>
        </Card>
      ) : null}

      {team && !eventsLoading && !eventsError && events.length === 0 ? (
        <Card>
          <Text style={styles.sectionTitle}>No events scheduled yet</Text>
          <Text style={styles.muted}>Once you add practices or negotiate a game, upcoming events will appear here.</Text>
        </Card>
      ) : null}

      {events.map((event) => (
        <Card key={event.id}>
          <Text style={styles.eventTitle}>{event.title ?? event.eventType}</Text>
          <Text style={styles.muted}>{event.startsAt ?? 'Draft event'}</Text>
          <Text style={styles.muted}>{event.location ?? 'Location TBD'}</Text>
          {event.negotiationId ? (
            <Pressable style={styles.linkButton} onPress={() => router.push(`/(app)/negotiate/${event.negotiationId}`)}>
              <Text style={styles.linkText}>Open negotiation</Text>
            </Pressable>
          ) : null}
        </Card>
      ))}
    </Screen>
  );
}

const styles = StyleSheet.create({
  sectionTitle: {
    fontSize: 22,
    fontWeight: '700',
    color: '#14281d',
  },
  eventTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#2b2d2f',
  },
  muted: {
    color: '#6e665a',
    fontSize: 14,
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
    paddingVertical: 12,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#c8c1b1',
    backgroundColor: '#fffdf8',
  },
  secondaryButtonText: {
    color: '#2b2d2f',
    fontWeight: '600',
  },
  linkButton: {
    paddingTop: 8,
  },
  linkText: {
    color: '#d95d39',
    fontWeight: '700',
  },
});
