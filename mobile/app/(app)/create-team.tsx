import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput } from 'react-native';
import { router } from 'expo-router';
import { Card } from '../../components/Card';
import { Screen } from '../../components/Screen';
import { api } from '../../services/api';

/**
 * Minimal team-creation flow for managers who authenticate into an empty account.
 *
 * Phase 1 only needs enough onboarding to create the first team so schedule and
 * roster screens can load real data without requiring separate backend setup.
 */
export default function CreateTeamScreen() {
  const [name, setName] = useState('');
  const [sport, setSport] = useState('soccer');
  const [ageGroup, setAgeGroup] = useState('');
  const [season, setSeason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function createTeam() {
    const trimmedName = name.trim();
    if (!trimmedName) {
      setError('Team name is required.');
      return;
    }

    setSaving(true);
    setError(null);

    try {
      await api.teams.create({
        name: trimmedName,
        sport: sport.trim() || undefined,
        ageGroup: ageGroup.trim() || undefined,
        season: season.trim() || undefined,
      });
      router.replace('/(app)');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to create team.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Screen
      title="Create team"
      subtitle="Use a lightweight first-team setup so the manager dashboard can load real schedule and roster data."
    >
      <Card>
        <Text style={styles.label}>Team name</Text>
        <TextInput
          style={styles.input}
          value={name}
          onChangeText={setName}
          placeholder="Bethesda Fire U12 Boys"
          placeholderTextColor="#8e846f"
        />

        <Text style={styles.label}>Sport</Text>
        <TextInput
          style={styles.input}
          value={sport}
          onChangeText={setSport}
          placeholder="soccer"
          placeholderTextColor="#8e846f"
        />

        <Text style={styles.label}>Age group</Text>
        <TextInput
          style={styles.input}
          value={ageGroup}
          onChangeText={setAgeGroup}
          placeholder="U12"
          placeholderTextColor="#8e846f"
        />

        <Text style={styles.label}>Season</Text>
        <TextInput
          style={styles.input}
          value={season}
          onChangeText={setSeason}
          placeholder="Spring2026"
          placeholderTextColor="#8e846f"
        />

        {error ? <Text style={styles.error}>{error}</Text> : null}

        <Pressable style={[styles.primaryButton, saving && styles.disabledButton]} onPress={createTeam} disabled={saving}>
          <Text style={styles.primaryButtonText}>{saving ? 'Creating team...' : 'Create team'}</Text>
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
