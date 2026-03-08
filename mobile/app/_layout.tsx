import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';

/**
 * Root Expo Router layout for the FieldIQ mobile shell.
 */
export default function RootLayout() {
  return (
    <>
      <StatusBar style="dark" />
      <Stack screenOptions={{ headerShown: false }} />
    </>
  );
}
