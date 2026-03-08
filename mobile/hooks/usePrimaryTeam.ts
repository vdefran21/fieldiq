import { useEffect, useState } from 'react';
import { useNavigation } from 'expo-router';
import type { TeamDto } from '../../shared/types';
import { api } from '../services/api';

/**
 * Shared team bootstrap state for authenticated mobile screens.
 *
 * Sprint 5 uses the first accessible team as the working context for schedule,
 * roster, and negotiation affordances. This hook centralizes the fetch, loading,
 * error, and focus-refresh behavior so those screens stay in sync.
 *
 * @returns Team list, first team, loading/error flags, and a manual reload helper.
 */
export function usePrimaryTeam() {
  const navigation = useNavigation();
  const [teams, setTeams] = useState<TeamDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function reload() {
    setLoading(true);
    setError(null);

    try {
      const nextTeams = await api.teams.list();
      setTeams(nextTeams);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to load teams.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
  }, []);

  useEffect(() => {
    const unsubscribe = navigation.addListener('focus', () => {
      void reload();
    });

    return unsubscribe;
  }, [navigation]);

  return {
    teams,
    primaryTeam: teams[0] ?? null,
    loading,
    error,
    reload,
  };
}
