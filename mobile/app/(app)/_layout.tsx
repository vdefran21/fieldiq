import { useEffect } from 'react';
import { Tabs } from 'expo-router';
import { api } from '../../services/api';
import { registerForPushNotifications } from '../../services/notifications';

/**
 * Main tab layout for the phase-1 mobile app shell.
 */
export default function AppLayout() {
  useEffect(() => {
    async function registerDevice() {
      const result = await registerForPushNotifications();
      if (result.status === 'registered') {
        await api.user.registerDevice(result.token);
      }
    }

    void registerDevice().catch(() => undefined);
  }, []);

  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#14281d',
        tabBarInactiveTintColor: '#796d5a',
        tabBarStyle: {
          backgroundColor: '#fffdf8',
        },
      }}
    >
      <Tabs.Screen name="index" options={{ title: 'Schedule' }} />
      <Tabs.Screen name="team" options={{ title: 'Team' }} />
      <Tabs.Screen name="settings" options={{ title: 'Settings' }} />
      <Tabs.Screen name="create-team" options={{ href: null }} />
      <Tabs.Screen name="create-event" options={{ href: null }} />
      <Tabs.Screen name="start-negotiation" options={{ href: null }} />
      <Tabs.Screen name="join-negotiation" options={{ href: null }} />
      <Tabs.Screen name="negotiate/[id]" options={{ href: null }} />
    </Tabs>
  );
}
