import { useEffect, useState } from 'react';
import { ActivityIndicator, Linking, Pressable, StyleSheet, Text } from 'react-native';
import { Card } from '../../components/Card';
import { Screen } from '../../components/Screen';
import { api } from '../../services/api';
import { isPushConfigured, registerForPushNotifications } from '../../services/notifications';

/**
 * Settings screen for profile and device registration.
 */
export default function SettingsScreen() {
  const [profile, setProfile] = useState<{ displayName?: string; phone?: string; email?: string } | null>(null);
  const [profileLoading, setProfileLoading] = useState(true);
  const [profileError, setProfileError] = useState<string | null>(null);
  const [calendarState, setCalendarState] = useState<{
    connected: boolean;
    provider?: string;
    lastSyncedAt?: string;
    expiresAt?: string;
  } | null>(null);
  const [calendarLoading, setCalendarLoading] = useState(true);
  const [calendarError, setCalendarError] = useState<string | null>(null);
  const [calendarSubmitting, setCalendarSubmitting] = useState(false);
  const [deviceState, setDeviceState] = useState(
    isPushConfigured() ? 'Push token not registered' : 'Push not configured for this Expo build',
  );
  const [registeringDevice, setRegisteringDevice] = useState(false);

  useEffect(() => {
    void loadProfile();
    void loadCalendar();
  }, []);

  async function loadProfile() {
    setProfileLoading(true);
    setProfileError(null);

    try {
      setProfile(await api.user.me());
    } catch (err) {
      setProfileError(err instanceof Error ? err.message : 'Unable to load profile.');
    } finally {
      setProfileLoading(false);
    }
  }

  async function registerDevice() {
    setRegisteringDevice(true);

    try {
      const result = await registerForPushNotifications();
      if (result.status !== 'registered') {
        setDeviceState(result.reason);
        return;
      }

      await api.user.registerDevice(result.token);
      setDeviceState('Push token registered with backend');
    } catch (err) {
      setDeviceState(err instanceof Error ? err.message : 'Unable to register this device.');
    } finally {
      setRegisteringDevice(false);
    }
  }

  async function loadCalendar() {
    setCalendarLoading(true);
    setCalendarError(null);

    try {
      setCalendarState(await api.calendar.status());
    } catch (err) {
      setCalendarError(err instanceof Error ? err.message : 'Unable to load calendar status.');
    } finally {
      setCalendarLoading(false);
    }
  }

  async function connectCalendar() {
    setCalendarSubmitting(true);
    setCalendarError(null);

    try {
      const response = await api.calendar.authorizeUrl();
      await Linking.openURL(response.authorizeUrl);
    } catch (err) {
      setCalendarError(err instanceof Error ? err.message : 'Unable to start Google Calendar connect.');
    } finally {
      setCalendarSubmitting(false);
    }
  }

  async function disconnectCalendar() {
    setCalendarSubmitting(true);
    setCalendarError(null);

    try {
      await api.calendar.disconnect();
      await loadCalendar();
    } catch (err) {
      setCalendarError(err instanceof Error ? err.message : 'Unable to disconnect Google Calendar.');
    } finally {
      setCalendarSubmitting(false);
    }
  }

  return (
    <Screen title="Settings" subtitle="Manage profile, device registration, and the browser-based Google Calendar handoff from one place.">
      <Card>
        <Text style={styles.heading}>{profile?.displayName ?? 'FieldIQ manager'}</Text>
        {profileLoading ? <ActivityIndicator color="#d95d39" /> : null}
        <Text style={styles.meta}>{profile?.phone ?? profile?.email ?? 'No contact loaded'}</Text>
        {profileError ? <Text style={styles.error}>{profileError}</Text> : null}
        <Pressable style={styles.secondaryButton} onPress={loadProfile}>
          <Text style={styles.secondaryButtonText}>Refresh profile</Text>
        </Pressable>
      </Card>

      <Card>
        <Text style={styles.heading}>Notifications</Text>
        <Text style={styles.meta}>{deviceState}</Text>
        <Pressable style={[styles.button, registeringDevice && styles.buttonDisabled]} onPress={registerDevice} disabled={registeringDevice}>
          <Text style={styles.buttonText}>{registeringDevice ? 'Registering...' : 'Register this device'}</Text>
        </Pressable>
      </Card>

      <Card>
        <Text style={styles.heading}>Calendar</Text>
        {calendarLoading ? <ActivityIndicator color="#d95d39" /> : null}
        <Text style={styles.meta}>
          {calendarState?.connected
            ? `Connected${calendarState.provider ? ` via ${calendarState.provider}` : ''}.`
            : 'Not connected. Use the browser-based Google consent flow, then return here and refresh.'}
        </Text>
        {calendarState?.lastSyncedAt ? <Text style={styles.meta}>Last sync: {calendarState.lastSyncedAt}</Text> : null}
        {calendarState?.expiresAt ? <Text style={styles.meta}>Token expires: {calendarState.expiresAt}</Text> : null}
        {calendarError ? <Text style={styles.error}>{calendarError}</Text> : null}
        <Pressable
          style={[styles.button, calendarSubmitting && styles.buttonDisabled]}
          onPress={calendarState?.connected ? disconnectCalendar : connectCalendar}
          disabled={calendarSubmitting}
        >
          <Text style={styles.buttonText}>{calendarSubmitting ? 'Working...' : calendarState?.connected ? 'Disconnect calendar' : 'Connect Google Calendar'}</Text>
        </Pressable>
        <Pressable style={styles.secondaryButton} onPress={loadCalendar} disabled={calendarSubmitting}>
          <Text style={styles.secondaryButtonText}>Refresh calendar status</Text>
        </Pressable>
      </Card>
    </Screen>
  );
}

const styles = StyleSheet.create({
  heading: {
    fontSize: 20,
    fontWeight: '700',
    color: '#14281d',
  },
  meta: {
    color: '#6e665a',
    lineHeight: 20,
  },
  error: {
    color: '#b42318',
    lineHeight: 20,
  },
  button: {
    backgroundColor: '#14281d',
    borderRadius: 14,
    paddingVertical: 14,
    alignItems: 'center',
  },
  buttonText: {
    color: '#f6f4ee',
    fontWeight: '700',
  },
  buttonDisabled: {
    opacity: 0.6,
  },
  secondaryButton: {
    borderRadius: 14,
    paddingVertical: 12,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#c8c1b1',
  },
  secondaryButtonText: {
    color: '#2b2d2f',
    fontWeight: '600',
  },
});
