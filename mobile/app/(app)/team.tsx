import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text } from 'react-native';
import { router } from 'expo-router';
import type { TeamMemberDto } from '../../../shared/types';
import { Card } from '../../components/Card';
import { Screen } from '../../components/Screen';
import { api } from '../../services/api';
import { usePrimaryTeam } from '../../hooks/usePrimaryTeam';

/**
 * Team roster screen for the first active team.
 */
export default function TeamScreen() {
  const { primaryTeam: team, loading: teamLoading, error: teamError, reload: reloadTeam } = usePrimaryTeam();
  const [members, setMembers] = useState<TeamMemberDto[]>([]);
  const [membersLoading, setMembersLoading] = useState(false);
  const [membersError, setMembersError] = useState<string | null>(null);
  const [membersReloadKey, setMembersReloadKey] = useState(0);
  const teamId = team?.id ?? null;

  useEffect(() => {
    async function loadMembers() {
      if (!teamId) {
        setMembers([]);
        setMembersError(null);
        return;
      }

      setMembersLoading(true);
      setMembersError(null);

      try {
        setMembers(await api.teams.members(teamId));
      } catch (err) {
        setMembersError(err instanceof Error ? err.message : 'Unable to load roster.');
      } finally {
        setMembersLoading(false);
      }
    }

    void loadMembers();
  }, [teamId, membersReloadKey]);

  return (
    <Screen title="Roster" subtitle="Managers need a fast team snapshot: who is on the roster and what role they hold.">
      <Card>
        <Text style={styles.heading}>{team?.name ?? 'No team selected'}</Text>
        {teamLoading ? <ActivityIndicator color="#d95d39" /> : null}
        <Text style={styles.meta}>{team ? `${members.length} active members` : 'Create a team first so FieldIQ can load a roster.'}</Text>
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
        {team ? (
          <Pressable style={styles.secondaryButton} onPress={() => router.push('./availability')}>
            <Text style={styles.secondaryButtonText}>Manage availability</Text>
          </Pressable>
        ) : null}
      </Card>

      {team && membersLoading ? (
        <Card>
          <ActivityIndicator color="#d95d39" />
          <Text style={styles.meta}>Loading roster...</Text>
        </Card>
      ) : null}

      {team && membersError ? (
        <Card>
          <Text style={styles.heading}>Roster unavailable</Text>
          <Text style={styles.error}>{membersError}</Text>
          <Pressable style={styles.secondaryButton} onPress={() => setMembersReloadKey((value) => value + 1)}>
            <Text style={styles.secondaryButtonText}>Retry roster</Text>
          </Pressable>
        </Card>
      ) : null}

      {team && !membersLoading && !membersError && members.length === 0 ? (
        <Card>
          <Text style={styles.heading}>Roster is empty</Text>
          <Text style={styles.meta}>This team exists, but no active members are visible yet.</Text>
        </Card>
      ) : null}

      {members.map((member) => (
        <Card key={member.id}>
          <Text style={styles.memberName}>{member.user?.displayName ?? member.playerName ?? member.userId}</Text>
          <Text style={styles.meta}>{member.role}</Text>
        </Card>
      ))}
    </Screen>
  );
}

const styles = StyleSheet.create({
  heading: {
    fontSize: 22,
    fontWeight: '700',
    color: '#14281d',
  },
  memberName: {
    fontSize: 17,
    fontWeight: '600',
    color: '#2b2d2f',
  },
  meta: {
    color: '#6e665a',
  },
  error: {
    color: '#b42318',
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
});
